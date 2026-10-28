# 04: Conversation 流式闭环

**What to build:** 小程序可以通过 Java 主服务创建、恢复、清理和发送 Agent 会话，得到与原 Runtime 相同的 NDJSON 流式事件和恢复行为。

**Blocked by:** 01: Agent Engine bridge 探针；02: Java 外部契约与认证边界；03: 业务事实仓储边界

**Status:** implemented locally (Java/NDJSON boundary; local verification passed)

- [x] 创建会话、读取 current、清理会话和丢弃最新 turn 的外部接口兼容。
- [x] 普通消息经 Agent Engine bridge 处理，返回 `message.delta`、`message.completed` 和运行终态。
- [x] `sequence` 从 1 开始单调递增，落库恢复顺序与实时顺序一致。
- [x] 断线只结束传输，不取消运行；显式 cancel 才产生 `run.aborted`。
- [x] 运行失败、超时、无进展和 sidecar 中断保持可恢复语义。
- [x] 使用现有小程序 API 调用方式进行 HTTP/NDJSON smoke 验证，无需修改小程序。

## Implementation notes

- Java exposes the existing `/api/agent/conversations` lifecycle and message/cancel paths. `ConversationFacade` owns local event sequencing, idempotency replay, disconnect semantics, and explicit cancellation.
- `HttpAgentEngineBridge` is an internal HTTP/NDJSON adapter to the Node `pi-agent-core` engine. Public event fields are filtered so engine sequence/identity fields cannot overwrite Java's per-turn sequence.
- Conversation/event stores and bridge configuration fail closed until durable adapters and the private sidecar URL are configured. The local bridge timeout is bounded by `agent.bridge.run-timeout-ms`.
- Contract verification: `mvn test` passed with 27 tests. No cloud connection or deployment was used.
