# 04: Java业务指引、引用上下文和事实表达恢复等价

**What to build:** 同样的统计、比较、报告、告警/环境/任务/报告引用问题，在Java业务定义下沿原Runtime规则选工具、保留对象与窗口、输出既有组件并如实解释事实，而不是因压缩prompt而改口径或让模型补算。

**Blocked by:** None (can start immediately).

**Status:** in-progress（源码与语义映射完成；真实路径取证与集成构建未做，不宣称验收）

## 当前问题

[核查F3](../findings.md)确认原Runtime来源指引、发现、任务、指代、失败恢复等组合被Java独立简化；文本不相同不自动等于错误，但必须证明行为约束被保留。电表卡片与报告导出已有定向修复，不归零重写；它们也不证明其他工具选择和上下文等价。

## 实现约束

- 在Java Business Agent Definition保留单一权威的来源指引、工具用途/schema及经核验上下文；按原Runtime语义逐规则映射「保留/恢复/有意差异及依据」，不追求逐字复制，不把规则重新放到Node。
- 只问统计/分析且无比较关系时先取统计事实；明确两期关系才对比，明确报告交付才生成附件。保持楼层与子区域、时间/区域比较维度和实际采样覆盖；不靠模型用原始示数自行宣称服务端统计。
- 原Runtime关于客户模拟补数、陈旧值、未知单位、影子值与历史告警的限制必须进入业务定义和可信结果表达；不由模型把模拟走势当真实节能/故障证据。
- 引用以原业务身份和窗口重核，来源/关注区域/租主隔离；旧确认卡退役与当前已登记任务的上下文不同，不按标题重建对象、不丢失已有已明确选择。
- 多候选监控要先由用户选择，服务器规则阻止自行选一个/逐台生成；已有前端组件承载事实，正文补必要意图/限制，不以Markdown替代缺失事件。
- 保持领域校验在Java模块，prompt不是权限、完整性、幂等或确认的唯一保护。新增业务通过同一Node产物运行。

## 语义映射与源码处置（2026-11-03，源码层）

比对方法：脚本提取原Runtime `agentSystemPrompt` 各组成段与Java `src/main/resources/agent/*.txt` 全文比对。office-instructions、company-instructions、shared-instructions 与原段 `IDENTICAL`；company-task-instructions 前4段与 `companyPhaseTaskPrompt`（apps/runtime/src/agent/company-phase-task-parameters.ts:77-83）逐字一致。逐条处置（原=原Runtime只读对照，行为落在谁处标注归属票）：

|规则族|原Runtime依据|Java现状与处置|
|---|---|---|
|按来源启用工具族|pi-runner.ts:1368-1377：company=观察4+条件告警+安全复盘+环境/计量历史+统计/对比/报告+能力+即时草案+照明范围草案+阶段任务(+closure兼容)+任务管理5件；office=观察4(+条件告警)+能力+两类草案+任务管理5件；report引用=能耗只读6件|等价+有意差异：Java AgentToolCatalog 按来源过滤（office 16件+普查门控的 query_alerts/company 22件），报告引用收窄见下行；差异①propose_lighting_draft 并入 propose_action_draft（01/02域；父任务已补 excludeCandidateSetIds/显式扩展/默认精确 actions 语义）；②夜间收口两工具不迁移（无对应语境，见阶段目标行）；③新增 list_office_regions 只读区域查询替代原 task_regions 上下文注入，另加 read_action_draft/read_action_device_states/read_action_summary 三只读回读工具（对应原Runtime确认后回读事实）；④office 告警普查门控已恢复（见领域保护行）|
|来源指引组合（office/company系统提示+配置追加+reply指引+发现/任务/指代/恢复公共段）|pi-runner.ts:36-75、164-169|保留：资源逐字一致；组合顺序 OFFICE/COMPANY→COMPANY_TASKS(仅company)→配置→reply→SHARED→run_context 等价|
|公司阶段目标契约|company-phase-task-parameters.ts:77-83|前4段保留（逐字）；第5段（continue_company_night_closure 兼容旧共享范围）有意差异：Java目录无夜间收口工具亦无 companyClosureContext，新目标走完整阶段请求（第3段已覆盖），不迁移死语境|
|发现/排除/候选≠选择；任务管理；设备指代承接；失败恢复|deviceDiscoveryPrompt/taskManagementPrompt/deviceReferencePrompt/failureRecoveryPrompt|保留：shared-instructions.txt 逐字一致；read.all/条件检查执行语义归03，运行时守卫归05|
|回复排版与结果沟通|replyLayout/resultCommunication|保留：内嵌 office/company 资源尾部，逐字一致|
|能耗口径（统计vs对比vs报告、夜间/nightBase 不混称、缺测不补零、日历基期、逐段统计不两两比较、模拟补数不作运行/节能证据、小时不足不造负荷形状）|companySystemPrompt|恢复原文：company/office/companyTasks/shared四组指引及全部启用工具描述/schema均从原Runtime单向迁入Java资源，撤回手写改写|
|模拟补数/陈旧值/未知单位/影子值的事实表达|companySystemPrompt + 既有电表卡片修复|ReportService事实保留evidenceNotes；run_context中的`<report_evidence_notes>`先于大报告事实，避免截断丢失来源；稳定指引不再叠加自写规则|
|结构化引用身份/窗口/来源隔离|app.ts:1484-1554 按类型注入核验事实与限制|保留：AgentReferenceResolver 按类型重核真实身份（task/task_plan/safety_event/environment_window/report/energy_report/customer_alert/device），COMPANY_ONLY 隔离，客户端标题/块不信任（既有测试覆盖）；本轮恢复两点见下|
|报告引用工具族|app.ts:1370-1371：report 引用仅开放6能耗只读工具；energy_report（会话内能耗卡）零工具|恢复：report仅开放原6工具；energy_report从同一已鉴权会话的observation事实读取原卡，按原电表身份重核当前授权，并零工具解读；不再把两者统一解释为附件|
|引用解读限制（按类型）|app.ts:1509/1522/1531/1541 各类型 modelContent 限制段|恢复（本轮）：run_context 按引用类型给出 report（不改写原报告/不授予控制任务权限）、safety_event（recordedAt≠报警发生时间、位置未核验、report仅上报标记、无当前态证据）、environment_window（站点采样不归属房间、覆盖不全不补零、单位未确认不判达标、同期间不重复查询）、task/task_plan（修改另提案）、customer_alert（未核验只作转述）|
|safety_event 复盘窗口|safety/events.ts:114：±15分钟且不超过当前时间|恢复（本轮，AgentReferenceResolver）：窗口终点钳制到当前时间；记录时间晚于当前时间按不可核验拒绝，不再输出未来窗口|
|run_context组合|pi-runner.ts:1390-1394|恢复：每轮载入来源授权目录，Java AgentDeviceContext注入完整数量、名称/位置词汇；task_regions来自授权Office区域；保留current_time/current_calendar、退役提案/草案、action_draft_context、task_confirmation_context及原报告事实；不把目录样本或历史时间当本轮全部范围|
|当轮事实的位置与生命周期|pi-runner.ts:1379-1402，历史与当轮runtimeContext分离|恢复：Java将稳定指引与动态`definition.context`分开；Node通过通用`transformContext`把当轮事实放在当前用户输入前，不写历史或摘要。当前时间、范围、引用内容仍全部归Java；新增基础协议不引入Node物联业务分支。实际HTTP模型输入回归覆盖重启后的新上下文替换及旧上下文不泄漏；原话的最终前端结果另记，不以此回归判业务通过。|
|退役卡≠已登记任务|pi-runner.ts:1384-1389|保留+恢复：原有 retiredActionDraftIds/retiredTaskProposalIds 与 run.started 投影 action.resolved/task.resolved(CANCELLED) 不变；本轮补 nextAction 指引（复用原设备证据重新提案/不查询已登记计划修改未确认提案/不把已执行并入新草案）|
|监控多候选必须用户选择|companySystemPrompt 第3段 + pi-runner.ts:1359-1365 工具语义 + 1411-1413 beforeToolCall 阻断|保留（每调用级）：prompt 逐字一致；dispatcher reviewSafetyEvent 仅单候选或显式 cameraDeviceId 生成卡，多候选返回 clarificationRequired 不出卡。run 级阻断（同 run 多候选后禁 review_safety_event/prepare_video_playback 直至用户新 run 选择）已由父任务补齐 AgentRunGuard 守卫与用户新 run 选择回归|
|领域保护（环境/计量/安全只读、来源隔离、门禁单次开门、视频只读、Office不得借账号级观察扩大范围）|office/company prompt + 工具族门控|恢复：两端均在构建run前载入各自授权目录；Office只在目录含safety时开放query_alerts，Company保留原工具族，空范围返回诚实空台账。SafetyLedgerComposer按真实source绑定scope并过滤；游标跨source拒绝，401/403不回退扩大范围；详情及安全复盘仍仅Company。原指引和scope规则留在Java，Node不分类|
|收尾/最终化|prepareNextTurnWithContext closingPrompt|恢复：Java guard拥有错误阈值、新证据及确定结果决策；dispatcher按原语义提供候选集简表和业务closingContext；Node仅把opaque closingContext用于无工具收尾，Java提供modelResult/completion，避免Node分类业务字段|

本轮源码改动：`AgentBusinessRunPolicyProvider`（报告引用工具族收窄、current_calendar、按类型引用指引、退役状态语义、office safety 普查门控）、`AgentReferenceResolver`（窗口钳制+Clock）、`SafetyLedgerComposer`（office 台账来源贯通、scope 来源绑定、事实 source、游标 source 绑定）、`WebClientObservationAdapter`（SAFETY_EVENTS 来源无关化）、`AgentToolCatalog`（仅 query_alerts sources 行 COMPANY→BOTH，其余父任务持有）、`AgentBusinessDefinitionContractTest`（消费者回归：报告引用仅能耗只读工具族；safety_event 窗口终点不晚于当前时间；office 告警按目录普查开放/关闭；已按父任务要求删除 prompt 措辞/拼接断言）、`SafetyLedgerObservationContractTest`（office 台账按自身 scope 过滤+游标跨来源拒绝+office 详情 404）。Node源码/产物零改动。

父任务已并行补齐（记录基线，不混淆改动归属）：awaitingCameraSelection run级守卫及回归；resolutionMode 持久化、expandCandidates 显式扩展/默认精确 actions、excludeCandidateSetIds（02域，AgentToolCatalog/ActionDraftFacade 归父任务）。原 02 exact-selection 展开歧义分析已由父任务落地解决。

## 验收条件

- [ ] 对启用业务指引和工具家族建立语义映射，至少涵盖发现/能力/现态、即时草案、任务/管理、能耗/报告、环境、安全/视频、结构化引用；差异标明证据和所属票，不以名称数量或字符串相等判通过。
- [ ] 消费者回归检验工具选择所依据的业务参数、引用身份/窗口、有效结果投影及授权失败，不断言完整prompt文本、内部拼接顺序或mock回声。
- [ ] 真实开发者工具走一条已有能耗事实/报告引用追问和一条真实可用的告警或环境引用路径：原对象与期间保持，卡片与正文口径一致；不要求为本票重新遍历全部卡片。
- [ ] 模拟补数不被宣称为真实运行或节能证据，真实缺数不补零/不改日期范围；报告附件只在原业务意图下生成。
- [ ] 业务指引与一项业务契约修正在Java生效，Node源码与产物未为该业务发生改变；若基础协议在别票调整，记录明确新基线，不能混淆两种改动。

## 交付

evidence/SEM-FACT-CONTEXT/保存原语义映射、真实引用/结果截图与构建；与原01/05/06/07/08/09/14票回链。用户未授权重新设计卡片或扩建新Agent平台。
