# 19: Task Plan Runtime Parity

**What to build:** Complete task/plan change, cancellation, occurrence, timeline, calendar, pause, resume, and authorization semantics so Java is a behavioral replacement for Runtime without executing customer production writes.

**Blocked by:** 03: MySQL / Redis 业务事实基础设施；07: Action Draft、控制安全与 Readback；08: Task、Plan、Notification 与 Durable Scheduler

**Status:** implemented-local-fingerprint-and-durable-fixture-matrix

- [x] Plan cancellation accepts Runtime `{version, scope, date?}` semantics and transactionally records occurrence/all cancellation without deleting immutable history.
- [x] Pending future materialized phases are cancelled exactly once; executing, unknown, and terminal phases remain untouched.
- [x] Plan resume requires explicit confirmation and revalidates current authorized device scope/bindings; unavailable scope fails closed.
- [x] Occurrence changes validate every phase timestamp, ordering, interval bounds, future-only rule, recurrence compatibility, and immutable history at proposal and confirmation.
- [x] Timeline/calendar/region/device query shapes and Asia/Shanghai date boundaries match the current Java/Runtime-compatible seam.
- [x] MySQL-backed regression fixtures cover cancellation/resume/occurrence persistence and CAS history semantics; execution is explicitly gated to a configured fixture database.
- [x] Authorized scope exposes deterministic non-secret catalog and stronger control-binding fingerprints; task/plan resume validates supplied fingerprints and explicit binding fields fail closed.
- [x] Production execution gateway remains fail-closed; no customer control or scanner run is used as evidence.

## Comments

- 2026-10-30：Java now derives deterministic SHA-256 catalog and control-binding fingerprints from the authorized scope. Task/plan resume rejects a stale supplied fingerprint or binding field while preserving the existing ID/kind/observation-only checks. A MySQL-gated fixture matrix records pending, executing, unknown and terminal cancellation behavior plus occurrence CAS/past/materialized rejection. These fixtures were added without production access and were not executed in this slice.

- Runtime source is behavior evidence only. The Java domain must remain the sole durable authority for migrated task facts.
- Ticket 17 remains blocked until this ticket and the final parity evidence ticket pass.
