# iot-agent-backend

`../iot-agent-runtime`（Fastify/TypeScript）的 Java 版实现：Spring Boot 3.5 / Java 21 / Maven，全本地开发。HTTP 契约对齐 runtime 的 `docs/api/`（openapi + NDJSON 事件协议）。

架构约定、模块边界纪律与 runtime→Java 模块映射见 [AGENTS.md](./AGENTS.md)；issue tracker 与领域文档规则见 `docs/agents/`。

构建、测试、启动和本地 healthz 验证命令见 [AGENTS.md](./AGENTS.md)；本地服务端口为 `127.0.0.1:3200`。

`java` 不在 PATH：直接运行 jar 用 `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java`。
