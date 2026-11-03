# Pi 会话接入 MySQL：社区实践核查

日期：2026-11-02。范围：上游及社区一手资料核查；不修改运行中的存储实现，不启动子代理，不宣称任何第三方适配器已在本项目运行通过。

## 结论

本轮没有找到可直接用于当前 Pi 0.84.4 的成熟 MySQL 会话后端。找到两个有参考价值的 PostgreSQL 实现；它们证明关系型数据库适配可行，但不能据此声称存在已经验证的 MySQL 包。MySQL 作为智能体 SQL 查询工具的数据源，不等于用 MySQL 持久化 Pi Session。

建议继续采用 Java/MySQL 统一持久化，但沿用 Pi 的会话契约，不自行缩减为前端聊天文本或任意 JSON 快照。Node 保留通用 Pi 语义及薄存储适配，Java 负责授权、事务、持久化与写入版本约束。Java 中转属于本项目维护目标下的设计选择，不是社区现成方案。

## 一手资料与版本限制

1. **本项目所用版本的官方说明**：Pi 0.84.4 README 明确把 SQLite 后端拆成独立包，core 默认不引入 SQLite；允许其他后端作为独立包。该版本 `packages/session-backends` 实际仅列出 `sqlite-node`，没有官方 MySQL 后端。
   - https://github.com/earendil-works/pi/blob/v0.84.4/packages/agent/README.md
   - https://api.github.com/repos/earendil-works/pi/contents/packages/session-backends?ref=v0.84.4
2. **PiCloud PostgreSQL 实践**：ADR-0105 记录其针对 Pi 0.84.1 实现 `SessionStorage` / `SessionRepo`，运行上游 `createSessionBackendConformance`，保留 opaque-string IDs、Entry/Record 共同标识空间、fork/log/statistics/lanes 与不可变读取语义。该项目的生产使用属于作者陈述，本轮未独立运行验证。
   - https://github.com/cookerpapa/pi-cloud/blob/main/docs/adr/0105-pi-session-backend-conformance.md
   - https://github.com/cookerpapa/pi-cloud/tree/main/packages/pi-session-postgres
   - **核实当前代码后的限制**：其 main 分支 package.json 已使用 `pi-durable` / `chord` 1.0.0，不能把历史 0.84.1 ADR 当作当前源码仍可直接接入本项目 0.84.4 的证据。
   - https://github.com/cookerpapa/pi-cloud/blob/main/packages/pi-session-postgres/package.json
3. **独立 PostgreSQL SDK 包**：`@netzlabor/pi-durable-postgres`，作者标为 Alpha；README 声明运行上游存储一致性测试。main 的 package.json 为 0.2.0，依赖 `pi-durable` / `chord` >=1.1.0 <2；README 兼容表仍记录早期 0.1.x，选用必须固定实际版本重新验证。它不是当前 `pi-agent-core` 0.84.4 的直接替代。
   - https://github.com/j-koester/pi-durable-postgres
   - https://github.com/j-koester/pi-durable-postgres/blob/main/package.json
   - https://github.com/j-koester/pi-durable-postgres/blob/main/docs/adapters.md
   - 其文档明确：PostgreSQL 不自动使 Harness 支持多写者；使用独占 advisory lock，连接失效后不能复用陈旧内存状态；事务提交结果不确定时不透明重试。说明换数据库不能代替正确的所有权和恢复设计。
4. **上游异步存储背景**：旧 SessionManager 的同步文件 IO 曾阻碍远程数据库接入；维护者说明为 server mode 重构。此历史讨论不是当前 0.84.4 无异步接口的证据。
   - https://github.com/earendil-works/pi/issues/2616

## 建议实施约束

- 固定当前 Pi 0.84.4，先验证它的公开 SessionRepo/SessionStorage 及上游一致性测试；不把本次去 SQLite 与升级 Pi Durable 1.x 绑在一起。
- 在 Node 的 Pi 存储适配位置接入 Java 私有会话模块；Java/MySQL 作为唯一持久化权威。Pi 上下文构建和压缩算法仍由通用引擎负责，不在 Java 重写 agent loop。
- 远程协议应保留有序写入、原子批次、稳定条目标识、版本冲突和不确定提交语义；不逐个 SQL 调用跨 HTTP，不只在整轮末尾保存最终文本。
- 运行上游未改动的一致性测试，且测试路径必须覆盖实际 Node → Java → MySQL，而非仅验证两端 mock；再验证重启、并发冲突、工具调用/结果配对及压缩历史恢复。
- MySQL 使用独立的 Pi 会话表/命名空间，不与现有业务 tasks 表或前端事件表混用。
- 不照搬社区 PostgreSQL advisory lock、SQL 方言或 Kafka 控制面；本项目使用 MySQL 事务与版本化写入约束实现对应语义。
- 旧 SQLite 先只读备份、导入并校验；正式切换后不双写，不保留自动回退，不通过删除旧文件宣称迁移成功。

## 未验证

未安装或运行上述社区适配器；未完成 MySQL 实现或导入验证；未证明当前运行中的 SQLite 租约故障已永久解决。本轮资料只支持设计决策，不构成业务验收。
