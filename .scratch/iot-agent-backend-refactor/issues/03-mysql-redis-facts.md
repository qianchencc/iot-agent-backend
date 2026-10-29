# 03: MySQL / Redis 业务事实基础设施

**What to build:** Java 主服务把需要重启、断线恢复和重试后仍可信的业务事实持久化到 MySQL，把缓存、锁、幂等和限流放到 Redis，并在依赖缺失时安全失败。

**Blocked by:** 01: 本地运行基线与安全只读探针

**Status:** ready-for-agent

- [ ] 本地 MySQL 连接、迁移和连接健康检查可重复启动。
- [ ] 会话、NDJSON 事件、Action Draft、Task、Plan、Phase、Notification、Report、Audit 和配置修订有业务事实仓储实现。
- [ ] 逐事件追加、sequence 重放、终态恢复、唯一性和 CAS 语义在 MySQL 中保持。
- [ ] Redis 实现可重建缓存、分布式锁、幂等键、限流和运行期协调。
- [ ] Redis 故障不会丢失 MySQL 业务事实；缓存失效进入可观测受控降级。
- [ ] 不保留进程内事实存储或假成功 fallback。
- [ ] 表、字段、decimal、唯一索引、时间字段和无外键/级联约束符合客户技术规范。

## Comments

- 2026-10-29：本地 MySQL/Redis Compose 已健康；当前 Java 仍使用 fail-closed 事实仓储，待完成真实实现。
