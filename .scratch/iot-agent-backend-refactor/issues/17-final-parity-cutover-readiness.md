# 17: Final Parity 与切换准备

**What to build:** Java 后端在不触碰生产云后端的前提下通过完整 Runtime 行为 parity、事实恢复、只读 shadow、备份恢复和回滚证据，达到人工审核的切换准备状态。

**Blocked by:** 11: Company 多源设备目录与 Control Reference；12: Dashboard 与 Region 本地组合；13: Task Change 与 Cancellation Durable Domain；14: Report Artifact 与 PDF/XLSX；15: Video Live 与 Playback；16: Admin Apply 与 Pi Session 运维边界

**Status:** blocked-by-phase-2-slices

- [ ] 66 paths / 69 operations 的请求、Header、source、状态码、错误体和 NDJSON 行为 gate 通过。
- [ ] Conversation、Draft、Task、Plan、Phase、Notification、Report、Audit 和 Admin facts 完成 Runtime → MySQL 映射并支持恢复。
- [ ] Pi Session backup/restore/integrity/capacity evidence 可复核。
- [ ] 只读 shadow 可对照，不允许 control/scanner/notification 双跑、双写或影子执行。
- [ ] 生产默认 fail-closed，所有生产写入和切换仍需独立审批。
- [ ] 形成切换窗口、监控、停止条件、回滚和生产权限清单；本票不执行云端部署或切换。

## Comments

- Route coverage 不是 behavioral parity；必须覆盖 Runtime regression fixtures 和真实外部 HTTP/NDJSON 语义。
- 生产控制、视频真实解码和 supervisor/cutover 只作为外部 acceptance evidence，不在本地冒充通过。
