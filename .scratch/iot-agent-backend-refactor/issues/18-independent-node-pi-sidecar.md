# 18: Independent Node Pi Agent Sidecar

**What to build:** Java backend can run the retained `pi-agent-core` capability through a separately buildable, privately authenticated Node sidecar that does not import, start, or read `../iot-agent-runtime` at runtime.

**Blocked by:** [Java定义业务、Node通用执行](../../agent-business-recovery/issues/01-java-business-generic-engine.md)

**Status:** ready-for-agent

**业务归属验收：重新打开。** 已有独立Pi进程、私有认证与Session成果可复用；工具清单/schema、业务指引和公开业务事件留在Node不符合客户维护要求。实际修改统一由[通用引擎切换票](../../agent-business-recovery/issues/01-java-business-generic-engine.md)承接，不重复实施。

- [ ] 工具契约、业务上下文与业务事件投影归Java，Node没有物联工具名/设备schema/业务状态分支。
- [ ] 真实Java业务扩展在Node源码及构建产物不变时从小程序完成调用。

以下勾选是历史工程证据，保留但不代表新职责边界已经实现。

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
- 2026-11-02：按[ADR0004](../../../docs/adr/0004-java-business-definition-generic-agent-engine.md)重开职责边界验收；未导入原工作区未提交代码，未运行本分支业务验证。
