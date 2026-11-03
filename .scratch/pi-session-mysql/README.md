# Pi Session MySQL：探索结论与执行入口

Date: 2026-11-03
Status: implemented; representative-sample-accepted; remaining-validation-open
Implementation: direct-cutover-no-import

方向：Java/MySQL 作为唯一持久化权威；Node 保留 Pi 0.84.4 通用执行和薄存储适配，不依赖本地会话库。用户已取消旧会话导入；从新会话开始，保留原有业务事实。未取得真实界面证据的项仍未验收。

入口：[规格](spec.md) · [真实验收账本](acceptance.md) · [最终一次审查](final-review.md) · [当前测试入口](../agent-business-recovery/local-preview.md) · [构建记录](evidence/final-validation-20261103.json)。原始依据：[探索记录](exploration.md) · [ADR0005](../../docs/adr/0005-java-owned-mysql-pi-session.md) · [社区研究](../agent-business-recovery/research-pi-mysql-session.md)。

## 探索收敛

- 已确认：现有 Pi SessionRepo/SessionStorage 可替换；官方提供 session/testing 一致性测试；SQLite 不是 Pi core 的硬依赖。
- 原管理入口依赖 Node SQLite；本轮一并切除，替换为 Java/MySQL 只读健康状态。
- 已确认：社区可参考 PostgreSQL 实现，但版本并非可直接替代本项目0.84.4；不引入数据库或引擎升级。
- 已选：不导入旧会话，停旧实例后直接切换；旧公开会话一次性结束，业务事实不删除；原 Runtime 不动。
- 已选：持久化检查点确认后才成功；失去所有权不可继续写；未知副作用不自动重放。
- 已选：MySQL 短事务/版本化写入，Node 不持有数据库凭据；私有协议不暴露 SQL。
- 首票先完成真实完整纵向契约路径，用实际协议与MySQL验证可行性；若官方契约不能满足，公开阻塞后续票，不先铺全套未验证模块。

## 票据及唯一完成结果

|票|当前状态|结果 / 剩余验收|
|---|---|---|
|[01 新会话与完整Pi仓储契约](issues/01-new-session-mysql-contract.md)|done|真实新会话、追问、新Node恢复及最终官方契约30/30分别留档|
|[02 在途提交与中断恢复](issues/02-run-checkpoints-recovery.md)|implemented / validation open|已中断/停止Redis/替换Node并真实追问；三个精确崩溃点的完整前端矩阵未齐|
|[03 旧会话导入](issues/03-existing-session-import.md)|cancelled|用户明确不导入，不实现、不计完成|
|[04 新会话直接切换](issues/04-existing-conversation-cutover.md)|implemented / validation open|新独立空库已从真实UI形成事实；草案、确认单及Node恢复有截图，最终同版集成未齐|
|[05 运维与无本地盘交付](issues/05-operations-sqlite-retirement.md)|implemented / validation open|无本地卷Node已真实恢复；O01/O02为v17证据，v24同版管理面/恢复未重演|
|[06 开发者工具验收及最终审查](issues/06-devtools-final-acceptance.md)|sample accepted / final replay open|F01–F21真实样本与唯一内联审查完成；最终13215窗口蓝牙弹层遮挡F22，不计通过|

源码、票据和证据已合入`iot-agent-backend`的`main`，原mysql-final工作树已删除；本轮私有归档和两个临时清理checkpoint依用户要求删除。13215/13235冻结服务从主worktree的`var/mysql-final-preview/`运行，独立MySQL库和Redis DB2不变，不覆盖主线3217/3237或根配置。21条仍为Java20–23迭代证据，不改成主线最新构建全量通过。未齐验收保留，无子代理。

## 预期终点

客户维护一套MySQL会话与业务持久化；Node升级/重启不需要搬本地会话文件；新会话可跨重启继续，草案、任务提案和报告引用不丢、不串来源；数据库故障不伪装成功。Node仍需通用Pi依赖维护，业务迭代留Java。

不自动关闭或改写原业务恢复票据；本特性结果提供给原最终集成验收。新迁移样本可复用进约20条代表业务路径，不额外要求149条全部通过。
