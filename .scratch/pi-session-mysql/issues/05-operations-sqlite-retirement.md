# 05: Java/MySQL 运维闭环与 SQLite 运行依赖退役

**What to build:** 客户从 Java 管理面和既有 MySQL 运维流程管理会话健康及恢复，不再被要求操作 Node 的 SQLite 路径、备份文件或 writer lease；交付可在无会话持久卷条件下运行。

**Blocked by:** [04 现有对话切至 MySQL，替换 Node 仍可继续](04-existing-conversation-cutover.md)

**Status:** in-progress

**Execution:** implemented；O01/O02有真实管理面与v17独立MySQL恢复后的业务截图；最终Node只读无本地卷并通过F15。最终Java24审计字段升级保留全部已生成事实；v24管理面/备份恢复未同版重演，不把历史证据改名闭票。

规格：[统一 MySQL 会话持久化](../spec.md)。验收：[预期效果与验收](../acceptance.md)。工程通过不等于业务通过；不修改或关闭原业务恢复父票。

## Acceptance criteria

- [ ] 管理页面展示真实 MySQL 会话健康及错误，不保留未工作的 SQLite 容量、备份、恢复、手工租约按钮；Java/Node/管理前端调用方全部迁移，旧入口不伪造成功或保留别名。
- [ ] 受控 MySQL 备份包含 Pi 上下文与相关 Java 业务事实的一致恢复点，在独立库恢复后用真实小程序继续同一业务对话；不把备份文件存在当作成功。
- [ ] Node 运行依赖和锁文件移除 SQLite backend，生产构建无 node:sqlite、SQLite 持久卷、本地文件恢复逻辑或旧库导入工具。
- [ ] 部署和本地启动对 Node 使用环境白名单，不传 MySQL/Redis 管理/管理员凭据；应用目录只读且无 SQLite 持久卷仍能完成真实对话。
- [ ] 启动、关闭和健康检查失败语义明确，数据库不可用不回退为空历史；恢复操作受管理授权和维护窗口保护。
- [ ] 按选定 Pi Session 隔离的清除/删除操作可通过正确授权完成，不影响其他用户和 Business Fact，不保留无效前端菜单或回调。
- [ ] 运维文档、环境示例、交付配置及当前架构入口一致，历史资料标明旧决策；原 SQLite 只读备份按保留策略保留，不作为在线依赖。

## 2026-11-03进展

O01管理界面显示MySQL；O02将业务+Pi+cutover marker一致备份恢复到独立`iot_agent_business_restore_v17_20261103`，启动同一v17构建，真实DevTools重开并追问26楼/27楼原报告，得到441.56/461.29度及模拟补数限制。原数据库未覆盖，演练后已切回本轮原库。截图、失败首轮与成功重验见[验收账本](../acceptance.md)；备份为忽略目录0600私有文件，不提交原始数据。

## Execution contract

只在既有隔离业务 worktree 推进，沿公开入口做红→绿行为回归和真实进程验烟；不修改原 Runtime，不向生产写控制、任务、通知或配置。未完成的检查保持未验收，不能写假回退或占位实现。每个闭环保留对应实现、回归、说明与语义提交；最终才做一次审查。不启动子代理。
