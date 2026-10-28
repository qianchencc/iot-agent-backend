# 持久化路线考察（storage route）

Status: accepted-direction-needs-environment-proof
Type: research
Date: 2026-10-28

## 问题

iot-agent-backend 的会话/事件/任务/审计数据需要从 Runtime 的单 SQLite 事实边界迁移到客户选型要求的 Java 后端。Runtime 使用 `turn_events` 逐事件追加、断线按 sequence 重放、writer lease、报告快照缓存和加密配置修订；客户选型清单要求 MySQL 与 Redis，但 Redis 不能替代事实库。

## 已确认方向

- MySQL：Java 业务域长期事实，包括会话事实、NDJSON 事件、草案、任务、周期计划、通知、报告元数据、审计和配置修订。
- Redis：可重建缓存、分布式锁、幂等键、限流和运行期协调，不作为唯一事实来源。
- Pi Session：首期保留 Node sidecar 的 SQLite session backend，后续单独评估 MySQL-backed Pi session。
- Java 模块先定义仓储入口和行为 contract tests，再接入具体 MySQL/Redis 实现。

## 必须补齐的环境证据

- [ ] 公司现有 MySQL 实例版本、字符集、时区、连接方式、账号和可用 schema。
- [ ] 公司现有 Redis 实例版本、部署方式、持久化策略、连接方式和可用命令范围。
- [ ] 客户是否允许 Agent 业务域在现有 MySQL 中新增独立表和索引。
- [ ] Node sidecar SQLite 持久卷、备份、恢复和磁盘上限。
- [ ] Java/Node/MySQL/Redis 的 Docker 或主机部署边界。

## 验收门槛

- MySQL 版本能实现逐事件追加、按 sequence 重放、幂等和终态恢复。
- Redis 失效不会丢失业务事实，缓存和锁失效有可观测的受控行为。
- Pi Session SQLite 在 sidecar 重启后可恢复；其数据不被 Java 业务表误当作权威业务事实。
- 技术规范约束进入建表检查：无外键/级联、必备字段、小数 decimal、唯一索引、命名规范。

## 来源

- `docs/adr/0001-storage-route-undecided.md`
- `docs/reference/项目技术选型清单.docx`
- `docs/reference/技术规范(1).pdf`
- `../iot-agent-runtime/apps/runtime/src/app.ts`
- `../iot-agent-runtime/apps/runtime/src/agent/history.ts`
