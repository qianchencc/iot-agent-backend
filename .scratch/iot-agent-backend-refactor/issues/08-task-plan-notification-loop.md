# 08: Task、Plan、Notification 与 Durable Scheduler

**What to build:** 用户可以创建、确认、修改、取消和查看一次性任务及周期计划，后台按时执行并生成通知；服务重启、scanner 中断和重复请求不会造成重复控制或重复通知。

**Blocked by:** 03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环；07: Action Draft、控制安全与 Readback

**Status:** ready-for-agent

- [ ] Task proposal、confirmation、modification、cancellation、discard、detail、timeline、calendar 和 notification payload 与 Runtime 兼容。
- [ ] Task、Plan、Phase、Notification 和 audit 事实全部落 MySQL。
- [ ] Asia/Shanghai recurrence、phase version CAS、durable claim/lease、scanner recovery 和终态幂等成立。
- [ ] scheduler 重启后只恢复未完成阶段，不补跑已终态或未知控制。
- [ ] Task execution gateway 只能调用已核验的 Action/IoT 能力，并保留 accepted/rejected/unknown/readback 事实。
- [ ] notification projection 不泄露 token、authorization、password、private key 或 provider credential。
- [ ] Redis 只承担锁、幂等和运行期协调，MySQL 是计划、阶段和通知事实来源。
- [ ] 使用本地受控 gateway 和故障注入验证，不在客户生产上游运行 scanner 或发送通知。

## Comments

- Runtime 已记录任务管理、phase、scanner、recovery 和通知语义；当前 Java 只有 fail-closed seam，待接入 durable implementation。
