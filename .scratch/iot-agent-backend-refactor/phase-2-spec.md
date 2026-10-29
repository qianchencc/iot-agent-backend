# Java Backend Parity Phase 2

**Status:** implementation-in-progress-with-independent-sidecar-core
**Date:** 2026-10-30
**Parent:** `spec.md`, ticket 10 parity readiness

## Problem Statement

The replacement must not depend on `../iot-agent-runtime` at runtime, as a source checkout, or as an imported application module. `iot-agent-runtime` remains a behavior oracle only. The refactor ships an independent Node sidecar service that retains `pi-agent-core` capability and exposes a private contract to the Java backend. The core sidecar process is now locally executable; full Runtime event/tool parity and evidence remain tickets 19–22.

The Runtime source already contains the authoritative customer routes, payloads, state machines, fixtures, and regression tests for the remaining gaps. Waiting for new customer protocol documentation is not a valid prerequisite for local implementation; production control/video/cutover acceptance remains external.

## Independent Node Sidecar Contract

The Node sidecar is a separate service and deliverable, not the original Runtime process:

- owns `pi-agent-core` / `pi-ai` model execution and Pi Session SQLite;
- exposes private loopback routes for `/run`, `/cancel`, `/internal/agent/tools`, Pi Session operations, and supervised apply handoff;
- calls Java `/internal/agent/tools` for business facts and tools, never accesses Java MySQL directly;
- uses an explicit bridge token and never exposes private routes through the public mini-program gateway;
- is built and tested from its own package directory with no import or runtime filesystem dependency on `../iot-agent-runtime`;
- keeps Java MySQL business facts and Node Pi Session facts as separate authorities.

The Java backend remains the only public API and remains the owner of customer identity, HTTP/NDJSON response contracts, authorization, durable business facts, and public error mapping.

## User Stories

1. As a Company user, I want the device catalog to include environment, energy, safety, lighting, access, AC, and video devices from the current authorized scope, so that Agent answers do not omit categories.
2. As a Company user, I want Dashboard sections to remain useful when an unrelated observation source fails, so that a safety outage does not erase environment results.
3. As an Office user, I want region devices, states, and seen markers to use the same current authorization scope as Runtime, so that stale or cross-user facts cannot appear.
4. As a task owner, I want to edit, skip, pause, resume, and cancel tasks through explicit proposals, so that no destructive change occurs before confirmation.
5. As a task owner, I want stale versions, expired proposals, revoked devices, and immutable history to be rejected without side effects.
6. As a report consumer, I want a saved report to remain readable and downloadable after restart without re-running upstream measurements.
7. As a report consumer, I want PDF/XLSX output to retain Runtime metadata, title, scope, status, and partial-result semantics.
8. As a video consumer, I want live/playback URLs to be issued only after current authorization and to expire by normal upstream semantics, without being persisted or cached as facts.
9. As an administrator, I want apply to reject active work and otherwise hand off to a supervisor, so that configuration changes cannot interrupt an in-flight run or execution.
10. As an operator, I want Pi Session backup, restore, integrity, and capacity checks to be explicit, so that Node-side durable context is not confused with Java business facts.
11. As a release operator, I want all remaining parity differences reported before any production cutover, so that the old Runtime remains a safe rollback authority.
12. As the Java backend operator, I want the independent Node sidecar to run and recover `pi-agent-core` sessions without importing or starting `iot-agent-runtime`, so that the Java backend can replace Runtime while retaining the Node agent capability.
13. As the Java backend, I want the sidecar bridge and Pi Session operations to have a private authenticated contract, so that customer-facing HTTP never exposes model/session internals.

## Implementation Decisions

- Runtime source code, OpenAPI, NDJSON schema, Runtime module rules, and Runtime production-protocol fixtures are the behavior oracle.
- The highest test seam is the public HTTP/NDJSON boundary. Module tests use package-root interfaces; controller implementation classes remain internal.
- Company catalog reads use an explicit multi-source adapter. `AuthorizedDevice` carries the identity, observation/control binding, hub binding, location metadata, and observation-only flag required by Runtime semantics.
- Dashboard and Region are local composition modules over authorized catalog, observation, task, and notification facts. They do not require configurable customer routes for composition itself.
- Task change/cancellation facts are MySQL-authoritative and use CAS/version, explicit terminal statuses, expiry, authorization revalidation, and idempotent replay.
- Reports are locally generated artifacts. Redis never becomes the report source of truth.
- Video URLs are returned only on authorized requests, marked `no-store`, and never persisted as business facts.
- Admin apply is a supervised-restart contract, not hot reload. Busy work rejects apply; successful handoff does not claim success before the supervisor accepts it.
- The independent Node sidecar is a separately buildable service in the backend deliverable. It may depend on published `pi-agent-core`, `pi-ai`, and Pi Session SQLite packages, but never imports Runtime source or reads Runtime files at runtime.
- The sidecar contract includes authenticated NDJSON `/run`, `/cancel`, Java tool callbacks, Pi Session backup/restore/integrity/capacity, writer-lease recovery, and supervised apply handoff. The Java backend owns the public HTTP and durable business facts.
- Production cloud remains untouched. Real control writes, task scanner dual-run, notification dual-write, video decode acceptance, deployment cutover, and rollback execution remain out of this phase's local implementation checks.

## Testing Decisions

- Every ticket must add or extend an external-behavior test at the highest existing seam.
- Runtime fixtures are ported as in-process Java/WebClient exchange fixtures; no test contacts a real customer endpoint or device.
- Tests must cover normal, empty, partial, unauthorized, out-of-scope, malformed, timeout, duplicate, stale-version, expired, restart, and recovery behavior where relevant.
- Full verification is `mvn -q test`, the route/NDJSON contract gate, and a local health/business smoke with real local MySQL/Redis and the configured Node sidecar model.
- Tests must not assert SQL strings, framework wiring, mock echoes, or incidental defaults.
- Sidecar tests start the independent Node service from its own package directory, exercise the private HTTP/NDJSON contract, and prove a clean install/build does not resolve `../iot-agent-runtime`.

## Out of Scope

- Modifying or deploying the cloud production backend.
- Switching the mini-program endpoint or changing mini-program pages.
- Creating a second login system or replacing `pi-agent-core` with Java.
- Sending device control, task scanner, notification, or configuration writes to the customer production gateway.
- Claiming successful production video decoding or production cutover without an external acceptance window.

## Completion tickets added after audit

- Ticket 18 closes the independent sidecar core: package, published Pi execution, SQLite operations, private auth, Java callback, and local model/process smoke.
- Ticket 19 closes remaining Runtime task-plan semantics: scoped cancellation, resume confirmation, occurrence validation, and query boundaries.
- Ticket 20 closes conversation-generated reports, attachment idempotency, block-level device authorization, and report fixtures.
- Ticket 21 replaces source/static parity claims with behavioral fixture parity, migration, shadow, single-owner execution, and recovery evidence.
- Ticket 22 closes sidecar event/tool union, active cancellation/timeout/disconnect/restart tests, Java adapter process tests, and backup-path/redaction hardening.
- Ticket 17 is blocked by 19, 20, 21, and 22 in addition to 11–16; it is not complete when route inventory alone passes.
- The production cloud backend remains untouched and is not a local acceptance target.
