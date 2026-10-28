#!/usr/bin/env python3
"""Deterministic, local-only migration and parity gates for Ticket 10.

This tool only reads local contract/evidence files and, for sqlite backup/restore,
local sqlite files. It never starts a process, sends HTTP, invokes a deployment
command, or changes a route. Every command emits one JSON evidence document and
returns non-zero on an unsafe or incomplete result.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sqlite3
from pathlib import Path
from typing import Any, Iterable

HTTP_METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
READ_ONLY_METHODS = {"GET", "HEAD", "OPTIONS"}
TERMINAL_STATUSES = {
    "run.completed", "run.awaiting_confirmation", "run.failed", "run.aborted",
    "completed", "failed", "aborted", "cancelled", "CANCELLED", "EXPIRED",
    "SCHEDULED", "CONFIRMED", "NO_CHANGE", "unknown", "UNKNOWN",
}
REQUIRED_EVENT_TYPES = {
    "run.started", "run.completed", "run.awaiting_confirmation", "run.failed",
    "run.aborted", "tool.started", "tool.completed", "tool.progress",
    "action.proposed", "action.resolved", "task.proposed", "task.resolved",
    "task.confirmation", "task.cancellation.proposed", "task.cancellation.resolved",
    "task.change.proposed", "task.change.resolved", "message.delta",
    "message.completed", "message.reclassified", "observation.reported", "heartbeat",
}
FORBIDDEN_SHADOW_WORDS = (
    "/confirm", "/cancel", "/apply", "/restore", "/revalidate", "/selection",
    "/parameters", "/seen", "/messages", "/test-model",
)
SINGLE_RUN_COMPONENTS = {"control", "scanner", "notification"}


def emit(payload: dict[str, Any], code: int = 0) -> int:
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    return code


def fail(check: str, errors: Iterable[str], **evidence: Any) -> int:
    return emit({"check": check, "status": "failed", "errors": list(errors), **evidence}, 1)


def ok(check: str, **evidence: Any) -> int:
    return emit({"check": check, "status": "passed", **evidence})


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"cannot read JSON {path}: {exc}") from exc


def openapi_inventory(path: Path) -> tuple[set[str], list[tuple[str, str]]]:
    document = load_json(path)
    paths = document.get("paths")
    if not isinstance(paths, dict):
        raise ValueError("OpenAPI document has no object-valued paths")
    path_names = set(paths)
    operations: list[tuple[str, str]] = []
    for route, item in paths.items():
        if not isinstance(item, dict):
            continue
        for method, operation in item.items():
            if method.lower() in HTTP_METHODS and isinstance(operation, dict):
                operations.append((method.upper(), route))
    return path_names, sorted(operations)


def java_inventory(root: Path) -> tuple[set[str], list[tuple[str, str]]]:
    """Extract literal Spring mappings; this is deliberately conservative."""
    paths: set[str] = set()
    operations: list[tuple[str, str]] = []
    mapping = re.compile(r"@(Get|Post|Put|Patch|Delete|Head|Request)Mapping(?:\s*\((.*?)\))?", re.S)
    string_arg = re.compile(r"(?:value|path)\s*=\s*\"([^\"]*)\"|^\s*\"([^\"]*)\"")
    for source in sorted(root.rglob("*.java")):
        text = source.read_text(encoding="utf-8")
        class_position = text.find("class ")
        matches = list(mapping.finditer(text))
        base = ""
        for match in matches:
            kind, args = match.group(1), match.group(2) or ""
            values = string_arg.search(args)
            suffix = (values.group(1) or values.group(2)) if values else ""
            if kind == "Request":
                # A class-level mapping occurs before the class declaration.
                if class_position < 0 or match.start() < class_position:
                    base = suffix
                continue
            route = join_route(base, suffix)
            paths.add(route)
            operations.append((kind.upper(), route))
    return paths, sorted(operations)

def join_route(base: str, suffix: str) -> str:
    if not base:
        return suffix or "/"
    if not suffix:
        return base
    return base.rstrip("/") + "/" + suffix.lstrip("/")


def check_contract(args: argparse.Namespace) -> int:
    runtime = Path(args.runtime_root)
    backend = Path(args.backend_root)
    openapi = runtime / "docs/api/openapi.json"
    ndjson = runtime / "docs/api/ndjson-events.schema.yaml"
    if not openapi.is_file() or not ndjson.is_file():
        return fail("contract", [f"missing contract file: {p}" for p in (openapi, ndjson)])
    try:
        runtime_paths, runtime_ops = openapi_inventory(openapi)
        java_paths, java_ops = java_inventory(backend / "src/main/java")
        document = load_json(openapi)
    except ValueError as exc:
        return fail("contract", [str(exc)])
    expected_paths = len(runtime_paths)
    expected_ops = len(runtime_ops)
    security = document.get("components", {}).get("securitySchemes", {})
    error_schema = document.get("components", {}).get("schemas", {}).get("ErrorResponse", {})
    schema_text = ndjson.read_text(encoding="utf-8")
    errors: list[str] = []
    if expected_paths != args.expected_paths:
        errors.append(f"contract path count {expected_paths} != expected {args.expected_paths}")
    if expected_ops != args.expected_operations:
        errors.append(f"contract operation count {expected_ops} != expected {args.expected_operations}")
    missing_paths = sorted(runtime_paths - java_paths)
    extra_paths = sorted(java_paths - runtime_paths)
    missing_operations = sorted(set(runtime_ops) - set(java_ops))
    extra_operations = sorted(set(java_ops) - set(runtime_ops))
    if missing_paths or extra_paths:
        errors.append("Java route inventory differs from Runtime paths")
    if missing_operations or extra_operations:
        errors.append("Java route inventory differs from Runtime operations")
    for name in ("agentBearer", "tenantCode", "adminCookie", "adminCsrf"):
        if name not in security:
            errors.append(f"missing security scheme {name}")
    if "message" not in error_schema.get("required", []):
        errors.append("ErrorResponse must require message")
    source_text = "\n".join(p.read_text(encoding="utf-8") for p in sorted((backend / "src/main/java").rglob("*.java")))
    error_response = backend / "src/main/java/ltd/qianc/iotagent/error/ErrorResponse.java"
    error_text = error_response.read_text(encoding="utf-8") if error_response.is_file() else ""
    for field in ("code", "message", "recovery"):
        if field not in error_schema.get("properties", {}):
            errors.append(f"ErrorResponse schema missing {field}")
        if field not in error_text:
            errors.append(f"ErrorResponse.java has no evidence for {field}")
    for token in ("sequence:", "type:", "phase:", "heartbeat", "replyVersion:"):
        if token not in schema_text:
            errors.append(f"NDJSON schema missing {token}")
    for event_type in sorted(REQUIRED_EVENT_TYPES):
        if not re.search(rf"^\s*-\s+{re.escape(event_type)}\s*$", schema_text, re.MULTILINE):
            errors.append(f"NDJSON schema missing event type {event_type}")
    for token in ("Authorization", "Tenantcode", "source", "application/x-ndjson", "ErrorResponse", "sequence", "run.completed", "run.aborted", "message.delta"):
        if token not in source_text:
            errors.append(f"Java source has no evidence for {token}")
    evidence = {
        "runtime": str(runtime), "pathCount": expected_paths, "operationCount": expected_ops,
        "javaPathCount": len(java_paths), "javaOperationCount": len(java_ops),
        "missingJavaPaths": missing_paths, "extraJavaPaths": extra_paths,
        "missingJavaOperations": missing_operations, "extraJavaOperations": extra_operations,
        "runtimeOperations": runtime_ops,
    }
    return fail("contract", errors, **evidence) if errors else ok("contract", **evidence)


def sqlite_integrity(path: Path) -> tuple[bool, str]:
    try:
        with sqlite3.connect(f"file:{path.resolve()}?mode=ro", uri=True) as db:
            result = db.execute("PRAGMA integrity_check").fetchone()
            value = str(result[0]) if result else ""
            return value.lower() == "ok", value
    except sqlite3.Error as exc:
        return False, str(exc)


def check_disk(path: Path, minimum: int) -> int:
    if not path.exists():
        return fail("sqlite-disk", [f"database does not exist: {path}"])
    free = shutil.disk_usage(path.parent).free
    valid, integrity = sqlite_integrity(path)
    errors = []
    if free < minimum:
        errors.append(f"free bytes {free} below minimum {minimum}")
    if not valid:
        errors.append(f"integrity_check={integrity}")
    evidence = {"database": str(path), "freeBytes": free, "minimumFreeBytes": minimum, "integrity": integrity}
    return fail("sqlite-disk", errors, **evidence) if errors else ok("sqlite-disk", **evidence)


def check_backup(args: argparse.Namespace) -> int:
    source, destination = Path(args.source), Path(args.destination)
    if not source.is_file():
        return fail("sqlite-backup", [f"source does not exist: {source}"])
    if destination.exists() and not args.force:
        return fail("sqlite-backup", [f"destination exists; pass --force to replace: {destination}"])
    destination.parent.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(destination.parent).free
    if free < args.min_free_bytes:
        return fail("sqlite-backup", [f"free bytes {free} below minimum {args.min_free_bytes}"], freeBytes=free)
    valid, integrity = sqlite_integrity(source)
    if not valid:
        return fail("sqlite-backup", [f"source integrity_check={integrity}"])
    temporary = destination.with_name(f".{destination.name}.tmp-{os.getpid()}")
    try:
        if temporary.exists():
            temporary.unlink()
        with sqlite3.connect(f"file:{source.resolve()}?mode=ro", uri=True) as src, sqlite3.connect(temporary) as dst:
            src.backup(dst)
            dst.commit()
        copied_ok, copied_integrity = sqlite_integrity(temporary)
        if not copied_ok:
            return fail("sqlite-backup", [f"backup integrity_check={copied_integrity}"])
        os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink()
    return ok("sqlite-backup", source=str(source), destination=str(destination), sourceIntegrity=integrity, backupIntegrity=copied_integrity, freeBytes=free)


def check_restore(args: argparse.Namespace) -> int:
    backup, destination = Path(args.backup), Path(args.destination)
    if not backup.is_file():
        return fail("sqlite-restore", [f"backup does not exist: {backup}"])
    valid, integrity = sqlite_integrity(backup)
    if not valid:
        return fail("sqlite-restore", [f"backup integrity_check={integrity}"])
    destination.parent.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(destination.parent).free
    if free < args.min_free_bytes:
        return fail("sqlite-restore", [f"free bytes {free} below minimum {args.min_free_bytes}"], freeBytes=free)
    if destination.exists() and not args.apply:
        return fail("sqlite-restore", [f"destination exists; restore is dry-run unless --apply is explicit: {destination}"])
    temporary = destination.with_name(f".{destination.name}.restore-{os.getpid()}")
    try:
        if temporary.exists():
            temporary.unlink()
        with sqlite3.connect(f"file:{backup.resolve()}?mode=ro", uri=True) as src, sqlite3.connect(temporary) as dst:
            src.backup(dst)
            dst.commit()
        restored_ok, restored_integrity = sqlite_integrity(temporary)
        if not restored_ok:
            return fail("sqlite-restore", [f"restored integrity_check={restored_integrity}"])
        if args.apply:
            os.replace(temporary, destination)
    finally:
        if temporary.exists():
            temporary.unlink()
    return ok("sqlite-restore", backup=str(backup), destination=str(destination), applied=bool(args.apply), backupIntegrity=integrity, restoredIntegrity=restored_integrity, freeBytes=free)


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path}:{number}: invalid JSON: {exc}") from exc
        if not isinstance(value, dict):
            raise ValueError(f"{path}:{number}: expected an object")
        rows.append(value)
    return rows


def check_migration(args: argparse.Namespace) -> int:
    try:
        source, target = read_jsonl(Path(args.source)), read_jsonl(Path(args.target))
    except (OSError, ValueError) as exc:
        return fail("migration", [str(exc)])
    errors: list[str] = []
    identity, order, version, status = args.identity, args.order, args.version, args.status
    if not source or not target:
        errors.append("source and target evidence must contain at least one fact")
    for label, rows in (("source", source), ("target", target)):
        for index, row in enumerate(rows, 1):
            for field in (identity, order, version, status):
                if field not in row:
                    errors.append(f"{label} row {index} missing {field}")
    def identities(rows: list[dict[str, Any]], label: str) -> list[str]:
        values = [str(row.get(identity)) for row in rows]
        if len(values) != len(set(values)):
            errors.append(f"{label} contains duplicate {identity}")
        return values
    source_ids, target_ids = identities(source, "source"), identities(target, "target")
    if len(source) != len(target):
        errors.append(f"row count differs source={len(source)} target={len(target)}")
    if source_ids != target_ids:
        errors.append("identity/order sequence differs")
    if not errors:
        for index, (before, after) in enumerate(zip(source, target), 1):
            if before[order] != after[order]:
                errors.append(f"row {index} order field changed")
            if before[version] != after[version]:
                errors.append(f"row {index} version changed")
            if before[status] in args.terminal_values.split(",") and before[status] != after[status]:
                errors.append(f"row {index} terminal status changed")
    evidence = {"sourceRows": len(source), "targetRows": len(target), "identityField": identity, "orderField": order, "versionField": version, "statusField": status, "terminalValues": args.terminal_values.split(",")}
    return fail("migration", errors, **evidence) if errors else ok("migration", **evidence)


def check_shadow(args: argparse.Namespace) -> int:
    try:
        records = read_jsonl(Path(args.records))
    except (OSError, ValueError) as exc:
        return fail("shadow", [str(exc)])
    errors: list[str] = []
    if not records:
        errors.append("shadow evidence must contain at least one request")
    allowed: list[dict[str, Any]] = []
    for index, record in enumerate(records, 1):
        method = str(record.get("method", "")).upper()
        path = str(record.get("path", ""))
        if method not in READ_ONLY_METHODS:
            errors.append(f"record {index} uses non-read method {method or '<missing>'}")
        if not path:
            errors.append(f"record {index} is missing path")
        if any(word in path.lower() for word in FORBIDDEN_SHADOW_WORDS):
            errors.append(f"record {index} is outside read-only shadow boundary: {path}")
        allowed.append({"method": method, "path": path})
    evidence = {"records": len(records), "allowed": allowed, "boundary": "GET/HEAD/OPTIONS only; control/task scanner/notification writes excluded"}
    return fail("shadow", errors, **evidence) if errors else ok("shadow", **evidence)


def check_execution(args: argparse.Namespace) -> int:
    try:
        records = read_jsonl(Path(args.records))
    except (OSError, ValueError) as exc:
        return fail("execution-boundary", [str(exc)])
    errors: list[str] = []
    if not records:
        errors.append("execution evidence must contain at least one invocation")
    seen: set[tuple[str, str]] = set()
    for index, record in enumerate(records, 1):
        component = str(record.get("component", ""))
        execution_id = str(record.get("executionId", ""))
        mode = str(record.get("mode", ""))
        key = (component, execution_id)
        if component not in SINGLE_RUN_COMPONENTS:
            errors.append(f"record {index} component must be one of {sorted(SINGLE_RUN_COMPONENTS)}")
            continue
        if not execution_id:
            errors.append(f"record {index} missing executionId for {component}")
        if mode != "primary":
            errors.append(f"record {index} {component} mode must be primary, got {mode or '<missing>'}")
        if key in seen:
            errors.append(f"duplicate {component} executionId {execution_id}")
        seen.add(key)
    evidence = {"records": len(records), "singleRunComponents": sorted(SINGLE_RUN_COMPONENTS), "uniqueExecutionKeys": len(seen)}
    return fail("execution-boundary", errors, **evidence) if errors else ok("execution-boundary", **evidence)


def evidence_passed(path: Path, label: str, required_fields: tuple[str, ...] = ()) -> list[str]:
    try:
        value = load_json(path)
    except ValueError as exc:
        return [str(exc)]
    if not isinstance(value, dict) or value.get("status") != "passed":
        return [f"{label} evidence must be a JSON object with status=passed"]
    errors = []
    for field in required_fields:
        if field not in value:
            errors.append(f"{label} evidence missing {field}")
    return errors


def check_cutover(args: argparse.Namespace) -> int:
    errors: list[str] = []
    approval = Path(args.approval)
    if not args.operator.strip():
        errors.append("human operator identity is required")
    if not args.window.strip():
        errors.append("deployment window identifier is required")
    try:
        approval_doc = load_json(approval)
        if not isinstance(approval_doc, dict) or approval_doc.get("status") != "passed":
            errors.append("human approval evidence must be status=passed")
        if not isinstance(approval_doc, dict) or approval_doc.get("approved") is not True:
            errors.append("human approval evidence must set approved=true")
        if not isinstance(approval_doc, dict) or not str(approval_doc.get("operator", "")).strip():
            errors.append("human approval evidence must identify an operator")
        elif str(approval_doc["operator"]).strip() != args.operator.strip():
            errors.append("approval operator does not match --operator")
    except ValueError as exc:
        errors.append(str(exc))
    try:
        health_doc = load_json(Path(args.health))
        if not isinstance(health_doc, dict) or health_doc.get("status") != "passed":
            errors.append("health evidence must be status=passed")
        else:
            components = health_doc.get("components")
            if not isinstance(components, dict):
                errors.append("health evidence must include components")
            else:
                for component in ("java", "node", "mysql", "redis"):
                    if components.get(component) not in ("ok", "healthy", "passed"):
                        errors.append(f"health component {component} is not healthy")
    except ValueError as exc:
        errors.append(str(exc))
    errors.extend(evidence_passed(Path(args.backup), "backup"))
    errors.extend(evidence_passed(Path(args.migration), "migration"))
    errors.extend(evidence_passed(Path(args.shadow), "shadow"))
    errors.extend(evidence_passed(Path(args.execution), "execution"))
    errors.extend(evidence_passed(Path(args.logs), "logs", ("coverage",)))
    errors.extend(evidence_passed(Path(args.config), "config", ("configuration",)))
    errors.extend(evidence_passed(Path(args.rollback), "rollback", ("oldRuntimeRestorable", "doubleRunPrevented")))
    try:
        rollback_doc = load_json(Path(args.rollback))
        if isinstance(rollback_doc, dict):
            if rollback_doc.get("oldRuntimeRestorable") is not True:
                errors.append("rollback evidence must set oldRuntimeRestorable=true")
            if rollback_doc.get("doubleRunPrevented") is not True:
                errors.append("rollback evidence must set doubleRunPrevented=true")
    except ValueError:
        pass
    evidence = {"oneWay": True, "action": "gate-only; no route switch or process command is executed", "operator": args.operator, "window": args.window}
    return fail("cutover-gate", errors, **evidence) if errors else ok("cutover-gate", **evidence)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="command", required=True)
    contract = sub.add_parser("contract", help="derive and compare Runtime contract inventory")
    contract.add_argument("--runtime-root", required=True)
    contract.add_argument("--backend-root", required=True)
    contract.add_argument("--expected-paths", type=int, default=66)
    contract.add_argument("--expected-operations", type=int, default=69)
    contract.set_defaults(run=check_contract)
    disk = sub.add_parser("sqlite-disk", help="check local Pi SQLite capacity and integrity")
    disk.add_argument("--path", required=True)
    disk.add_argument("--min-free-bytes", type=int, default=1)
    disk.set_defaults(run=lambda a: check_disk(Path(a.path), a.min_free_bytes))
    backup = sub.add_parser("sqlite-backup", help="make and verify a local SQLite backup")
    backup.add_argument("--source", required=True)
    backup.add_argument("--destination", required=True)
    backup.add_argument("--min-free-bytes", type=int, default=1)
    backup.add_argument("--force", action="store_true")
    backup.set_defaults(run=check_backup)
    restore = sub.add_parser("sqlite-restore", help="verify or explicitly apply a local SQLite restore")
    restore.add_argument("--backup", required=True)
    restore.add_argument("--destination", required=True)
    restore.add_argument("--min-free-bytes", type=int, default=1)
    restore.add_argument("--apply", action="store_true")
    restore.set_defaults(run=check_restore)
    migration = sub.add_parser("migration", help="compare exported fact JSONL without writing either side")
    migration.add_argument("--source", required=True)
    migration.add_argument("--target", required=True)
    migration.add_argument("--identity", default="id")
    migration.add_argument("--order", default="sequence")
    migration.add_argument("--version", default="version")
    migration.add_argument("--status", default="status")
    migration.add_argument("--terminal-values", default=",".join(sorted(TERMINAL_STATUSES)))
    migration.set_defaults(run=check_migration)
    shadow = sub.add_parser("shadow", help="enforce read-only shadow request boundaries")
    shadow.add_argument("--records", required=True)
    shadow.set_defaults(run=check_shadow)
    execution = sub.add_parser("execution-boundary", help="reject control/scanner/notification double runs")
    execution.add_argument("--records", required=True)
    execution.set_defaults(run=check_execution)
    cutover = sub.add_parser("cutover-gate", help="validate prerequisites; never performs a cutover")
    cutover.add_argument("--approval", required=True)
    cutover.add_argument("--operator", required=True)
    cutover.add_argument("--window", required=True)
    cutover.add_argument("--health", required=True)
    cutover.add_argument("--backup", required=True)
    cutover.add_argument("--migration", required=True)
    cutover.add_argument("--shadow", required=True)
    cutover.add_argument("--logs", required=True)
    cutover.add_argument("--config", required=True)
    cutover.add_argument("--rollback", required=True)
    cutover.add_argument("--execution", required=True)
    cutover.set_defaults(run=check_cutover)
    return root


def main() -> int:
    args = parser().parse_args()
    try:
        return int(args.run(args))
    except (OSError, sqlite3.Error, ValueError) as exc:
        return fail(args.command, [str(exc)])


if __name__ == "__main__":
    raise SystemExit(main())
