# 10: 数据迁移、Parity 与生产切换

**What to build:** 在 Java 版本完成外部契约和业务闭环 parity 后，完成 Runtime 业务事实迁移、只读 shadow 验证、发布回滚和单向生产切换。

**Blocked by:** 06: Action Draft 确认闭环；07: Task、Plan 与 Notification 闭环；08: Company / Office 只读业务闭环；09: Admin 与模型配置兼容

**Status:** ready-for-agent

- [ ] Runtime 的会话事件、草案、任务、计划、通知、报告和审计迁移到 Java 事实模型，保留身份、顺序、版本和终态。
- [ ] Pi sidecar SQLite session 有独立备份、恢复和磁盘容量检查。
- [ ] 66 paths / 69 operations、认证 Header、错误体和 NDJSON 事件完成自动 parity 检查。
- [ ] 只读请求可 shadow 对照；控制、任务 scanner 和通知禁止双跑或双写。
- [ ] Java / Node / MySQL / Redis 的健康检查、日志、配置和回滚均可验证。
- [ ] 切换失败可以恢复旧 Runtime，且不丢失或重复执行设备控制。
