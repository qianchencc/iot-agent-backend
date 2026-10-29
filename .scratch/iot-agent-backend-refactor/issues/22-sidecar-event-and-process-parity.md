# 22: Sidecar Event, Tool, and Process Parity

**What to build:** Complete the independent sidecar contract beyond the core model/session bridge so it can replace Runtime's agent execution boundary without importing or starting Runtime.

**Blocked by:** 04: Conversation 与 Pi Agent 生产闭环；18: Independent Node Pi Agent Sidecar

**Status:** implemented-independent-process-contract; runtime-business-union-external-gates

- [x] Sidecar exposes an explicit observation/readback, report, action-draft, task proposal/change/cancellation, confirmation, resolution, and readback-alias matrix. Java-backed tools preserve the existing calls; absent Java capabilities return structured `AGENT_TOOL_UNAVAILABLE` without claiming business success.
- [x] Public NDJSON maps observation, report, action proposal/resolution, task proposal/resolution, confirmation-awaiting, failure, and abort events while retaining one sequenced terminal event per run.
- [x] Active cancellation, timeout, disconnect, duplicate-run, restart/recovery, callback auth, and redaction are covered by sidecar in-process and separately launched process tests.
- [x] Java `NodePiSessionOperations` passes against a separately launched sidecar process through `tools/sidecar-java-process-smoke.mjs`; capacity and integrity remain read-only.
- [x] Java `HttpAgentEngineBridge` and `SupervisedAdminApplyCoordinator` pass against a separately launched sidecar process under the gated `IndependentSidecarProcessContractTest`.
- [x] Sidecar backup/restore paths are restricted to its configured backup directory and operational errors never reveal secrets, URLs, or filesystem paths.
- [x] Clean install/build/test proves no import, process, workspace, or runtime filesystem dependency on `../iot-agent-runtime`.

## Comments

- 2026-10-30：Sidecar capability negotiation now maps every locally supported Java seam: device candidate/detail/readback aliases, observation history and safety review, energy summarize/compare/report, action draft read/confirm/resolve plus durable state/summary readback, task list/plan/detail, and task change/cancellation proposal plus confirmation. Context, source, tenant, authorization, and terminal confirmation state remain bridge-owned.
- 2026-10-30：Residual external-only gates are explicit and fail-closed: video playback/customer production video payloads (`prepare_video_playback`), company night closure (`propose_company_night_closure`, `continue_company_night_closure`), and task resolution tools without a corresponding Java facade (`resolve_task`, `resolve_task_change`, `resolve_task_cancellation`) remain `AGENT_TOOL_UNAVAILABLE`. Observation and energy capabilities also remain unavailable when their configured customer gateway route/adapter is absent; no route is invented.
