# 03: 旧 SQLite 会话保真导入且不重复业务事实

**What changed:** 用户明确要求“不必导入旧会话，直接切换即可”。本票取消，不实现、不验收 SQLite 导入。

**Blocked by:** 无

**Status:** wontfix

**Execution:** cancelled-by-user

规格：[统一 MySQL 会话持久化](../spec.md)。验收：[预期效果与验收](../acceptance.md)。工程通过不等于业务通过；不修改或关闭原业务恢复父票。

## Scope decision

- 不读取、导入或修改原 Runtime 的任何会话数据。
- 不导入旧 sidecar 的 SQLite；原文件保留为离线遗留数据，不成为运行依赖。
- 旧公开会话仅标记结束；既有草案、任务、报告及执行事实不删除、不重建。
- [04](04-existing-conversation-cutover.md) 只依赖02，切换后创建新会话；新 MySQL 会话必须在 Node/Java 重启后继续。

## Execution contract

只在既有隔离业务 worktree 推进，沿公开入口做红→绿行为回归和真实进程验烟；不修改原 Runtime，不向生产写控制、任务、通知或配置。未完成的检查保持未验收，不能写假回退或占位实现。每个闭环保留对应实现、回归、说明与语义提交；最终才做一次审查。不启动子代理。
