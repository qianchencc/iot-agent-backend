# 34: 前后端缓存责任与失效协作

**Type:** grilling
**Status:** open
**Blocked by:** 31, 32, 33

## Question

冻结两层完全不同的责任：

1. **backend cache / backend latest snapshot：** 目标是减少 backend→upstream。backend 可以按约 5 秒策略获取最新进度；用户请求命中 backend 最新快照时不访问 upstream。
2. **frontend cache：** 目标是减少 UI→backend。前端约 1 分钟内复用从 backend 取得的结果；页面手动刷新只绕过 frontend 快照重新请求 backend，不要求 backend 强制访问 upstream。

需要明确哪些 backend 能力共享同一最新快照、哪些查询键必须分开；页面打开、前端手动刷新、后台轮询、写操作后的局部失效和在途请求如何协作。权限仍按既有认证/授权不变量处理，不在本票据新增撤权缓存机制。

## Evidence to use

- `mp/apis/agent/agent-read-cache.mjs`
- `mp/apis/agent/index.js`
- `pages/agent/AGENTS.md`
- backend `CompanyOfficeObservationAdapter`
