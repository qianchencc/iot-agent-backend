# MySQL 结构与迁移

建表、修改 JSON 事实结构、数据迁移和恢复前读根 `docs/configuration-governance.md` 第5节，以及 `.scratch/admin-console-governance/database-inventory.md`。业务事实与 Pi Session 的权威在 Java/MySQL；Redis不是替代库。

- 同一结构的 SQL 资源、Java 初始化 DDL、兼容升级和实际旧库必须对应；`CREATE IF NOT EXISTS` 不承担已有列／索引迁移。迁移登记版本和影响，避免每次启动重复业务副作用。
- 遵守根建表规则：bigint unsigned id、create_time/update_time、decimal、无外键级联、适用时 null 逻辑删除与联合唯一键。既有自然／复合主键表的差异先记录，未经迁移方案不改在线结构。
- 业务唯一、幂等与 CAS 是数据库约束，不以应用内先查后写代替；冲突不覆盖审计原证据。JSON 中的 tenant／owner／source 必须在写入及关联读取时同样核验。
- 配置修订与任务凭据密文不回显；Pi 原生日志、用户正文和报告未获证明是应用加密数据，按敏感业务内容保护，不做任意表浏览器。
- 到期、六小时活跃窗口、逻辑删除和用户清会话不是审计保留期。保留／清理策略未定前不增加无界自动删除。
- 本地核查只读元数据及聚合数量，不导出正文、Token、密文或凭据哈希。全库一致备份包含业务、配置、Pi四表和 storage_migrations；恢复先入独立库，不覆盖运行实例。
