# 04: Conversation 与 Pi Agent 生产闭环

**What to build:** 小程序通过 Java 主服务完成可恢复的 Conversation，并由独立 Node `pi-agent-core` sidecar 执行模型和工具循环，事件与事实均保持 Runtime 语义；原 `iot-agent-runtime` 只作行为参考，不作为运行时依赖。

**Blocked by:** 02: Runtime 身份核验与稳定 Principal；03: MySQL / Redis 业务事实基础设施

**Status:** implemented-local-process-verified

- [x] 创建、读取 current、发送消息、清理、丢弃最新 turn 和取消接口与 Runtime 兼容。
- [x] Java Conversation 与事件事实落 MySQL，按 sequence 重放且幂等。
- [x] Java → Node 使用内部 bridge token 和 NDJSON，Node 不对公网开放。
- [x] 独立 Node sidecar 提供同一 `/run`、`/cancel`、工具 callback 和 Pi Session contract，不依赖 `../iot-agent-runtime`。
- [x] Java business Tool Bridge from the real Agent loop returns successful tool results for the active run.
- [x] 断开 HTTP 传输不取消后台运行；显式 cancel 才产生 `run.aborted`。
- [x] 使用原小程序请求方式完成 Java HTTP/NDJSON smoke，不修改小程序。

## Comments

- 2026-10-30：`agent-sidecar/` 已独立 `npm ci`、Node 22 build/test；本地 Java 3204 → sidecar 3221 的真实模型问候通过，返回 `run.started`、`message.*`、`run.completed`；工具 callback 通过独立 fake Java bridge contract test。客户 gateway 未配置时工具读状态按设计 503 fail-closed，不冒充客户业务成功。
