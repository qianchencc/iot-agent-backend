# 17: Final Parity 与切换准备

**What to build:** Java 后端在不触碰生产云后端的前提下通过完整 Runtime 行为 parity、事实恢复、只读 shadow、备份恢复和回滚证据，达到人工审核的切换准备状态。

**Blocked by:** [说明书全场景收口](../../agent-business-recovery/issues/16-manual-acceptance-readiness.md)

**Status:** ready-for-agent

**业务验收：重新打开。** 2026-11-02用户确认原local readiness不能证明新后端完整业务。当前执行由[说明书全场景收口](../../agent-business-recovery/issues/16-manual-acceptance-readiness.md)统一承接，本票不另建重复实现。原11–22的工程记录保留为线索，不再充当全部前置已完成的证据；生产切换仍不在本轮授权内。

- [ ] 当前同一构建完成指定说明书全部可执行场景，逐项有真实微信前端证据；明确上游缺口和用户允许不执行的边界，不用接口、旧截图或测试替代。
- [ ] Java拥有全部业务定义，新增真实业务能力保持Node源码和产物不变，维护演示有前端证据。
- [ ] 缓存主线取得可审查提交后安全集成，并完成当前读取及业务回归；不搬入原工作区未提交内容。

下列勾选及Comments均为历史工程记录，不表示本次业务验收通过。

- [ ] 66 paths / 69 operations 的请求、Header、source、状态码、错误体和 NDJSON 行为通过完整真实 behavioral parity gate；本轮代表性本地 gate 已通过，未执行生产写入或切换。
- [x] Conversation、Draft、Task、Plan、Phase、Notification、Report、Audit 和 Admin facts 完成 Java/Node local ownership 映射并支持恢复。
- [x] Pi Session backup/restore/integrity/capacity evidence is locally implemented; independent Java adapter and Java bridge/supervisor process evidence are covered by ticket 22 gated tests.
- [x] Local normalized shadow comparison and explicit denylist are implemented in ticket 21; live Runtime dispatch/comparison remains intentionally unexecuted.
- [x] 生产默认 fail-closed，所有生产写入和切换仍需独立审批。
- [x] 已形成切换窗口、监控、停止条件、回滚和生产权限清单；本票不执行云端部署或切换。

## Comments

- Local final readiness is complete: backend/sidecar ownership, recovery, shadow planner, fail-closed production boundary, local Developer Tools acceptance, and rollback/runbook evidence are present. The only unchecked item is the complete 66/69 behavioral gate, which needs a safe customer acceptance corpus or explicit upstream protocol fixtures.
- 2026-11-02：重开业务完成门禁。上一条“local final readiness is complete”仅为当时局部工程结论，不能覆盖当前真实前端未验收；执行依据改为[恢复规格](../../agent-business-recovery/spec.md)。
