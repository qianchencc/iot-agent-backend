# 52: 草案设备状态必须是后端持有的当前设备事实

**What to build:** 用户打开待确认或执行中的草案时，灯和空调应展示 backend 持有的当前平台状态；不能把尚不存在的执行回读记录当作设备状态，导致全部未知。前端只认识 backend，不承担网关同步。

**Blocked by:** None
**Status:** ready-for-human

## 已确认的问题

- MP 草案卡片及明细调用 GET /api/agent/action-drafts/:id/device-states。
- Runtime 同路径读取草案设备的当前平台状态，返回 states 映射、observedAt、source。
- Java ActionDraftFacade.deviceStates 错误读取 ActionReadbackStore，仅投影执行后的回读证据；未执行草案没有这些证据，所以返回空列表和空设备映射。
- 公共行为红测 pendingDraftDisplaysCurrentDeviceStateWithoutAnyDispatchReadback 已实际运行：期望 device-1.power=on，实际 states=[]。
- 普通状态读取还应避免 currentChecked 每次重新扫描完整范围；身份/草案归属在本地检查，后端同步按既有范围与状态策略执行。控制确认前的权威校验不削弱。

## 实施约束

- 当前设备状态和执行回执/回读分离。summary 仍只返回持久执行事实，不能用当前状态重写历史执行结果。
- 草案状态使用既有 ReadSnapshots CURRENT 模板，与普通设备状态共享身份、来源、设备集合键。HTTP 返回已持有结果，未准备返回明确 preparing；同步仅在后台进行。
- 前端沿用普通读取的 preparing 协议，不把 preparing 当空状态覆盖已有显示；不是新增上游依赖或另一套缓存平台。
- 保留租户、用户、来源及草案归属隔离；确认控制仍用权威现读，不使用展示快照作为执行证据。
- 本轮只读或生成未执行草案，禁止真实确认下发、创建任务或发送通知。真实页面验收由用户完成，不替用户宣告通过。

## 验收条件

- [x] 无回读记录的待确认草案返回灯/空调当前状态，公共行为红后绿；不再将空回读当设备未知。
- [x] 同一身份/来源/排序设备集合复用既有CURRENT后端快照，普通草案读取不实时扫描完整范围；确认控制的权威校验保留。
- [x] summary保持持久执行事实，不被当前设备状态覆盖。
- [x] 归属/缺失草案回归及快照身份/来源隔离回归通过；前端取消旧草案的等待。
- [x] 真实只读3灯、2空调状态有效；重启后stale仍返回已有状态；完整后端回归、前端259项、157边界及开发编译通过。
- [ ] 用户在真实草案卡片/明细页面确认设备状态显示；本项不以HTTP或回归替代。

证据：[当前状态与执行回读分离](../evidence/draft-current-device-state-2026-11-02.md)。
