# 08: Task、Plan、Notification 与 Durable Scheduler

**What to build:** 用户可以创建、确认、修改、取消和查看一次性任务及周期计划，后台按时执行并生成通知；服务重启、scanner 中断和重复请求不会造成重复控制或重复通知。

**Blocked by:** 03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环；07: Action Draft、控制安全与 Readback

**Status:** implemented-core-open-plan-parity

- [x] Task proposal、confirmation、discard、detail、timeline、calendar 和 notification payload 与 Runtime 兼容。
- [ ] Task modification/cancellation proposal core exists; scoped plan cancellation, resume confirmation, complete occurrence validation, and Runtime query shapes remain in ticket 19.
- [x] Task、Plan、Phase、Notification 和 audit 事实全部落 MySQL。
- [x] Asia/Shanghai recurrence data model、phase version CAS、durable claim/lease、scanner recovery 和终态幂等成立。
- [x] Scheduler recurrence occurrence materialization and repeat-date claiming are implemented in a bounded MySQL transaction.
- [x] scheduler 重启后只恢复未完成阶段，不补跑已终态或未知控制。
- [x] Task execution gateway 只能调用已核验的 Action/IoT 能力，并保留 accepted/rejected/unknown/readback 事实（production default fail-closed; local fixture explicit）。
- [x] notification projection 不泄露 token、authorization、password、private key 或 provider credential。
- [x] Redis 只承担锁、幂等和运行期协调，MySQL 是计划、阶段和通知事实来源。
- [x] 使用本地受控 gateway 和故障注入验证，不在客户生产上游运行 scanner 或发送通知。

## Comments

- Durable one-shot/recurring facts, scanner recovery, notification projection, occurrence identity, and core change/cancellation CAS exist locally. Ticket 19 closes Runtime-specific scoped cancellation, resume authorization, future-phase validation, and public query parity. Production execution remains fail-closed.
