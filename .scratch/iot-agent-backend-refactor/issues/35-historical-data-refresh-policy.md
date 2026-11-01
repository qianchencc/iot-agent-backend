# 35: 历史数据深夜刷新与迟到修正

**Type:** grilling
**Status:** open
**Blocked by:** 31, 33

## Question

确认已结束日期数据的默认“深夜一次刷新”是否足够；处理客户补数、迟到采样、历史报告修正、上游分页变化和跨日边界。决定历史页面、原始能耗页、统计报告和已生成 Report Fact 是否采用不同存储/失效策略，以及用户主动刷新如何绕过日级快照。

## Evidence to use

- Runtime `energy/history-cache.ts`、`energy/report-snapshot-cache.ts`
- `mp/pages/agent/AGENTS.md` 能耗快照规则
- 软件说明书中的历史能耗、报告和安全台账案例
