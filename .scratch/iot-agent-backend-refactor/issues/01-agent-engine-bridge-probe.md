# 01: Agent Engine bridge 探针

**What to build:** 一个可真实启动的 Java MVC → Node `pi-agent-core` → Java fake tool bridge → NDJSON 闭环，用来验证组合架构而不是模拟接口。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] Java 与 Node sidecar 可独立启动并通过受控内网 HTTP/NDJSON 通信。
- [x] Node 使用真实 `pi-agent-core` / `pi-ai`，不以 Java 重写 Agent loop。
- [x] Fake tool 的调用、结果、普通文本增量和运行终态可被观察。
- [x] 支持取消、超时、重复 run、断线和 sidecar 重启场景。
- [x] 探针不访问真实客户网关、不控制真实设备、不进入生产路由。
- [x] 运行命令和观察到的事件序列记录在票据评论中。

## Comments

- 2026-10-29：完成 throwaway probe，入口位于 `.scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/`。
- `mvn -q package` 成功；Node `node --check` 成功。
- 实际 HTTP 观察到：Java healthz 200、Node healthz 200、`pi.tool_execution_start`、Java 返回的 `pi.tool_execution_end`、`message.delta`、`message.completed`、`run.completed`。
- `slow=true` 场景实际调用 `/cancel/{runId}`，返回 `aborted:true`，流以 `run.aborted` 收口。
- sidecar 停止后重新启动，Java/Node 两个 healthz 均恢复 200。
- 探针使用 faux model 产生确定性模型响应；不联网、不访问真实设备。该目录不属于生产源码。
- 2026-10-29 review remediation：补齐 `conversationId` / `turnId`、按 conversation 隔离 Pi Session、断线后的事件重放、timeout、重复 `runId` 409 和取消竞态处理。
- 复验观察到：断开 Java run 响应后，`GET /runs/{runId}/events` 仍返回完整事件并以 `run.failed` 超时终态收口；不同 conversation 的事件身份保持隔离。
