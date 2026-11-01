# 37: 缓存存储、Redis 协调与 MySQL 事实分工

**Type:** grilling
**Status:** open
**Blocked by:** 32, 34, 35, 36

## Question

在缓存模板和能力矩阵冻结后，决定每类内容使用 frontend memory、backend memory、Redis、MySQL 派生快照还是 Business Fact；定义 Redis key/namespace/身份摘要/TTL/容量/指标、跨实例 lease、Redis 故障降级，以及哪些内容绝不能进入 Redis。

## Evidence to use

- backend `RedisCoordinationStore` / `LettuceRedisCoordinationStore`
- backend `issues/30-cache-architecture-audit.md`
- 项目开发规范中 MySQL/Redis 分工与深模块约束
