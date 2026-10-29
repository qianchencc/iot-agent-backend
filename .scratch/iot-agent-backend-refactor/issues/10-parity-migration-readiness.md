# 10: 全量 Parity、事实迁移与切换准备

**What to build:** Java 后端在不触碰云端生产后端的前提下完成 Runtime 外部契约、业务事实、只读 shadow、备份恢复和回滚证据，达到可被人工审核的单向切换准备状态。

**Blocked by:** 05: Office Authorized Device Scope 与只读工具；06: Company Observation、Report、Video 只读闭环；07: Action Draft、控制安全与 Readback；08: Task、Plan、Notification 与 Durable Scheduler；09: Admin、模型配置与运维边界；11–16: Phase 2 Runtime behavior migration slices；18: Independent Node Pi Agent Sidecar；19: Task Plan Runtime Parity；20: Report Conversation Flow；21: Behavioral Parity/Migration/Shadow Evidence；22: Sidecar Event/Tool/Process Parity

**Status:** open-behavioral-parity-and-evidence-gate

- [ ] Runtime 与 Java 的 66 paths / 69 operations、Header、source、错误体和 NDJSON schema 通过真实 parity gate。
- [ ] Conversation、Draft、Task、Plan、Phase、Notification、Report、Audit 和配置事实完成 Runtime → MySQL 映射，保留 identity、sequence、revision 和 terminal state。
- [ ] Pi Session SQLite 备份、恢复、完整性和容量检查通过。
- [ ] 只读请求可以 shadow 对照；控制、任务 scanner 和通知禁止双跑、双写和影子执行。
- [ ] 事实迁移支持 dry-run、校验、幂等重跑和失败停止，不覆盖来源事实。
- [x] Java、Node、MySQL、Redis 的健康、日志、配置、备份和恢复 evidence 可复核（local only）。
- [x] 失败回滚保持旧 Runtime 生产权威，不重复执行设备控制或任务（no cloud cutover executed）。
- [ ] 形成切换窗口、审批、监控、停止条件和回滚 runbook，但不执行云端生产部署、重启或切换。

## Comments

- 2026-10-30：Runtime source review corrected the blocker classification. Task change/cancellation, Dashboard/Region composition, Report artifacts, Video, Company multi-source catalog/controlRef and Admin apply are implementation slices backed by Runtime code/tests, not requests for new customer protocol documentation. The new dependency-ordered Phase 2 tickets are 11–16; ticket 17 is the final parity/cutover-readiness gate. External inputs remain limited to deployment volume/backup/supervisor permissions and production control/video/cutover acceptance.
