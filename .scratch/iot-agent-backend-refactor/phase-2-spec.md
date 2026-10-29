# Java Backend Parity Phase 2

**Status:** ready-for-agent
**Date:** 2026-10-30
**Parent:** `spec.md`, ticket 10 parity readiness

## Problem Statement

The Java backend already preserves the public route surface and has a working Conversation → Node sidecar → `pi-agent-core` path, durable MySQL/Redis facts, Office read-only scope, local control fixtures, and durable one-shot/recurring task execution. It is not yet a behavioral replacement for `iot-agent-runtime`: Company device scope is modeled as one generic endpoint, Dashboard and Region composition are incorrectly treated as upstream reads, task change/cancellation proposals return 503, reports are not rendered locally, and video live/playback is not wired.

The Runtime source already contains the authoritative customer routes, payloads, state machines, fixtures, and regression tests for these gaps. Waiting for new customer protocol documentation is not a valid prerequisite for this phase.

## Solution

Migrate the remaining Runtime behavior into the Java topology in dependency order:

1. Build a Runtime-derived Company device catalog with multi-source area-tree traversal, pagination, deduplication, observation-only video devices, control references, hub bindings, and current-principal scope verification.
2. Compose Dashboard and Region responses in Java from authorized observation/device/task facts. Do not model local composition as a customer gateway endpoint.
3. Port task change and cancellation proposals as durable MySQL state machines with version CAS, expiry, authorization rechecks, idempotent confirmation, and no duplicate execution.
4. Persist and serve local Report artifacts, including metadata, authorization scope, PDF and XLSX downloads, replay, and stable report references.
5. Implement uncached Company/Office video live and playback entry points using the Runtime-derived device bindings and window/error rules.
6. Match Runtime Admin apply busy-gate and supervised-restart handoff; document Pi Session backup/restore and make the local sidecar boundary operationally verifiable.
7. Run the final parity gate and produce a no-production-cutover readiness report.

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

## Implementation Decisions

- Runtime source code, OpenAPI, NDJSON schema, Runtime module rules, and Runtime production-protocol fixtures are the behavior oracle.
- The highest test seam is the public HTTP/NDJSON boundary. Module tests use package-root interfaces; controller implementation classes remain internal.
- Company catalog reads use an explicit multi-source adapter. `AuthorizedDevice` carries the identity, observation/control binding, hub binding, location metadata, and observation-only flag required by Runtime semantics.
- Dashboard and Region are local composition modules over authorized catalog, observation, task, and notification facts. They do not require configurable customer routes for composition itself.
- Task change/cancellation facts are MySQL-authoritative and use CAS/version, explicit terminal statuses, expiry, authorization revalidation, and idempotent replay.
- Reports are locally generated artifacts. Redis never becomes the report source of truth.
- Video URLs are returned only on authorized requests, marked `no-store`, and never persisted as business facts.
- Admin apply is a supervised-restart contract, not hot reload. Busy work rejects apply; successful handoff does not claim success before the supervisor accepts it.
- Production cloud remains untouched. Real control writes, task scanner dual-run, notification dual-write, video decode acceptance, deployment cutover, and rollback execution remain out of this phase's local implementation checks.

## Testing Decisions

- Every ticket must add or extend an external-behavior test at the highest existing seam.
- Runtime fixtures are ported as in-process Java/WebClient exchange fixtures; no test contacts a real customer endpoint or device.
- Tests must cover normal, empty, partial, unauthorized, out-of-scope, malformed, timeout, duplicate, stale-version, expired, restart, and recovery behavior where relevant.
- Full verification is `mvn -q test`, the route/NDJSON contract gate, and a local health/business smoke with real local MySQL/Redis and the configured Node sidecar model.
- Tests must not assert SQL strings, framework wiring, mock echoes, or incidental defaults.

## Out of Scope

- Modifying or deploying the cloud production backend.
- Switching the mini-program endpoint or changing mini-program pages.
- Creating a second login system or replacing `pi-agent-core` with Java.
- Sending device control, task scanner, notification, or configuration writes to the customer production gateway.
- Claiming successful production video decoding or production cutover without an external acceptance window.

## Further Notes

- Ticket 11 is the first ready slice. It unblocks Dashboard/Region, Video, and production-control protocol plumbing.
- Tickets 12–16 are independent after their listed blockers and can be worked on separate integration branches, but the final parity ticket must wait for all of them.
- The existing ticket 10 remains the release-readiness parent; these tickets replace its obsolete assumption that customer protocol documents are required before implementation.
