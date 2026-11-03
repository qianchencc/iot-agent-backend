# 22: Sidecar Event, Tool, and Process Parity

**What to build:** Complete the independent sidecar contract beyond the core model/session bridge so it can replace Runtime's agent execution boundary without importing or starting Runtime.

**Blocked by:** [Java定义业务、Node通用执行](../../agent-business-recovery/issues/01-java-business-generic-engine.md)

**Status:** ready-for-agent

**业务契约验收：重新打开。** 原“在sidecar维护业务union和别名矩阵”是历史方案，当前按[ADR0004](../../../docs/adr/0004-java-business-definition-generic-agent-engine.md)由Java定义业务和公开投影，Node只承载通用执行事件。修改由[通用引擎切换票](../../agent-business-recovery/issues/01-java-business-generic-engine.md)统一承接。

- [ ] 取消/超时/恢复/Session等通用机制保留，业务工具目录、参数、来源过滤和卡片事件不在Node硬编码。
- [ ] 原小程序能消费Java投影的真实工具结果，不出现空草案、缺失任务卡或失败伪成功事件。

以下勾选与Comments仅为历史工程记录；旧unavailable列表须按实际源码核对，不能照单宣称本次实现状态。

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
- 2026-11-02：按用户要求重新打开业务职责和前端契约验收，不将历史进程测试或工具数量视作说明书通过。
