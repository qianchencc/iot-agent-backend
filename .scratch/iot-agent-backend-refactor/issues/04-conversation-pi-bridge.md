# 04: Conversation 与 Pi Agent 生产闭环

**What to build:** 小程序通过 Java 主服务完成可恢复的 Conversation，并由真实 Node `pi-agent-core` sidecar 执行模型和工具循环，事件与事实均保持 Runtime 语义。

**Blocked by:** 02: Runtime 身份核验与稳定 Principal；03: MySQL / Redis 业务事实基础设施

**Status:** ready-for-agent

- [ ] 创建、读取 current、发送消息、清理、丢弃最新 turn 和取消接口与 Runtime 兼容。
- [ ] Java Conversation 与事件事实落 MySQL，按 sequence 重放且幂等。
- [ ] Java → Node 使用内部 bridge token 和 NDJSON，Node 不对公网开放。
- [ ] 真实 `pi-agent-core` / `pi-ai` 模型运行返回 `message.delta`、`message.completed` 和终态。
- [ ] Tool Bridge 支持 runId、conversationId、turnId、取消、超时、重复请求、断线和 sidecar 重启。
- [ ] 断开 HTTP 传输不取消后台运行；显式 cancel 才产生 `run.aborted`。
- [ ] 使用原小程序请求方式完成 Java HTTP/NDJSON smoke，不修改小程序。

## Comments

- 当前 Java/Node 探针已验证 echo tool 闭环；本票将其扩展为 MySQL-backed Conversation 和生产业务 Tool Bridge 基础。
