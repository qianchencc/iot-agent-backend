# 01: 新会话经 Java/MySQL 保存并完整重开

**What to build:** 从真实新对话到 Pi 仓储、私有协议、Java/MySQL 再到下一轮上下文，完成一个可运行闭环，并实现固定 Pi 0.84.4 的完整公开存储契约；不是只建表或只做转发。

**Blocked by:** None (can start immediately)

**Status:** in-progress

**Execution:** implemented; MySQL官方契约30/30、U01/U03真实查询与只读新Node恢复有截图；等待最终集成验收与审查，不以工程绿灯闭票。

规格：[统一 MySQL 会话持久化](../spec.md)。验收：[预期效果与验收](../acceptance.md)。工程通过不等于业务通过；不修改或关闭原业务恢复父票。

## Acceptance criteria

- [ ] Node 实现 SessionRepo / SessionStorage 薄适配，Java 隐藏持久化实现；使用真实独立 MySQL，不依赖 SQLite 或伪造内存后端换绿。
- [ ] 固定版本未修改的官方 createSessionBackendConformance 经过实际 Node→Java→MySQL 全部通过；覆盖分支、压缩、lane、共享序号、日志、fork、用量与不可变读取，不以当前只调用 appendMessage 缩减接口。
- [ ] 新会话真实设备查询后，以新 Node 进程重新打开同一会话，能继续引用原上下文查询当前状态；保存真实界面证据。
- [ ] 所有会话访问绑定 Java 核验的租户、主体、source 和 conversation/run；猜测标识、伪造范围、跨来源、无内部认证均不可读写，且不泄露他人会话存在性。
- [ ] 不强制 UUID，不大小写折叠或静默截断 Pi 标识；规范载荷和顺序保真，Pi 表不与业务 tasks/事件表混用。
- [ ] Node 不持有 MySQL 凭据；私有协议只允许受限会话操作，不接受 SQL、文件路径或代码执行。
- [ ] 记录各 SDK 语义到公共协议操作的完整映射与失败语义；不能满足官方契约时停止依赖票，不先扩散未验证实现。

## 2026-11-03进展

`pisession`公开入口隐藏Jdbc实现；Node用公开Pi JsonlSessionRepo与远程虚拟文件系统。运行实例已无SQLite；Node启动环境白名单不含DB/Redis/Admin凭据。真实证据见[验收账本](../acceptance.md)，失败与限制同页公开。

## Execution contract

只在既有隔离业务 worktree 推进，沿公开入口做红→绿行为回归和真实进程验烟；不修改原 Runtime，不向生产写控制、任务、通知或配置。未完成的检查保持未验收，不能写假回退或占位实现。每个闭环保留对应实现、回归、说明与语义提交；最终才做一次审查。不启动子代理。
