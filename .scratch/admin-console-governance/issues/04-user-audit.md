# 04: 用户操作审计闭环

**What to build:** 管理员在A成员分栏中按用户查看公司和办公室操作，能够看到谁、何时、做了什么及结果；拒绝和失败也进入审计。

**Blocked by:** 01 管理员登录与控制台壳；独立白名单不是成员轨迹或业务的前置

**Status:** completed（2026-11-05，实现与两来源真实只读前端验证；真实控制与自动任务执行仍未验收）

- [x] 审计事件关联可信用户、tenant、identity realm、principal和company/office来源（AGENT_*/ACTION_DRAFT_*/TASK_* 事件携带身份三元组；无归属事件诚实归入未识别桶，不借 owner hash/名单/操作者命名）
- [x] 记录请求、关键业务步骤和最终结果，区分成功、拒绝、失败和未知结果（AGENT_REQUESTED 带成员请求文本；终态 AGENT_RUN_* 带公开答复；ACTION_DRAFT_* 覆盖提案/复核/取消/中断/执行结果；TASK_PROPOSED/TASK_CHANGE_PROPOSED/TASK_CANCELLATION_PROPOSED 及三个 CONFIRMED 与业务事实同事务落库）
- [x] 审计事件追加保存且重复事件不覆盖不同原内容；清理会话不删除审计事实（audit_events 追加式，重复 eventId 保留原内容并返回持久化行；DELETE 会话仅清 conversation 表）
- [x] 用户／来源／时间／类型／结果由服务端先过滤再分页；成员索引支持完整主体ID查询。真实管理端已看到同一核验成员的公司2条、办公室2条请求／完成记录；类型筛选实际传入eventType，详情显示相同公开答复。
- [x] 详情不显示Pi隐藏思维、密文、凭据或无关原始工具响应（detail 白名单投影；自由文本 Bearer/密码类模式脱敏；observedStates/未知字段丢弃）
- [x] 无归属或历史归属不足时诚实显示未知，不把owner hash当作姓名（identityResolved=false → “未识别主体”）
- [x] 新确认任务只保存服务端核验主体；系统执行保持actor=system，历史身份缺失诚实显示未知。检查点审计故障不重派，任务终态事务从持久阶段事实补齐；本项以本地行为回归验证，不当作真实设备执行验收。
- [x] 关联草案／任务／报告事实、上游回执与状态回读分别投影；审计不读取Pi隐藏思维或原始工具响应。

实现位置：`audit/**`（含 AuditEventQuery/AuditMember/AuditMemberIndex/AuditQuerySupport 有界查询契约）、`storage/internal/JdbcReportAuditStore`（下推 SQL + 游标 + 分组索引 idx_audit_tenant_time）、`admin/internal/AuditAdminController`（无名单依赖；存储故障 503 不报空成功）、`conversation/ConversationFacade`（请求/公开答复入审计）、`task/TaskFacade`+`TaskConfiguration`（可空 AuditEventStore，确认审计与业务事实同事务）、`web/admin/src/views/AuditView.vue`、测试 `admin/AuditAdminApiTest` 与 `storage/ReportAuditStoreBehaviorTest`（后者按隔离 MySQL 环境变量门控）。action/ActionDraftFacade 既有审计钩子无需改动。
