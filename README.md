# iot-agent-backend

Java public backend plus an independent Node `agent-sidecar`: Spring Boot 3.5 / Java 21+ and Node 22. The Java service owns the customer-facing HTTP contract, identity boundary, MySQL/Redis business facts, and safety policy. The sidecar owns `pi-agent-core`, `pi-ai`, and Pi Session SQLite behind a private bridge. `../iot-agent-runtime` is behavior evidence only; it is not imported, launched, or read at runtime.

架构约定、模块边界纪律与 Runtime→Java 模块映射见 [AGENTS.md](./AGENTS.md)；issue tracker 与领域文档规则见 `docs/agents/`。

Java 构建、测试、启动和本地 healthz 验证命令见 [AGENTS.md](./AGENTS.md)。独立 sidecar 的安装、构建、启动和私有 HTTP contract 见 [agent-sidecar/README.md](./agent-sidecar/README.md)。

`java` 不在 PATH：直接运行 jar 用 `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java`。
