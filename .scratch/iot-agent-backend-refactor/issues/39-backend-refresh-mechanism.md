# 39: Backend 最新快照刷新机制

**Type:** grilling
**Status:** resolved
**Blocked by:** 33

## Question

用户确认 backend 约 5 秒可以获取一次最新进度，但尚未确定机制。冻结以下选择：

- 定时刷新：backend 按业务能力/来源主动更新最新快照，用户请求只读 backend 快照；
- 请求触发最短间隔：第一次请求触发上游读取，5 秒内请求复用在途/最近结果；
- 组合：关键当前数据定时刷新，冷门查询按请求触发并受 5 秒最短刷新间隔限制。

## Answer

采用**组合策略**：高频核心当前数据按业务能力定时刷新；低频或带查询条件的读取按请求触发，并在约 5 秒内合并在途/复用最近结果。具体能力归类由业务缓存矩阵票据继续决定。

这条决策明确区分：

- backend 5 秒级更新/复用策略；
- frontend 约 1 分钟 UI 复用策略；
- 用户手动刷新只绕过 frontend cache，不强制 backend 访问 upstream。

后续实现仍需定义刷新任务粒度、失败时旧值保留、并发合并、快照存储和指标。

## Evidence to use

- `issues/33-business-capability-cache-matrix.md`
- `issues/34-frontend-backend-cache-ownership.md`
- Runtime/MP 现有 read cache、in-flight merge、energy sync 记录
