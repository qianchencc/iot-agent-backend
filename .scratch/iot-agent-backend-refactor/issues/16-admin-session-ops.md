# 16: Admin Apply 与 Pi Session 运维边界

**What to build:** Admin 配置保存/恢复/测试/应用和 Node Pi Session 具备 Runtime 兼容的 busy-gate、监督重启、会话恢复、备份、恢复与完整性检查，且不误报配置已生效。

**Blocked by:** 03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环；09: Admin、模型配置与运维边界

**Status:** ready-for-agent

- [ ] Admin apply 在 active run、execution summary、executing draft 或 task phase 存在时拒绝。
- [ ] apply 只在受监督进程 handoff 成功后返回成功；未接入 supervisor 时保持明确 fail-closed。
- [ ] Node sidecar session SQLite 的路径、writer lease recovery、online backup、restore、quick_check 和容量检查可执行。
- [ ] Java MySQL 业务事实与 Pi Session SQLite 的权威边界明确，不互相冒充。
- [ ] healthz、备份和恢复检查不返回 secret、token、URL 或内部授权键。
- [ ] 使用 Runtime admin-apply、session-writer-recovery 和本地部署 procedure 做 contract verification。

## Comments

- Runtime apply 是 supervised restart，不是 hot reload。
- 客户持久卷、磁盘容量、部署 supervisor 和 MySQL schema 权限属于后续外部部署验收输入。
