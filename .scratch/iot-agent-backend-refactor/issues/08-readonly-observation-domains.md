# 08: Company / Office 只读业务闭环

**What to build:** 迁移小程序依赖的 dashboard、environment、safety、energy、region、video 和 report 只读能力，保持事实、缺数、授权和部分结果语义。

**Blocked by:** 04: Conversation 流式闭环；05: IoT 身份与 Authorized Device Scope

**Status:** implemented locally (fail-closed observation adapter; customer read schemas pending)

- [x] Company dashboard 和 Office region HTTP routes preserve source boundaries and require the current identity.
- [x] Environment, energy, safety, video, report and region routes preserve typed query/source errors and delegate reads without control.
- [x] Missing, stale, unverified, partial-coverage and read failures have explicit adapter response/error states; no fake empty success is produced.
- [ ] Exact upstream payload parity and report rendering cannot be claimed until customer route schemas are supplied.
- [ ] Existing miniapp cards can consume the raw adapter payload only after a customer protocol adapter is supplied.
- [x] Java parity shell is read-only and does not trigger real control.

## Implementation notes

- Added the public `ltd.qianc.iotagent.iot.ObservationAdapter` seam, typed `ObservationRequest`/`ObservationResponse`/`ObservationStatus`/`ObservationFailure` contracts, and `ReportDownload` binary seam.
- Added Runtime-compatible source-checked routes under `observation.internal.controller.ObservationController` for dashboard, environment history, safety records/cameras, Office regions and timelines, energy history/comparison/reports, video stream and report read/download.
- The default Spring adapter is `FailClosedObservationAdapter`; local in-process protocol fixtures can provide an `ObservationAdapter` bean without network access. It returns `OBSERVATION_UPSTREAM_UNAVAILABLE` (503) rather than fabricated devices, readings, reports or downloads.

## Unresolved upstream contract gap

The Java repository currently exposes only an authorized device-scope endpoint. The customer has not supplied stable Java endpoint paths and payload schemas for Company observation catalogs, environment samples, safety ledgers, meter history/statistics/comparison, Office region/task projections, short-lived video URLs, report facts, or PDF/XLSX rendering. Consequently the shell validates and preserves the Runtime route/source/error boundary, but cannot assert full payload/card parity or implement a real WebClient observation adapter yet. Supplying those schemas is required before marking the remaining parity checkboxes complete.
