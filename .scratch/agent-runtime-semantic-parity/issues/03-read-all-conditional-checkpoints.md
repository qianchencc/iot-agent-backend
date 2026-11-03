# 03: 全授权只读与条件检查通过服务器绑定完整成员

**What to build:** 用户安排全范围只读简报、报告或空调条件检查时，由Java根据显式意图绑定完整授权固定成员，按原时间/条件契约生成真实待确认单，不要求模型逐台搬运设备或靠省略字段猜全部。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

## 当前问题与对照

原Runtime支持显式read.all、服务器按观察类型展开及单一只读阶段形状；Java对外read契约和归一化需逐项证明等价，不能因为可接收任意JSON或已有检查点类就称能力完整。[核查F2/F3](../findings.md)提供源语义入口。本票不以未经验证的推测宣布所有条件任务都已坏。

## 实现约束

- Java定义清晰的只读工具schema，显式全范围、已有精确引用与参数缺失分开处理；省略deviceIds不默认全部。显式read.all允许服务端解析完整固定成员，不强迫先查多个候选集或受展示/50条限制。
- 观察类型、范围、报告期间与执行时刻分开存为Business Fact；安全昨日/上周按检查点计划日期的上海自然窗口锚定，报告重试保留原期间，不按重试时钟漂移。
- 条件任务保持单次检查与窗口检查区别；只给窗口缺频率先澄清。首检查点为起点后一个频率、含终点，当前窗口保留剩余未来点；持续/全天未给窗口按既有业务规则解释完整日窗口。
- 控制targets与只读read不能同阶段混用；报告不能接收模型自拟统计区块。去重、权限、绑定和缺数语义使用既有Java深模块。
- 业务规则不迁回Node，不改另一线程的会话存储协议；共享任务模块由同一集成人串行处理，独立依赖不等于可同时覆盖文件。

## 验收条件

- [x] 大范围显式只读覆盖完整固定集合，后续新增设备不自动加入；精确选择保留原小集合；省略成员、跨来源和范围缺失不会静默成为全部。（源码+TaskRangeTargetContractTest既有与本轮新增回归；真实UI走查另见下）
- [x] 缺频率只产生澄清，不保存误导待确认单；确认频率后卡片的窗口、首末检查点、次数与服务器事实一致。（AC_CADENCE_REQUIRED不落库+展开端点/剩余点回归）
- [x] 用确定时钟的业务回归覆盖延迟执行、跨日重试、自然日/周右开边界、当天剩余点与次日完整窗口；不是精确prompt措辞测试。（本轮补齐previous_week右开与窗口/宽限边界，跨日重试锚定沿用ReportFactProducerContractTest）
- [ ] 独立开发者工具走一条真实全范围只读或条件巡检提案，看到完整范围、期间、条件和检查点；不得以HTTP结果或任意JSON落库替代。
- [ ] 真实路径停在提案，不建生产任务、不产生现场调温或通知。检查点执行/恢复反例在隔离源验证，明确不代表现场执行。

## 2026-11-03 会话验收中发现的下层读取障碍

- 真实Company全照明查询定位到156台，但状态读取反复失败，见[失败终态](../../pi-session-mysql/evidence/U07/continued/04-terminal.png)。不能以候选完整代替状态完整，也不能把前50条当全部。
- 对照原Runtime `apps/runtime/src/iot/adapter.ts:275-342`：照明/监控按kind+smartAreaCode只读一次区域快照，并与空调共用三路队列。Java原实现却逐设备串行重复拉区域页，50台会放大为50次读取，且同区域不同设备可能混用不同时刻快照。
- `WebClientIotAdapter.readAuthorizedCompanyStates`现按类型/区域分组、三路并发，直接用原行索引回填该区域设备；保留现有鉴权/失败语义，不通过延长超时、缩小范围或假称关闭换绿。新增公开入口快照一致性回归已失败前/通过后。
- 本修复只解决读取批次与一致性，不等于本票完整显式all/条件任务能力已验收。新隔离版本仍须走同一真实全范围问题并留截图；会话线程的冻结窗口9444当前有需要人工拒绝的蓝牙弹层，保持未验收。

## 交付

evidence/SEM-READ-ALL/存同构建截图、范围/时间依据和工程回归；回链原07/10/11/12票，复用已有报表、检查点和结果投影修复，不重写缓存或MySQL会话迁移。

## 2026-11-03 03票源码对齐与工程回归（本轮改动）

源码（对照原Runtime行号：pi-runner.ts:417-435/505/930-1013、app.ts:3093-3110、tasks/store.ts:503-534、tasks/ac-inspection.ts:83-154、tasks/AGENTS.md第10/12条）：

- `TaskTargetResolver.readAll`：read存在时必须显式非空deviceIds/devices或read.all=true；根级reportRequest不再让无成员只读检查静默通过——该形状此前会在RealTaskExecutionGateway被报表捷径整体跳过（COMPLETED而read.agent从未执行），现为提案期INVALID_TASK_SCOPE。
- `TaskTargetResolver.targets`：新增`target.excludeCandidateSetIds`（等价原Runtime排除候选集）：逐id经`candidate()`重验会话/租户/owner/来源绑定，`resolutionMode`必须exact、成员完整非空、kind一致、全部成员属于本target包含成员；与excludeTexts并集去重，excludedLabels取record.query.scopeText/scopeTerms；引用绝不扩大选择。schema字段（AgentToolCatalog.companyControlTarget）由02父任务补。
- `TaskProposalNormalizer`：无区间任务恢复原十分钟规则（首阶段at >= now-10min仍可提案，store.ts:507-508/pi-runner.ts:954）；`expandAcCheckpoints`补单次vs窗口区分——窗口缺频率仍AC_CADENCE_REQUIRED（澄清、不落库）；无窗口时空调检查必须恰好一个阶段（手写检查点列表与"检查+控制"混合阶段拒绝）；周期+空调必须有窗口与频率（AC_WINDOW_REQUIRED）；删除startsAt==endsAt死分支（normalizeInterval已拒绝相等）。
- `TaskFacade.validateConfirmTime`：非区间确认同样保留十分钟宽限；区间确认维持"仍有未来检查点即可"（与剩余点语义一致，Main确认后修改）。

已核等价、未改动：read.all显式全集/精确选择/缺省三分（TaskRangeTargetContractTest既有）；(起点,终点]首点=起点+频率、终点含入（expandAcCheckpoints）；read.agent固定电表/期间锚定dueAt/错误不伪成功/重复调用指纹（DefaultAgentCheckpointBridge+AgentCheckpointContractTest既有）；报表previous期间跨日/跨月重试不漂移（DefaultReportFactProducer→ReportEvidenceService.build(anchor=checkpointAt)+ReportFactProducerContractTest既有）；当天剩余点dropElapsedIntervalPhases、次日完整窗口（preserveCurrentAcWindow完整模板+JdbcTaskRecurrenceMaterializer recovery MISSED/PENDING既有）；窗口结束边界检查点仍可下发（JdbcTaskDispatchGuard `p.due_at=windowEnd`分支）。

新增回归：TaskProposalNormalizationContractTest 8项（终点含入/非整除截断、缺频率澄清且不落库、单次保持conditional、手写列表与混合阶段拒绝、周期需窗口频率、活动窗口剩余点/全过TASK_TIME_PASSED、周期保留完整模板、十分钟宽限两侧边界）；TaskRangeTargetContractTest 3项（excludeCandidateSetIds正/反例：越包含范围与非exact拒绝；read缺成员+reportRequest拒绝）并把fake适配DeviceCandidateRecord末尾resolutionMode（exact）；RealTaskExecutionGatewayBehaviorTest 1项（previous_week=上一自然周右开、延迟多日执行仍锚定计划日）。

有意差异：Java允许weekly/monthly的空调窗口周期（原Runtime仅daily/weekdays，pi-runner.ts:1008），等价依据是Java按anchor日差整体平移完整模板，任意频率均保持完整日窗口，无业务收益不收窄。

未验收保留：真实开发者工具全范围只读/条件巡检提案与停在提案的截图仍待父任务集成后执行；冻结窗口蓝牙弹层问题不变；本票不运行任何检查命令，回归由父任务统一执行。

## 2026-11-03 终审补齐：报告固定成员贯通与控制目标预检（第二轮）

- `TaskTargetResolver`（对照 pi-runner.ts:995-1005、company-control-task.ts:220-270）：
   - 精确read成员+根级energy报表：核验成员写入reportRequest固定scope（selection+原成员有序集合）；显式scope集合不一致（含level=all对子集、跨集合selection）一律拒绝，不再让ReportEvidenceService在生成期默认"全部当前授权"（会把未来新增电表算入）。read.all+level=all仅在成员为全部energy家族时接受并改写为固定selection。
   - 安全报表固定成员贯通：read成员（精确或read.all展开）核验kind=safety后写入服务器专属`reportRequest.fixedDeviceIds`；模型自带该字段在materialize入口直接拒绝（服务器专属事实，不可伪造）。`TaskProposalNormalizer.validateReportRequest`仅对safety放行该键并校验形状。
   - targets控制目标预检：展开成员逐台`observationOnly`拒绝；经`IotAdapter.inspectDeviceCapabilities`核验kind与指令能力（照明closeOrOpen受支持、空调parameters存在），无能力即拒绝提案；`readDeviceStates`读取当前状态仅作记录——phase.preflight{observedAt,targetCount,alreadySatisfiedCount,unreadableCount,needsDispatchCount}，已满足/不可读成员保留不删（原Runtime语义），adapter级读取失败拒绝提案。
- `ReportEvidenceService.safety`：接受`fixedDeviceIds`（须⊆当前授权safety设备，伪造只能收窄不能扩大），台账items按固定成员过滤，coverage/requirements记录fixedDeviceCount；期间锚定与缺数partial语义不变。
- 新增回归：TaskRangeTargetContractTest +6（energy固定scope钉住/同集等价规范化、冲突拒绝（窄selection与level=all）、read.all改写固定selection、safety固定成员精确/read.all/伪造拒绝、预检计数不删成员、无能力拒绝）；RangeAdapter补readDeviceStates/inspectDeviceCapabilities（含incapable/state/unreadable开关）；ReportGenerationContractTest +1（安全台账按固定成员过滤计数）。
- 边界与未做：read.agent.spec检查点已有固定电表+锚定期间（第一轮已核），本轮不改；reportRequest与read.agent.spec同任务并存会各产一份工件（沿既有行为，未在本次范围）；phase.preflight新增字段对confirm/plan-change/materializer透传无白名单冲突（validatePlanStructure只查at/actions）。本轮全部未运行构建/测试，由父任务集成后统一验证。
