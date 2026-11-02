# Pi Session MySQL：探索结论与执行入口

Date: 2026-11-02
Status: specification-and-tickets-published
Implementation: not-started

方向：Java/MySQL 作为唯一持久化权威；Node 保留 Pi 0.84.4 通用执行和薄存储适配，不依赖 SQLite 本地盘。**这是规划交付，不是当前运行实例已完成迁移或业务已完备。**

入口：[探索收敛记录](exploration.md) · [规格](spec.md) · [预期效果与验收](acceptance.md) · [ADR0005](../../docs/adr/0005-java-owned-mysql-pi-session.md) · [社区研究](../agent-business-recovery/research-pi-mysql-session.md)。

## 探索收敛

- 已确认：现有 Pi SessionRepo/SessionStorage 可替换；官方提供 session/testing 一致性测试；SQLite 不是 Pi core 的硬依赖。
- 已确认：Java 会话管理当前仍转发 Node 的 SQLite 运维；迁移必须包括这些调用方，而非只改 appendMessage。
- 已确认：社区可参考 PostgreSQL 实现，但版本并非可直接替代本项目0.84.4；不引入数据库或引擎升级。
- 已选：旧新后端会话一致性备份并迁移，业务事实不复制；原 Runtime 不动；不能映射的活跃会话阻止切换。
- 已选：持久化检查点确认后才成功；失去所有权不可继续写；未知副作用不自动重放。
- 已选：MySQL 短事务/版本化写入，Node 不持有数据库凭据；私有协议不暴露 SQL。
- 首票先完成真实完整纵向契约路径，用实际协议与MySQL验证可行性；若官方契约不能满足，公开阻塞后续票，不先铺全套未验证模块。

## 票据及唯一完成结果

|票|真实阻塞|可展示结果|
|---|---|---|
|[01 新会话与完整Pi仓储契约](issues/01-new-session-mysql-contract.md)|无|实际Node→Java→MySQL创建、写入、重开并构造相同上下文；官方契约通过|
|[02 在途提交与中断恢复](issues/02-run-checkpoints-recovery.md)|01|应答丢失/旧writer/中断不会重复或覆盖事实，成功终态确实已落盘|
|[03 旧会话无损导入](issues/03-existing-session-import.md)|01|受控旧库备份导入后，完整上下文与归属一致，可重复校验|
|[04 现有用户会话切换](issues/04-existing-conversation-cutover.md)|02、03|原小程序会话改走MySQL，替换Node仍可继续，未确认操作不重放|
|[05 运维与无本地盘交付](issues/05-operations-sqlite-retirement.md)|04|管理面与备份恢复只依赖Java/MySQL，运行包没有SQLite依赖/旧入口|
|[06 开发者工具验收及最终审查](issues/06-devtools-final-acceptance.md)|05|统一构建真实UI恢复路径、截图及一次最终审查收口|

02、03共享01的版本化会话协议；协议/Java会话模块由01的集成负责人维护，03只读取备份并通过受控导入入口写入。05涉及部署、管理调用者和文档，04/05在同一集成分支完成后才发布用户测试构建；不承诺中间提交可单独上线。没有子代理或中途审查。

## 预期终点

客户维护一套MySQL会话与业务持久化；Node升级/重启不需要搬SQLite文件；旧对话能继续，草案、任务提案和报告引用不丢、不串来源；数据库故障不伪装成功。Node仍需通用Pi依赖维护，但业务迭代留Java。

不自动关闭或改写原业务恢复票据；本特性结果提供给原最终集成验收。新迁移样本可复用进约20条代表业务路径，不额外要求149条全部通过。
