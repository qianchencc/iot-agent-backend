# 22: Sidecar Event, Tool, and Process Parity

**What to build:** Complete the independent sidecar contract beyond the core model/session bridge so it can replace Runtime's agent execution boundary without importing or starting Runtime.

**Blocked by:** 04: Conversation 与 Pi Agent 生产闭环；18: Independent Node Pi Agent Sidecar

**Status:** implemented-independent-process-contract; runtime-business-union-open

- [x] Sidecar exposes an explicit observation/readback, report, action-draft, task proposal/change/cancellation, confirmation, resolution, and readback-alias matrix. Java-backed tools preserve the existing calls; absent Java capabilities return structured `AGENT_TOOL_UNAVAILABLE` without claiming business success.
- [x] Public NDJSON maps observation, report, action proposal/resolution, task proposal/resolution, confirmation-awaiting, failure, and abort events while retaining one sequenced terminal event per run.
- [x] Active cancellation, timeout, disconnect, duplicate-run, restart/recovery, callback auth, and redaction are covered by sidecar in-process and separately launched process tests.
- [x] Java `NodePiSessionOperations` passes against a separately launched sidecar process through `tools/sidecar-java-process-smoke.mjs`; capacity and integrity remain read-only.
- [x] Java `HttpAgentEngineBridge` and `SupervisedAdminApplyCoordinator` pass against a separately launched sidecar process under the gated `IndependentSidecarProcessContractTest`.
- [x] Sidecar backup/restore paths are restricted to its configured backup directory and operational errors never reveal secrets, URLs, or filesystem paths.
- [x] Clean install/build/test proves no import, process, workspace, or runtime filesystem dependency on `../iot-agent-runtime`.

## Comments

- 2026-10-30：Sidecar now exports a deterministic capability matrix and maps the public event union. Existing Java-backed observation/report/action/task proposal calls remain unchanged; unsupported task management, confirmation, resolution, history, and detail/readback seams fail closed with `AGENT_TOOL_UNAVAILABLE`. In-process and separately launched process tests cover auth/redaction, callback forwarding, timeout/cancellation, disconnect, duplicate-run rejection, restart/recovery, SQLite close/reopen recovery, unsupported capability mapping, and proposal terminal mapping.
- Residual full-runtime union gaps: Java does not yet expose task change/cancellation, task/action confirmation/resolution, alert/history/detail readback, or broad report seams. No production cloud was touched.
