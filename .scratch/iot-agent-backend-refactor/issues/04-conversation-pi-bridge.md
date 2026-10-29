# 04: Conversation 与 Pi Agent 生产闭环

**What to build:** 小程序通过 Java 主服务完成可恢复的 Conversation，并由真实 Node `pi-agent-core` sidecar 执行模型和工具循环，事件与事实均保持 Runtime 语义。

**Blocked by:** 02: Runtime 身份核验与稳定 Principal；03: MySQL / Redis 业务事实基础设施

**Status:** implemented-locally (Java/MySQL/Node real-model loop verified; business tool migration remains in tickets 05–08)

- [x] 创建、读取 current、发送消息、清理、丢弃最新 turn 和取消接口与 Runtime 兼容。
- [x] Java Conversation 与事件事实落 MySQL，按 sequence 重放且幂等。
- [x] Java → Node 使用内部 bridge token 和 NDJSON，Node 不对公网开放。
- [x] 真实 `pi-agent-core` / `pi-ai` 模型运行返回 `message.delta`、`message.completed` 和终态。
- [x] Tool Bridge seam 支持 runId、conversationId、turnId、取消、超时、重复请求、断线和 sidecar 重启探针。
- [x] 断开 HTTP 传输不取消后台运行；显式 cancel 才产生 `run.aborted`。
- [x] 使用原小程序请求方式完成 Java HTTP/NDJSON smoke，不修改小程序。

## Comments

- 2026-10-29：Conversation/turn/event now use MySQL adapters; authenticated Java→Node bridge uses NDJSON and bearer bridge token; real local `pi-agent-core` + `pi-ai` run returned `message.delta`, `message.completed`, `run.completed`. Sidecar rejects unauthenticated `/run` with 401. Remaining work is Java business Tool Bridge capability migration.
