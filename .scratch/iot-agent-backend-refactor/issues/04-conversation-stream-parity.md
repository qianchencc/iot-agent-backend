# 04: Conversation 流式闭环

**What to build:** 小程序可以通过 Java 主服务创建、恢复、清理和发送 Agent 会话，得到与原 Runtime 相同的 NDJSON 流式事件和恢复行为。

**Blocked by:** 01: Agent Engine bridge 探针；02: Java 外部契约与认证边界；03: 业务事实仓储边界

**Status:** ready-for-agent

- [ ] 创建会话、读取 current、清理会话和丢弃最新 turn 的外部接口兼容。
- [ ] 普通消息经真实 `pi-agent-core` 处理，返回 `message.delta`、`message.completed` 和运行终态。
- [ ] `sequence` 从 1 开始单调递增，落库恢复顺序与实时顺序一致。
- [ ] 断线只结束传输，不取消运行；显式 cancel 才产生 `run.aborted`。
- [ ] 运行失败、超时、无进展和 sidecar 中断保持可恢复语义。
- [ ] 使用现有小程序 API 调用方式进行 HTTP/NDJSON smoke 验证，无需修改小程序。
