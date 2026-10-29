# 13: Task Change 与 Cancellation Durable Domain

**What to build:** 用户可以通过 Runtime 兼容的提案流程修改、跳过、暂停、恢复和取消任务/计划；确认前不产生执行副作用，确认后只对未来阶段生效并保持历史事实。

**Blocked by:** 08: Task、Plan、Notification 与 Durable Scheduler；11: Company 多源设备目录与 Control Reference

**Status:** resolved-local-task-change-cancellation-contract; production-execution-gate-open

- [x] MySQL 保存 task change、task cancellation proposal、before/after、version、expiry、source、owner 和终态。
- [x] confirm/discard 具备 CAS、幂等回放、过期拒绝、授权范围复核和 Runtime 错误语义。
- [x] 已执行 phase 不可修改；未来 phase 可按 Runtime 规则更新、重排、跳过或取消。
- [x] 周期计划支持 Runtime-compatible future occurrence edit/skip、pause/resume/delete，并保持 occurrence identity、历史报告和通知；scoped cancellation/resume confirmation/query boundaries are covered by ticket 19.
- [x] cancel 不会重新派发已终态阶段，重复 cancel 不重复写入或执行。
- [x] Runtime task-reference-management、task-plan-management、task-cancellation essential fixture behaviors are represented by Java contract tests; execution remains local fixture-only.

## Comments

- occurrence edit/skip, pause/resume/delete, scoped plan cancellation, resume confirmation, and complete validation use the durable MySQL occurrence/proposal seams. Already-materialized occurrences and non-identity targets remain explicit fail-closed cases. No production scanner or control gateway is touched.
