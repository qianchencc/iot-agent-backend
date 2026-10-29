# 20: Report Conversation Flow and Content Authorization

**What to build:** Complete Runtime-equivalent report generation from conversation/tool flow, durable attachment idempotency, fixed device membership, report-block authorization, and public report download/read behavior.

**Blocked by:** 04: Conversation 与 Pi Agent 生产闭环；06: Company Observation、Report、Video 只读闭环；11: Company 多源设备目录与 Control Reference；14: Report Artifact 与 PDF/XLSX

**Status:** resolved-local-conversation-report-flow-and-authorization; customer-runtime-parity-gate-open

- [x] Java exposes a narrow report-generation application seam to the authenticated Agent tool bridge; sidecar can invoke it without owning MySQL facts.
- [x] Conversation report generation is idempotent by conversation/turn/request key and stores immutable report metadata, snapshot, origin, status, and attachments in MySQL.
- [x] Fixed device/meter membership is persisted and every device reference in report blocks, authorization fields, and attachments is checked against the current authorized scope.
- [x] Local public application-entry fixture covers bridge generation plus report read/download projections (PDF/XLSX), with no Runtime or customer gateway; direct MVC route fixture is intentionally not used because deep-module tests may only call package-root entry points.
- [x] Tests cover preparing/partial/failed/completed reports, duplicate requests, revoked/missing device references, cross-tenant access, source rejection, and unsupported format mapping.
- [x] PDF/XLSX responses preserve status, filename, content type, `no-store`, and safe error mapping after restart; public renderer injection covers deterministic failure mapping, and the conditional MySQL fixture exercises durable idempotency across service reconstruction.
- [x] No report test contacts customer production or writes to Redis as report truth.

## Comments

- Existing terminal task checkpoint producer and renderers are retained; this ticket closes the missing conversation-generated report path and block-level authorization.
- Public HTTP contract coverage uses `ReportHttpApplicationFactory` and does not import the internal MVC controller.
