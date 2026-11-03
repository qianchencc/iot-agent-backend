# iot-agent-backend

Java public backend plus an independent Node `agent-sidecar`: Spring Boot 3.5 / Java 21+ and Node 22. Java owns customer HTTP, identity, business instructions/tools, device/task/report behavior, MySQL business facts **and durable Pi Sessions**. Redis is coordination/cache, not session authority. Node owns only the generic Pi loop, streaming, tool transport and interruption checkpoints; no SQLite, database credentials or local session volume. `../iot-agent-runtime` is read-only behavior evidence, never a runtime dependency.

架构约定、模块边界纪律与 Runtime→Java 模块映射见 [AGENTS.md](./AGENTS.md)；issue tracker 与领域文档规则见 `docs/agents/`。

Java 构建、测试、启动和本地 healthz 验证命令见 [AGENTS.md](./AGENTS.md)。独立 sidecar 的安装、构建、启动和私有 HTTP contract 见 [agent-sidecar/README.md](./agent-sidecar/README.md)。

`java` 不在 PATH：直接运行 jar 用 `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java`。

## MySQL Pi Session cutover

- Direct cutover, without importing old conversations. A transactional, one-time migration retires old public conversations; historical turns, drafts, tasks and reports are not deleted. Subsequent restarts preserve new MySQL conversations within the six-hour inactivity retention policy.
- Start Node through `tools/start-sidecar.py`; it strips Java/MySQL/Redis/admin credentials from the child environment. Business capability changes belong in Java, not Node.
- Administration exposes MySQL session health only. SQLite file backup/restore/capacity operations are removed. Back up the whole authoritative MySQL schema, including business facts, Pi tables and `storage_migrations`; restore into an independent database before any live cutover.
- Current scope, execution tickets and **real UI evidence / unresolved cases**: [Pi Session acceptance](.scratch/pi-session-mysql/acceptance.md). Tests and HTTP success are engineering diagnostics, never business acceptance.
