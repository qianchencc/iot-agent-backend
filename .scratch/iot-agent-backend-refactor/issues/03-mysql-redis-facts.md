# 03: MySQL / Redis 业务事实基础设施

**What to build:** Java 主服务把需要重启、断线恢复和重试后仍可信的业务事实持久化到 MySQL，把缓存、锁、幂等和限流放到 Redis，并在依赖缺失时安全失败。

**Blocked by:** 01: 本地运行基线与安全只读探针

**Status:** implemented-locally (MySQL/Redis adapters and local integration pass; Redis failure evidence and Admin configuration revisions remain in later slices)

- [x] 本地 MySQL 连接、迁移和连接健康检查可重复启动。
- [x] 会话、NDJSON 事件、Action Draft、Task、Plan、Phase、Notification、Report 和 Audit 有业务事实仓储实现。
- [x] 逐事件追加、sequence 重放、终态恢复、唯一性和 CAS 语义在 MySQL 中保持。
- [x] Redis 实现可重建缓存、分布式锁、幂等键、限流和运行期协调。
- [x] Redis 故障不会丢失 MySQL 业务事实；缓存失效进入可观测受控降级。
- [x] 不保留进程内事实存储或假成功 fallback。
- [x] 表、字段、decimal、唯一索引、时间字段和无外键/级联约束符合客户技术规范。

## Comments

- 2026-10-29：新增 conditional MySQL/Redis wiring、idempotent schema initialization、JDBC business-fact adapters and Redis coordination adapter. `StorageIntegrationTest` passed with local Compose; Java HTTP conversation create/current returned 201/200 with real identity verification; local Redis was stopped and MySQL-backed conversation creation still returned 201, then Redis was restored. Admin configuration persistence and later read projections are implemented in their owning tickets.
