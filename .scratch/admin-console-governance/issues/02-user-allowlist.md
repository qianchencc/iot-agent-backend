# 02: 用户白名单闭环（手机号名单）

**What to build:** 管理员维护一份手机号名单（`mobileNumber` 必填，`name` 为可选姓名备注）。按 2026-11-04 最新指令：名单**暂不接入任何实际业务**，不管谁能用公司 Agent、不做任务取消、不新增认证/Token 核验/官方账号目录/任意 SSO 查询、不要求用户先登录；移除仅删除名单记录，不影响公司/办公室可用性与任何任务。旧 `CompanyAgentAllowlist`（tenant/identityRealm/principalId 身份三元组）是错误业务模型，已干净切换为 `PhoneAllowlist` 公开模块，无旧准入别名或 fallback；旧表 `company_agent_allowlist(_state)` 作为历史保留，不迁移、不删除、不再读写。

**Blocked by:** 01 管理员登录与控制台壳

**Status:** completed（2026-11-05；隔离MySQL／浏览器已验证添加、重载持久化、取消移除与确认移除；不接业务）

研究及已完成撤回：[账号选择研究](../account-admission-research.md)、[真实前端恢复证据](../evidence/allowlist-withdrawal/result.json)。ADR0007 中"移除取消未执行任务/阶段/计划"的决策已被最新指令取代（白名单不接业务、不做取消）；ADR 更新归父任务。

- [x] 管理员直接录入手机号；姓名为可选备注，不参与匹配，不强制查询官方目录或填写技术三元组
- [x] 管理员能查看当前名单、添加和移除记录，不伪报成功：重复添加幂等并返回原记录；`expectedRevision` 过期返回 409 冲突
- [x] 真实持久存储：新表 `phone_allowlist` + `phone_allowlist_state`（`db/mysql/agent-allowlist.sql`，经共享 StorageSchemaInitializer 建表）
- [x] 名单不接入任何业务路径；公司/办公室可用性与任务不受添加/移除影响；页面明确提示"暂未接入业务"
- [x] 添加、移除记录管理审计事件（`PHONE_ALLOWLIST_ADDED` / `PHONE_ALLOWLIST_REMOVED`，tenant=`ADMIN`，载荷含 mobileNumber/name/actor/revision/时间）；仅真实变更产生事件；不制造业务限制/拒绝事件
- [x] 页面包含加载、空、校验、冲突和失败状态，不用假成功提示；冲突后自动刷新快照
- [x] 移除仅删名单记录，不取消任务、不改调度；历史表与历史数据保留
