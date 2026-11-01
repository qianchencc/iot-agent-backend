# 36: 当天实时数据 1 分钟模板与强制现读例外

**Type:** grilling
**Status:** resolved
**Blocked by:** 31, 32, 33

## 2026-11-02 执行修订

“实时”对前端表示读取backend当前状态，不表示等待上游现读。当前结果按约60秒后台更新；已有数据保持显示，首次缺数诚实准备中。已确认操作、回读、短视频地址等继续走专门业务规则，不能把显示快照当执行成功证据。实施见[整改规格](../cache-current-read-spec.md)与47–50号票。


## Question

当天所有读请求先纳入当前数据范围，frontend 默认约 1 分钟复用 backend 返回值；这不是“每个 frontend 请求都触发全量 upstream 读取”。

需要决定：

- backend 约 5 秒获取最新进度的机制是定时刷新、请求触发最短间隔，还是两者组合；
- 设备状态、环境、能耗、告警、区域目录、任务执行态、通知未读是否共享同一刷新节奏；
- frontend 轮询、页面打开、手动刷新如何消费 backend 最新快照；
- 控制确认前目录/能力、执行后 readback、视频短地址等是否作为不缓存/强制现读例外；
- 当前 backend 快照不可用或更新失败时，frontend 如何表达“沿用上次值/暂不可用”。

## Answer

- 当天所有读请求进入当前数据范围，但不强制统一成一种复杂缓存策略。
- Frontend 继续使用约 60 秒同身份读快照；页面打开可先展示已有结果，手动刷新 force frontend 请求。
- Backend 采用已确定的组合刷新：核心当前数据按能力更新，低频查询按需合并/复用；用户请求不默认全量触发上游。
- 控制确认、执行后 readback、任务执行态/详情、视频短地址和会话流保持实时/事实语义，不进入普通当前读快照。
- 当前 backend 结果不可用时，沿用现有“暂不可用/缺数/后台准备”等明确状态，不用空值伪装成功。

## Evidence to use

- Runtime `apps/runtime/src/AGENTS.md` 的状态时效与 fresh readback 规则
- `mp/pages/agent/AGENTS.md` 的状态刷新与任务轮询规则
- backend `issues/30-cache-architecture-audit.md`
