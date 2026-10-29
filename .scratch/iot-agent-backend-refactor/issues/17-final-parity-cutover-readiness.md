# 17: Final Parity 与切换准备

**What to build:** Java 后端在不触碰生产云后端的前提下通过完整 Runtime 行为 parity、事实恢复、只读 shadow、备份恢复和回滚证据，达到人工审核的切换准备状态。

**Blocked by:** 11: Company 多源设备目录与 Control Reference；12: Dashboard 与 Region 本地组合；13: Task Change 与 Cancellation Durable Domain；14: Report Artifact 与 PDF/XLSX；15: Video Live 与 Playback；16: Admin Apply 与 Pi Session 运维边界；18: Independent Node Pi Agent Sidecar；19: Task Plan Runtime Parity；20: Report Conversation Flow；21: Behavioral Parity/Migration/Shadow Evidence；22: Sidecar Event/Tool/Process Parity

**Status:** resolved-local-final-readiness; full-behavioral-parity-and-production-cutover-gates-open

- [ ] 66 paths / 69 operations 的请求、Header、source、状态码、错误体和 NDJSON 行为通过完整真实 behavioral parity gate；本轮代表性本地 gate 已通过，未执行生产写入或切换。
- [x] Conversation、Draft、Task、Plan、Phase、Notification、Report、Audit 和 Admin facts 完成 Java/Node local ownership 映射并支持恢复。
- [x] Pi Session backup/restore/integrity/capacity evidence is locally implemented; independent Java adapter and Java bridge/supervisor process evidence are covered by ticket 22 gated tests.
- [x] Local normalized shadow comparison and explicit denylist are implemented in ticket 21; live Runtime dispatch/comparison remains intentionally unexecuted.
- [x] 生产默认 fail-closed，所有生产写入和切换仍需独立审批。
- [x] 已形成切换窗口、监控、停止条件、回滚和生产权限清单；本票不执行云端部署或切换。

## Comments

- Local final readiness is complete: backend/sidecar ownership, recovery, shadow planner, fail-closed production boundary, local Developer Tools acceptance, and rollback/runbook evidence are present. The only unchecked item is the complete 66/69 behavioral gate, which needs a safe customer acceptance corpus or explicit upstream protocol fixtures.
