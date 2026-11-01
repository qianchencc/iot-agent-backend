# 31: 冻结缓存新鲜度与固定模板

**Type:** grilling
**Status:** open
**Blocked by:** None

## Question

不要把 backend cache 与 frontend cache 合并成一个 TTL 模板。本票据只冻结两层各自的固定模板：

- **Backend：** 历史数据最新快照、当天当前数据最新快照、按查询条件生成的低频快照；定义 backend→upstream 的刷新/复用策略和失败状态。
- **Frontend：** 从 backend 取得的数据在 UI 侧的复用窗口、手动刷新行为、页面占位和写操作后的局部失效。
- **事实/事务：** Action Draft、Task/Plan、Receipt、Readback、Report Fact 等不作为普通 cache 模板，沿业务事实/实时读取规则处理。

用户已确认：历史数据原则上深夜刷新；当天所有读请求进入当前数据范围；frontend 默认约 1 分钟复用 backend 返回值；backend 刷新策略约 5 秒但不代表每次请求都访问 upstream。

需要进一步为 Backend 与 Frontend 分别定义 fresh/stale、刷新触发、失败展示和缓存可复用的查询键，不能用一套“缓存模板”覆盖两层。

## Evidence to use

- `issues/30-cache-architecture-audit.md`
- `mp/apis/agent/agent-read-cache.mjs`
- Runtime `company-read-cache.ts`、`energy/report-snapshot-cache.ts`、`energy/history-cache.ts`
