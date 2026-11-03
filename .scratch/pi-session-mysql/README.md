# Pi Session MySQL：探索结论与执行入口

Date: 2026-11-02
Status: implementation-in-progress
Implementation: direct-cutover-no-import

方向：Java/MySQL 作为唯一持久化权威；Node 保留 Pi 0.84.4 通用执行和薄存储适配，不依赖本地会话库。用户已取消旧会话导入；从新会话开始，保留原有业务事实。未取得真实界面证据的项仍未验收。

入口：[探索收敛记录](exploration.md) · [规格](spec.md) · [预期效果与验收](acceptance.md) · [ADR0005](../../docs/adr/0005-java-owned-mysql-pi-session.md) · [社区研究](../agent-business-recovery/research-pi-mysql-session.md)。

## 探索收敛

- 已确认：现有 Pi SessionRepo/SessionStorage 可替换；官方提供 session/testing 一致性测试；SQLite 不是 Pi core 的硬依赖。
- 原管理入口依赖 Node SQLite；本轮一并切除，替换为 Java/MySQL 只读健康状态。
- 已确认：社区可参考 PostgreSQL 实现，但版本并非可直接替代本项目0.84.4；不引入数据库或引擎升级。
- 已选：不导入旧会话，停旧实例后直接切换；旧公开会话一次性结束，业务事实不删除；原 Runtime 不动。
- 已选：持久化检查点确认后才成功；失去所有权不可继续写；未知副作用不自动重放。
- 已选：MySQL 短事务/版本化写入，Node 不持有数据库凭据；私有协议不暴露 SQL。
- 首票先完成真实完整纵向契约路径，用实际协议与MySQL验证可行性；若官方契约不能满足，公开阻塞后续票，不先铺全套未验证模块。

## 票据及唯一完成结果

|票|真实阻塞|可展示结果|
|---|---|---|
|[01 新会话与完整Pi仓储契约](issues/01-new-session-mysql-contract.md)|无|实际Node→Java→MySQL创建、写入、重开并构造相同上下文；官方契约通过|
|[02 在途提交与中断恢复](issues/02-run-checkpoints-recovery.md)|01|应答丢失/旧writer/中断不会重复或覆盖事实，成功终态确实已落盘|
|[03 旧会话导入](issues/03-existing-session-import.md)|已取消|用户明确不导入，不实现、不计完成|
|[04 新会话直接切换](issues/04-existing-conversation-cutover.md)|02|新会话走MySQL，替换Node后仍继续；旧会话明确结束，不伪装继承上下文|
|[05 运维与无本地盘交付](issues/05-operations-sqlite-retirement.md)|04|管理面与备份恢复只依赖Java/MySQL，运行包没有SQLite依赖/旧入口|
|[06 开发者工具验收及最终审查](issues/06-devtools-final-acceptance.md)|05|统一构建真实UI恢复路径、截图及一次最终审查收口|

01/02形成唯一会话协议，04/05在同一隔离集成分支完成后切换用户构建。03取消，不再阻塞04。没有子代理或中途审查；工程结果与真实业务验收分开记录。

## 预期终点

客户维护一套MySQL会话与业务持久化；Node升级/重启不需要搬本地会话文件；新会话可跨重启继续，草案、任务提案和报告引用不丢、不串来源；数据库故障不伪装成功。Node仍需通用Pi依赖维护，业务迭代留Java。

不自动关闭或改写原业务恢复票据；本特性结果提供给原最终集成验收。新迁移样本可复用进约20条代表业务路径，不额外要求149条全部通过。
