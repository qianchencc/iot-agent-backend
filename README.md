# iot-agent-backend

`../iot-agent-runtime`（Fastify/TypeScript）的 Java 版实现：Spring Boot 3.5 / Java 21 / Maven，全本地开发。HTTP 契约对齐 runtime 的 `docs/api/`（openapi + NDJSON 事件协议）。

架构约定、模块边界纪律与 runtime→Java 模块映射见 [AGENTS.md](./AGENTS.md)；issue tracker 与领域文档规则见 `docs/agents/`。

```sh
mvn test                 # 单元测试 + ArchUnit 模块边界
mvn spring-boot:run      # 127.0.0.1:3200
curl -s http://127.0.0.1:3200/healthz
```

`java` 不在 PATH：直接运行 jar 用 `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java`。
