# 08: Task、Plan、Notification 与 Durable Scheduler

**What to build:** 用户可以创建、确认、修改、取消和查看一次性任务及周期计划，后台按时执行并生成通知；服务重启、scanner 中断和重复请求不会造成重复控制或重复通知。

**Blocked by:** 03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环；07: Action Draft、控制安全与 Readback

**Status:** implemented-locally (durable one-shot and bounded MySQL recurrence materialization/claim loop; production execution gateway remains fail-closed)

- [x] Task proposal、confirmation、discard、detail、timeline、calendar 和 notification payload 与 Runtime 兼容。
- [ ] Task modification/cancellation proposal semantics remain unavailable; exact routes fail closed with 503 until their durable domain is implemented.
- [x] Task、Plan、Phase、Notification 和 audit 事实全部落 MySQL。
- [x] Asia/Shanghai recurrence data model、phase version CAS、durable claim/lease、scanner recovery 和终态幂等成立。
- [x] Scheduler recurrence occurrence materialization and repeat-date claiming are implemented in a bounded MySQL transaction.
- [x] scheduler 重启后只恢复未完成阶段，不补跑已终态或未知控制。
- [x] Task execution gateway 只能调用已核验的 Action/IoT 能力，并保留 accepted/rejected/unknown/readback 事实（production default fail-closed; local fixture explicit）。
- [x] notification projection 不泄露 token、authorization、password、private key 或 provider credential。
- [x] Redis 只承担锁、幂等和运行期协调，MySQL 是计划、阶段和通知事实来源。
- [x] 使用本地受控 gateway 和故障注入验证，不在客户生产上游运行 scanner 或发送通知。

## Comments

- 2026-10-29：Explicit fixture smoke confirmed a due one-shot task, MySQL phase transitioned to `COMPLETED`, task fact became `COMPLETED`, and one notification was listed. The bounded JDBC recurrence loop now claims `(plan_id, occurrence_date)` uniquely, snapshots phase times in Asia/Shanghai, advances the plan pointer by CAS, and materializes task/phase facts in one transaction. Covered rules are `daily`, `weekdays`, `weekly` (ISO 1–7), and `monthly` (1–31; nonexistent short-month dates skipped), with inclusive `until`; malformed/unknown rules fail closed. Runtime occurrence overrides, per-occurrence edits/skips, and custom first-occurrence templates are not ported. Production execution gateway stays fail-closed.
- 2026-10-29：Review correction: task change/cancellation proposal routes are present for external path compatibility but intentionally return `503 TASK_CHANGE_UNAVAILABLE` / `503 TASK_CANCELLATION_UNAVAILABLE`; no false success or customer-side mutation is performed.
