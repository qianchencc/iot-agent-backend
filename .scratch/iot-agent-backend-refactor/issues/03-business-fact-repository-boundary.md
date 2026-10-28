# 03: 业务事实仓储边界

**What to build:** 为会话事件、草案、任务、报告和审计建立不绑定具体数据库的业务事实仓储入口，并用行为 contract tests 固定 Runtime 语义。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 会话事实、逐事件追加、sequence 重放、终态和幂等语义有可执行 contract tests。
- [x] 草案、任务、报告元数据和审计的仓储入口位于对应模块包根，具体实现隐藏在 internal 中。
- [x] Redis 只出现在可重建缓存、锁、幂等、限流和运行期协调边界。
- [x] MySQL 适配遵守客户规范：无外键/级联、必备字段、decimal、唯一索引和命名规则。
- [x] 存储配置缺失时 fail closed，不写入假数据或内存伪持久化回退。
- [x] 当前未取得真实客户连接信息时，测试使用隔离夹具，不伪称生产 MySQL/Redis 已验收。

## Comments

- 2026-10-29：新增 conversation/action/task/report/audit 事实仓储入口、MySQL schema draft 与 fail-closed `StorageSettings`。
- Contract tests 覆盖事件顺序/幂等、草案 revision、任务幂等、报告/审计事实读取和缺少存储配置。
- `mvn test`：17 tests，0 failures；未连接真实 MySQL/Redis，未部署云端生产。
