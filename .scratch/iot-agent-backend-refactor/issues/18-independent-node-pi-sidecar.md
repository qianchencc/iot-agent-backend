# 18: Independent Node Pi Agent Sidecar

**What to build:** Java backend can run the retained `pi-agent-core` capability through a separately buildable, privately authenticated Node sidecar that does not import, start, or read `../iot-agent-runtime` at runtime.

**Blocked by:** 01: Local Runtime Foundation；03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环；09: Admin、模型配置与运维边界

**Status:** implemented-local-process-verified

- [x] Sidecar has its own Node 22 package, lockfile, build/start commands and deployment contract; a clean install/build does not depend on the Runtime repository.
- [x] Sidecar owns `pi-agent-core` / `pi-ai` execution and Pi Session SQLite; Java MySQL facts remain Java-owned.
- [x] Authenticated private `/run` and `/cancel` endpoints return the Java-supported public NDJSON event union and preserve timeout/cancel semantics.
- [x] Authenticated sidecar-to-Java tool callback calls the Java tool bridge and rejects missing/invalid bridge identity.
- [x] Authenticated Pi Session operations provide capacity, integrity/quick-check, writer-lease recovery, backup and restore with redacted responses.
- [x] Authenticated supervised apply handoff provides `{accepted:true}` only after the sidecar/supervisor contract accepts the restart handoff; no fake success.
- [x] Sidecar contract tests cover normal, malformed, unauthorized, callback, recovery and redaction behavior without customer gateway or production Runtime; live model and Java process smoke are separate evidence.
- [x] Backend `HttpAgentEngineBridge`, `NodePiSessionOperations` and `SupervisedAdminApplyCoordinator` use the independent sidecar contract; Java→sidecar model and Pi Session adapter smoke passed locally.

## Comments

- `../iot-agent-runtime` is behavior oracle and historical evidence only. It is not an allowed runtime dependency or substitute implementation.
- Published `pi-agent-core`, `pi-ai` and Pi Session SQLite packages are allowed dependencies; source-level imports from the Runtime repository are not.
- 2026-10-30：独立 sidecar 已通过 Node 22 clean install/build/test、真实模型问候、真实 Java `NodePiSessionOperations` capacity/integrity smoke，以及真实客户只读设备目录/状态工具链路。Java adapter 已统一使用 `Authorization: Bearer`。
- Remaining full Runtime event/tool union, active timeout/cancel/disconnect/restart process tests and complete adapter matrix are ticket 22; production supervisor, persistent-volume permissions and real cutover remain external acceptance.
- Ticket 17 is blocked by this ticket and tickets 19–22 in addition to tickets 11–16.
