# 31: 冻结缓存新鲜度与固定模板

**Type:** grilling
**Status:** resolved
**Blocked by:** None

## Question

不要把 backend cache 与 frontend cache 合并成一个 TTL 模板。本票据只冻结两层各自的固定模板：

- **Backend：** 历史数据最新快照、当天当前数据最新快照、按查询条件生成的低频快照；定义 backend→upstream 的刷新/复用策略和失败状态。
- **Frontend：** 从 backend 取得的数据在 UI 侧的复用窗口、手动刷新行为、页面占位和写操作后的局部失效。
- **事实/事务：** Action Draft、Task/Plan、Receipt、Readback、Report Fact 等不作为普通 cache 模板，沿业务事实/实时读取规则处理。

用户已确认：历史数据原则上深夜刷新；当天所有读请求进入当前数据范围；frontend 默认约 1 分钟复用 backend 返回值；backend 刷新策略约 5 秒但不代表每次请求都访问 upstream。

需要进一步为 Backend 与 Frontend 分别定义 fresh/stale、刷新触发、失败展示和缓存可复用的查询键，不能用一套“缓存模板”覆盖两层。

## Answer

固定模板按责任层冻结：

- **Backend 历史快照：** 已结束日期/历史窗口原则上深夜刷新；补数和迟到采样另按业务发现处理。
- **Backend 当前数据：** 高频核心能力按组合策略更新；低频查询按需合并/复用；不要求每个请求访问 upstream。
- **Backend 查询结果：** 按完整查询键复用，错误/部分/准备中结果不延长正常有效期。
- **Frontend 读快照：** 当前数据默认约 1 分钟复用；手动刷新只绕过 frontend 快照；局部写操作只失效受影响的读取。
- **事实/事务：** Action Draft、Task/Plan、Receipt、Readback、Report Fact、会话和 Pi Session 不进入普通 cache。

不再设计统一 TTL、统一 Redis cache 或复杂通用失效机制。后续只针对业务矩阵中出现的实际慢点做局部矫正。

## Evidence to use

- `issues/30-cache-architecture-audit.md`
- `mp/apis/agent/agent-read-cache.mjs`
- Runtime `company-read-cache.ts`、`energy/report-snapshot-cache.ts`、`energy/history-cache.ts`
