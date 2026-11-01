# 34: 前后端缓存责任与失效协作

**Type:** grilling
**Status:** resolved
**Blocked by:** 31, 32, 33

## 2026-11-02 执行修订

前端只面对backend，不管理上游刷新；手动刷新读取backend当前结果。backend已有结果即时返回，失效只标待更新，不把下一次请求改为等待上游。该票原文没有要求每次请求重核全量设备，也没有要求所有写入清空backend全部结果；此前聊天中的相反概括撤回。实现及失效范围见[整改规格](../cache-current-read-spec.md)与47–50号票。


## Question

冻结两层完全不同的责任：

1. **backend cache / backend latest snapshot：** 目标是减少 backend→upstream。backend 可以按约 5 秒策略获取最新进度；用户请求命中 backend 最新快照时不访问 upstream。
2. **frontend cache：** 目标是减少 UI→backend。前端约 1 分钟内复用从 backend 取得的结果；页面手动刷新只绕过 frontend 快照重新请求 backend，不要求 backend 强制访问 upstream。

需要明确哪些 backend 能力共享同一最新快照、哪些查询键必须分开；页面打开、前端手动刷新、后台轮询、写操作后的局部失效和在途请求如何协作。权限仍按既有认证/授权不变量处理，不在本票据新增撤权缓存机制。

## Answer

- Frontend 只对 `index.js` 显式接入 `readSnapshot` 的只读路径复用快照：概览、Company 目录/统计、安全列表、任务/计划/通知列表；不是所有 GET。
- Frontend 手动刷新只绕过 frontend snapshot；backend 是否命中最新结果由 backend 自己决定，不强制 upstream refresh。
- 控制、草案、执行状态、任务详情、视频地址、报告下载和会话流不进入普通 frontend cache。
- backend 需要逐步提供当前数据最新结果和在途合并，首批以首屏、常用目录/状态、Agent 候选查询为目标；不先建立全量分布式 cache。
- 写操作只清理受影响的 frontend snapshot；事实保存和业务 CAS 仍由 backend/MySQL 负责。

## Evidence to use

- `mp/apis/agent/agent-read-cache.mjs`
- `mp/apis/agent/index.js`
- `pages/agent/AGENTS.md`
- backend `CompanyOfficeObservationAdapter`
