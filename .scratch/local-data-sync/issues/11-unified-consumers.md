# 11: dashboard/Agent 跨域引用/区域时间轴日历未读的全链统一本地消费

**What to build:** 所有跨领域消费入口统一走各领域本地读：dashboard 各段、Agent 每轮前置与全部只读工具、跨域引用追问解析、区域与设备时间轴/日历、未读聚合、region seen、自动化摘要与创建人显示名。各领域基础读取已由 02—10 迁移完成，本票收口消费端：清除 HTTP 与 Agent 侧绕过本地读的分支，不是先前票半成事的补丁借口；页面与 Agent 同问必须同口径。本地未覆盖窗口返回 preparing/UNAVAILABLE 并登记需求，不误报 EMPTY；明确拒绝/授权过期不返旧值。

**Blocked by:** [04-state-drafts.md](04-state-drafts.md)、[05-energy-history.md](05-energy-history.md)、[06-environment-history.md](06-environment-history.md)、[07-safety-ledger.md](07-safety-ledger.md)、[08-reports.md](08-reports.md)、[09-tasks.md](09-tasks.md)、[10-video-metadata.md](10-video-metadata.md)；任一未完成不得开工。

**Status:** ready-for-agent
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- dashboard：分区取数全部本地——目录（02）、环境当前（06）、能耗状态（04）、告警（07 本地事件）、自动化摘要（本地任务事实+本地授权）；组合语义保持（summary 分段构成等现状不静默改变）；不新增遮住底层回源的总览缓存。
- Agent 统一：每轮运行前置的区域/目录读取、全部只读工具（观察、状态、能力、告警、能耗、视频准备、任务/计划）、Agent 检查点桥的证据查询走本地读入口；Agent 观察工具不再在调用线程直读上游；卡片/事件投影与模型摘要语义保持，只换取数来源。
- 引用追问：customer_alert/task/task_plan/safety_event/environment_window/report/device/energy_report 各类型解析到本地真实对象；energy_report 引用复核改本地有效授权；历史引用（含报告引用、候选短句柄 sN/dN_k）使用时复核当前权限；跨 source/租户/会话引用被拒；客户端 label/标题不作权威事实。
- HTTP 旁路收口：/tools/find_device_candidates、/tools/read_device_states、candidateSets 绑定状态分支、capabilities 检查、region seen 前置授权——全部改本地入口，不再由参数形状决定是否回源。
- 区域消费：REGION_TIMELINE/CALENDAR、DEVICE_TIMELINE/CALENDAR 用本地任务事实+本地授权裁剪；未读聚合（region_seen 水位×异常任务×当前授权）本地；REGION_SEEN 仍是用户认知事实写入，不是同步采集，不强套快照语义。
- 普通资料：创建人显示名、自动化摘要来自本地事实/合法同步资料，缺失明确显示缺失，不以 SSO 资料调用伪装每次身份核验或普通读取。
- 语义校准：本票消费端失去用途的回源分支与旧注释随切换清理，13检查全局遗漏；本地副本是普通读取依据，不是即时物理状态或执行结果权威。

## 非目标
- 不做各领域同步/schema或跨领域恢复调度（12）；全局出口约束归13。本票清理自身 obsolete 接线，不保留无人调用的旧路径等待下一票。

## 行为验收
- [ ] 隔离程序阻断客户数据读取出口后，dashboard各段、Agent普通工具、上述全部引用类型、时间轴/日历/未读及本地认知写入按原契约工作；网络计数0，身份/开流/真实执行例外另计。
- [ ] 同一问题页面与 Agent 结果同口径：同设备集合、区间、单位、缺数解释。
- [ ] 参数形状（tools 直连、candidateSets 分支）不再改变取数路径；绕过检测（E15）能暴露任何残留。
- [ ] 重启后合法引用仍解析原事实并遵守当前权限；新会话只接受原契约允许跨会话的引用，候选短句柄不能跨会话复用，跨租户/来源引用被拒。
- [ ] 未读不伪零：异常任务更新晚于已读水位即未读；部分授权按当前授权裁剪；明确拒绝不返旧可见性。
- [ ] 本地未覆盖→preparing/UNAVAILABLE 并登记，不误报 EMPTY/NOT_FOUND；MySQL 不可用→UNAVAILABLE；Redis 清空后全链一致。
- [ ] dashboard、Agent 追问、时间轴/日历、未读的开发者工具 UI 截图验证（含 preparing/错误态）。

## 证据与既有公开接口
- 公开符号：ObservationAdapter、IotAdapterFactory（agentReads 组合入口）、ReadSnapshots、TaskProjectionStore、TaskFacade（scope/loadScope）、RegionSeenStore、AutomationSummaryReader、ConversationFacade、AgentRunPolicyProvider、AgentToolBridge、ReportService。
- 既有测试接口：CompanyOfficeObservationAdapterContractTest、AgentToolBridgeContractTest、AgentBusinessDefinitionContractTest、ConversationFacadeContractTest、RegionAttentionHttpIntegrationTest、RegionAttentionReadIntegrationTest、AutomationSummaryReaderContractTest；测试经包根入口断言，不从 internal 导入，不开新测试框架。
