# 36: 当天实时数据 1 分钟模板与强制现读例外

**Type:** grilling
**Status:** open
**Blocked by:** 31, 32, 33

## Question

当天所有读请求先纳入当前数据范围，frontend 默认约 1 分钟复用 backend 返回值；这不是“每个 frontend 请求都触发全量 upstream 读取”。

需要决定：

- backend 约 5 秒获取最新进度的机制是定时刷新、请求触发最短间隔，还是两者组合；
- 设备状态、环境、能耗、告警、区域目录、任务执行态、通知未读是否共享同一刷新节奏；
- frontend 轮询、页面打开、手动刷新如何消费 backend 最新快照；
- 控制确认前目录/能力、执行后 readback、视频短地址等是否作为不缓存/强制现读例外；
- 当前 backend 快照不可用或更新失败时，frontend 如何表达“沿用上次值/暂不可用”。

## Evidence to use

- Runtime `apps/runtime/src/AGENTS.md` 的状态时效与 fresh readback 规则
- `mp/pages/agent/AGENTS.md` 的状态刷新与任务轮询规则
- backend `issues/30-cache-architecture-audit.md`
