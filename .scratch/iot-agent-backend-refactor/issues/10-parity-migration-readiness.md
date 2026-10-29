# 10: 全量 Parity、事实迁移与切换准备

**What to build:** Java 后端在不触碰云端生产后端的前提下完成 Runtime 外部契约、业务事实、只读 shadow、备份恢复和回滚证据，达到可被人工审核的单向切换准备状态。

**Blocked by:** 05: Office Authorized Device Scope 与只读工具；06: Company Observation、Report、Video 只读闭环；07: Action Draft、控制安全与 Readback；08: Task、Plan、Notification 与 Durable Scheduler；09: Admin、模型配置与运维边界；11–16: Phase 2 Runtime behavior migration slices；18: Independent Node Pi Agent Sidecar；19: Task Plan Runtime Parity；20: Report Conversation Flow；21: Behavioral Parity/Migration/Shadow Evidence；22: Sidecar Event/Tool/Process Parity

**Status:** resolved-local-migration-recovery-and-rollback-evidence; full-behavioral-parity-and-production-cutover-gates-open

- [ ] Runtime 与 Java 的 66 paths / 69 operations、Header、source、错误体和 NDJSON schema 通过完整真实 behavioral parity gate；本轮只完成代表性本地行为 gate，未调用客户生产写入或切换。
- [x] Conversation、Draft、Task、Plan、Phase、Notification、Report、Audit 和配置事实完成 Java-owned MySQL 映射，保留 identity、sequence、revision 和 terminal state。
- [x] Pi Session SQLite 备份、恢复、完整性和容量检查通过。
- [x] 只读请求的本地 normalized shadow comparison 已实现；控制、任务 scanner 和通知禁止双跑、双写和影子执行。
- [x] 事实迁移的 dry-run、校验、幂等重跑和失败停止规划器已实现，不覆盖来源事实。
- [x] Java、Node、MySQL、Redis 的健康、日志、配置、备份和恢复 evidence 可复核（local only）。
- [x] 失败回滚保持旧 Runtime 生产权威，不重复执行设备控制或任务（no cloud cutover executed）。
- [x] 已形成切换窗口、审批、监控、停止条件和回滚 runbook；本票不执行云端生产部署、重启或切换。

## Comments

- 2026-10-30：Local migration/recovery/rollback evidence and runbook are complete. The only unchecked item is the full 66/69 behavioral parity gate, which requires a safe customer acceptance corpus or explicit upstream protocol fixtures; production cutover remains out of scope and untouched.
