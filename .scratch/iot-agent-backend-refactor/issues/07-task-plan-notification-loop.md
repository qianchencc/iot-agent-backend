# 07: Task、Plan 与 Notification 闭环

**What to build:** 小程序可以创建、确认、修改、取消和查看一次性任务及周期计划，后台按时执行并生成通知，且重启和重复请求不造成重复执行。

**Blocked by:** 04: Conversation 流式闭环；05: IoT 身份与 Authorized Device Scope；06: Action Draft 确认闭环

**Status:** implemented-locally (durable adapters and customer scheduler pending)

- [~] 已添加任务提案/确认/修改/取消/丢弃、计划管理、任务详情、时间轴/日历与通知的公共入口和内部 Controller；实际 HTTP payload 仍由 Runtime/OpenAPI 对照适配器补齐。
- [~] 已添加 Asia/Shanghai recurrence、阶段版本 CAS、scanner recovery 与终态通知幂等 seam；没有实现伪生产 scheduler 或进程内事实存储。
- [x] TaskPayloadSafety 在保存、读模型和通知投影中递归移除 credential/password/secret/token/authorization/privateKey 字段。
- [~] 已读、区域水位、当前授权裁剪由 TaskProjectionStore/TaskNotificationStore 注入边界保留，尚无客户目录适配器。
- [~] 尚未添加跨 MySQL/网关的集成回归；本地 `mvn test` 已通过 27 tests，真实 scheduler/gateway 仍待客户协议。

Implementation notes:

- `TaskFacade` 以 proposal/plan/task version CAS 和 tenant+ownerKey 隔离重复请求；重复确认按 `task:<proposalId>` 回放，取消只对非终态任务执行 CAS。
- `TaskScanner` 先 claim 阶段再调用注入的 `TaskExecutionGateway`，执行异常收口为 `INTERRUPTED/unknown`；`recover()` 只回收残留 executing，不补跑。
- `TaskConfiguration` 为 Task/Plan/Notification/Phase、执行网关、scheduler 和投影提供显式 fail-closed beans；缺少真实适配器时不会启动本地替代执行。

Unresolved prerequisites: MySQL-backed implementations of all fact/phase/projection/notification stores, a confirmed customer control/execution gateway, a durable scheduler/lock provider, and the exact Runtime task proposal/event payload schema. No cloud, deployment, restart, commit, or real-device call was performed.
