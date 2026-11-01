# 45: 物联助手首屏打开超过 10s

**Type:** task
**Status:** resolved
**Blocked by:** 43, 44

## 症状

用户报告：每次打开物联助手页面加载都在 10s 以上。

## 反馈环

`/tmp/mp-dashboard-timing.py`：导航到 `pages/agent/dashboard`，轮询页面 6 个状态块直到不再是「读取中」，输出各块落定时间。修复前稳定变红（10s 内 3–4/6 落定）。

## 根因（实测）

1. **`company-energy-daily` 30s 超时 → 503**。站点有 43 个电表；单表 30 天约 3999 行，旧代码 `size=100` + 每表上限 60 页，组合读取远超超时预算。
2. **WebClient 默认 256KB 缓冲上限**。500 行/页响应约 666KB，超过上限抛 `DataBufferLimitException`，被 `onErrorMap` 统一映射成「客户观察上游请求失败」——表面像上游故障，实际是本地编解码限制。
3. **前端 `Promise.allSettled` 等最慢分区**，`loading` 一直挂到能耗返回。

排除项：上游网关本身不慢（原始单页 1.0–1.2s，43 表 6 路并发全部 200）。

## 修正

- `spring.codec.max-in-memory-size: 8MB`（可配）。
- 能耗分页 `size` 100 → 500，每表页数从 ~12 降到 ~3。
- 新增墙钟读预算 `DEFAULT_READ_BUDGET_MS = 3000`：预算耗尽按截断处理，不再撞上游超时。
- 电表并发显式常量化为 6，避免大目录打满网关。
- 前端首屏临界分区（环境/告警/设备）完成即解除整页 loading，能耗单独 `energyLoading`。

## 验收

- 能耗接口：`30000ms / 503` → `5300ms / 200 / truncated`。
- 页面反馈环：首屏 5/6 分区 **~1s** 落定（原 >10s），能耗随后以「暂无数据」落定。
- 回归测试 `oversizedEnergyHistoryStopsWithinPageBudgetAndReportsTruncation`：对旧代码变红（12 页 > 4），修复后转绿。
- 后端全量 `mvn test` 通过；MP `npm run test:agent` 213 passed。

## 未解决（需业务决定）

能耗日用量当前全部为 null。上游对该站点每表 30 天仅返回约 61 个采样（约 2/天），不足以做 08:00 边界的累计示数差分，因此「暂无数据」是诚实结果而非读取失败。要出可用的逐日用量，需要客户侧提供日聚合接口或更密的采样，属于数据口径决策，不在本次性能修复范围。
