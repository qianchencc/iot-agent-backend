# 37: 缓存存储、Redis 协调与 MySQL 事实分工

**Type:** grilling
**Status:** resolved
**Blocked by:** 32, 34, 35, 36

## Question

在缓存模板和能力矩阵冻结后，决定每类内容使用 frontend memory、backend memory、Redis、MySQL 派生快照还是 Business Fact；定义 Redis key/namespace/身份摘要/TTL/容量/指标、跨实例 lease、Redis 故障降级，以及哪些内容绝不能进入 Redis。

## Answer

- Frontend：继续使用现有同身份内存读快照，只有显式只读路径接入。
- Backend：先使用简单的进程内最新结果/在途合并解决实际重复读取；只有实际出现跨实例协调或 Redis 已有业务需求时再接 Redis。
- Redis：不作为全局统一 cache；保留为可重建协调、锁、幂等辅助和限流的候选实现，当前不新增复杂 key/namespace 体系。
- MySQL：继续作为 Conversation、Action、Task、Plan、Notification、Report、Audit 等 Business Fact 权威。
- Node sidecar SQLite：继续作为 Pi Session 恢复事实。
- 视频短地址、控制确认/readback、执行态、报告下载和会话流不进入普通 Redis cache。
- Redis 故障不影响 MySQL Business Fact；本票据不新增无法由现有业务证明必要的分布式缓存机制。

## Evidence to use

- backend `RedisCoordinationStore` / `LettuceRedisCoordinationStore`
- backend `issues/30-cache-architecture-audit.md`
- 项目开发规范中 MySQL/Redis 分工与深模块约束
