# 07: Task、Plan 与 Notification 闭环

**What to build:** 小程序可以创建、确认、修改、取消和查看一次性任务及周期计划，后台按时执行并生成通知，且重启和重复请求不造成重复执行。

**Blocked by:** 04: Conversation 流式闭环；05: IoT 身份与 Authorized Device Scope；06: Action Draft 确认闭环

**Status:** ready-for-agent

- [ ] 任务提案与确认事件、任务详情、时间轴、日历和通知接口兼容。
- [ ] 周期计划的启停、修改、取消、下次发生和到点 scanner 保持原语义。
- [ ] scanner 使用阶段 CAS 认领，重复扫描和进程重启不重复下发。
- [ ] 任务凭据不出现在接口、事件、日志或模型上下文中。
- [ ] 已读、区域水位、任务来源和当前授权裁剪保持隔离。
- [ ] 测试覆盖正常、未核验、部分结果、取消、中断、重复确认和恢复。
