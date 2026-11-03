# Independent Pi Agent Sidecar

Node 22 / published Pi 0.84.4. Java owns business instructions, tool schemas, authorization, device/task/report behavior, model policy and durable Pi Session storage in MySQL. Node owns the generic Pi loop, streaming, tool transport and checkpoint/recovery mechanics. It has no runtime dependency on `../iot-agent-runtime`, no database credentials and no SQLite or local session volume.

## Build and run

```sh
npm ci
npm run build
```

From the backend root, inject configuration into the launcher, not directly into Node:

```sh
set -a
source .env
set +a
python tools/start-sidecar.py
```

The launcher passes only the sidecar environment allowlist. An optional directory argument selects an immutable release containing `dist/`, `package.json` and installed dependencies. The application directory may be read-only; session data is not written there.

## Configuration

- `SIDECAR_HOST` / `SIDECAR_PORT`: loopback by default; private bridge only.
- `AGENT_BRIDGE_TOKEN`: Java → Node bearer authentication.
- `JAVA_AGENT_TOOLS_URL` / `JAVA_AGENT_TOOLS_TOKEN`: private Java tool callback.
- `JAVA_AGENT_SESSIONS_URL`: private Java `/internal/agent/sessions`; defaults to the tools URL's origin plus that path. Uses the same private callback token.
- `MODEL_BASE_URL`, `MODEL_ID`, `MODEL_API_KEY`: bootstrap only; production runs receive Java's immutable `definition.model` snapshot.
- `SIDECAR_RUN_TIMEOUT_MS`, `JAVA_AGENT_TOOL_TIMEOUT_MS`, `SIDECAR_HEARTBEAT_MS`, `AGENT_THINKING_LEVEL`: optional runtime limits.

Java must have MySQL enabled and available. There is no SQLite fallback and no old-session import. The one-time Java cutover retires old public conversations while preserving historical business facts. New MySQL conversations survive subsequent Java/Node replacement within the application's conversation retention policy.

## Private HTTP contract

All Node routes require `Authorization: Bearer $AGENT_BRIDGE_TOKEN`.

- `POST /run`: versioned generic run request; NDJSON response.
- `POST /cancel/:runId`: cancel the matching in-flight run.
- `GET /healthz`: sanitized service status.

Run fields: `protocolVersion:1`, `runId`, `conversationId`, `turnId`, `toolContextId`, `content`, opaque `sessionScope`, and `definition`. Unknown fields are rejected.

`definition` contains Java-owned `instructions`, `tools` (`name`, `label`, `description`, JSON Schema `parameters`), `maxTurns`, optional `maxToolCalls`, `runTimeoutMs`, `finalizationInstructions` and `model`. A model snapshot contains `modelId`, `modelBaseUrl`, `modelApiKey`, `contextWindow`, `maxOutputTokens`, `reasoning`, `thinkingLevel`, `supportsDeveloperRole`, `supportsReasoningEffort`, `supportsUsageInStreaming`, `maxTokensField`. Keys and private URLs are never emitted in public events or operational errors.

Node enforces the supplied loop/tool/time budgets. Java may return `halt:true` on a successful or failed tool response. Optional Java-supplied finalization instructions permit a tool-free final answer; no business fallback is embedded in Node.

Tool callbacks include the four run-binding IDs, protocol, tool name/call ID, arguments and `sessionWriter` fencing proof. Java resolves tenant/owner/source from its active authenticated run and rejects stale writers before dispatch. The session callback carries the same run binding and opaque virtual filesystem operations; it accepts neither database credentials nor tenant/source claims.

## Persistence and recovery

The public Pi `JsonlSessionRepo` runs against a remote virtual filesystem backed by Java/MySQL append chunks. Its name does not imply local JSONL files. Native Pi retains record/entry, branch, compaction and open-operation semantics. Java provides scoped transactions, version checks, idempotent commit receipts and writer-epoch fencing.

Tool intent and outcomes are checkpointed. After interruption, a committed outcome is recovered; an uncertain outcome becomes an explicit interrupted tool result. Node does not automatically replay a business effect. A running model request is not promised seamless continuation after process replacement.

### Public Pi API to private storage mapping

| Public API | Owner and durable operation |
| --- | --- |
| `SessionRepo.create/open/list/delete/fork/close` | Thin opaque-ID wrapper delegates to Pi's native repository. Only physical filename IDs are JSON/base64url encoded; public IDs and parent IDs round-trip unchanged. Deleted/recreated handles are invalidated. |
| metadata, lanes, lane moves, append entry/record, entry/branch/record/log queries, open operations, name/label/stats | Pi `Session` delegates to native storage unchanged; every wrapper checks the live handle. Pi owns validation, shared sequence, immutable reads, branch and compaction semantics. |
| `absolutePath/joinPath` | Pure POSIX virtual-path normalization; never opens a local file. |
| `readTextFile/readTextLines/fileInfo/listDir/exists` | Authenticated Java `read/lines/info/list/exists`, restricted to `/sessions` in the Java-derived scope. |
| `writeFile/appendFile/createDir/renameFile/remove` | Java `write/append/mkdir/rename/remove`; short scope-locked transaction with expected version, writer epoch and commit ID. |
| writer admission / close / tool dispatch | Java `claim/release/check`; four active-run IDs resolve server identity, not caller-supplied tenant/source. |

File failures map to Pi's `aborted`, `not_found`, `permission_denied`, `not_directory`, `is_directory`, `invalid`, `not_supported`, or `unknown`. Authentication, stale writers and conflicts fail closed. A transport/body-read failure may retry **one identical storage mutation** with the same commit ID and content; it never retries a model run or business tool. Append chunks are checked for missing sequence numbers rather than silently truncating history.

Public text also follows Runtime's generic reasoning-channel normalization: `<think>` / `<thinking>` spans, including split tag prefixes, are not published as answer/process text. The native durable model messages retain their original content; business errors and legitimate public text are not suppressed.


The former SQLite capacity, integrity, backup, restore and writer-lease HTTP operations are removed. Administration exposes only Java's `/api/admin/pi-session/health`; backups cover the authoritative MySQL database together with linked business facts and the cutover marker.

## Engineering verification versus acceptance

`npm test` exercises generic loop/transport/recovery behavior. MySQL contract tests require the separate local Java conformance fixture and isolated test database documented in the Pi Session tickets. The unchanged upstream SessionBackendConformance suite runs through Node → Java → real MySQL. No customer gateway or historical Runtime is contacted by those fixtures.

These are engineering checks, **not business acceptance**. Business acceptance requires real WeChat DevTools operations and screenshots, linked from `.scratch/pi-session-mysql/acceptance.md`. Never execute device control, create tasks or send production notifications during acceptance.
