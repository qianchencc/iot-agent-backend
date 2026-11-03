# MySQL Pi Session：预期效果与真实验收

口径：2026-11-03。用户明确不导入旧会话，直接切换。约20条灵活代表路径，不要求说明书149条全过；数量放宽不代表已知错误可以标绿。测试、SQL、HTTP状态、NDJSON和进程启动均不是业务验收。

## 当前定版验收与隔离更正

- 本轮原工作区`iot-agent-backend-mysql-final`及分支`refactor/pi-session-mysql-final`的实现、票据和证据已合入主工作区`iot-agent-backend`的`main`，原工作树已删除。本轮私有归档及清理时创建的两个临时checkpoint已依用户要求删除，不保留该归档备份；13215/13235冻结进程从主worktree专用`var/mysql-final-preview/`运行，不覆盖根配置或其他实例。
- 冻结窗口最初虽然名为3215，编译配置实际指向3217。U14–U21不能验收新发布Java20，原程序字节已另存。之后纠正同一9444窗口路由，完成下列真实样本；最终窗口名为`MySQL Final - Java 13215`，Agent/视频API均指向13215。旧3215又被另一实例占用，本轮未终止它。
- 正确路由后，U23真实恢复两楼层旧报告合计441.56/461.29及模拟补数、采样边界；U24完整覆盖156台照明，54开、82关、20未知。之后U25因另一Java实例正使用同一Company会话而被writer保护拒绝；不是伪装查询成功。
- 为避免同账号会话互相干扰，最终环境使用独立空库`iot_agent_mysql_final`和Redis DB2，仅复制加密的模型管理配置，不复制旧会话、任务、报告或身份；原库及另一实例不动。以下F系列在此空库从真实界面重新建立业务事实。

|ID|当前结果|截图|
|---|---|---|
|F01|真实Office当前区域4盏：2开、2关、0未知；空会话开始，无控制|[状态](evidence/F01-office-current-lighting/03-result.png)|
|F02|承接刚才已关的两盏，正确列出真实名称；无新操作|[追问](evidence/F02-office-followup/03-result.png)|
|F03|打开两盏已关灯的待确认草案：4发现、2选择执行、2其他未选；未点击执行|[草案](evidence/F03-office-two-light-draft/03-result.png)|
|F04|页面重开后同一草案与两盏选择保留，仍待人工确认|[重开](evidence/F04-office-draft-reopen/03-reopened-draft.png)|
|F05|两盏已关灯再次关闭返回无需操作，0选择、执行按钮禁用；未下发|[无需操作](evidence/F05-office-no-change/03-result.png)|
|F06|原先因Office误用Company范围契约失败；仅修改Java后，v21真实生成11月4日09:00、全部4盏照明的单次待确认单，保留当前已关闭成员；Node未修改，未点击创建任务|[修复后](evidence/F06-office-once-task-proposal/java21/03-result.png)、[原失败](evidence/F06-office-once-task-proposal/03-result.png)|
|F07|同一Office任务确认单页面重开后仍为待确认，原时间与4盏目标保留；未创建|[重开](evidence/F07-office-proposal-reopen/03-pending-proposal-reopened.png)|
|F08|Java22已修复显式公司范围被当作空查询拒绝的问题；真实覆盖156台照明，53开、83关、20未知，不把未知记为关闭；原失败保留|[修复后](evidence/F08-company-all-lighting/java22/03-result.png)、[原失败](evidence/F08-company-all-lighting/03-result.png)|
|F09|Java23真实返回27楼空调固定候选26台，与正文26台一致；实际名称列表可继续选择单台。不再用宽目录49台中的模型自行清点作为完成证据|[固定范围与数量](evidence/F09-company-ac-discovery/java23/04-fixed-scope-summary.png)|
|F10|Office确认单自然超时后重新进入，权威卡显示已过期、不再提供创建按钮，未伪造时间或改库|[过期](evidence/F10-office-expired-proposal/03-expired-on-reopen.png)|
|F11|在新独立库生成26楼/27楼上周报告：441.56/461.29度，分别给出环比与模拟补数、采样覆盖、小时负荷缺数限制|[生成结果](evidence/F11-company-two-floor-reports/03-result.png)|
|F12|用户明确选择列表第一台，真实读取关机、制冷、24℃及16–30℃可控范围，未控制|[状态与能力](evidence/F12-company-ac-state-capability/03-result.png)|
|F13|真实生成仅把24℃设为26℃的单台草案，未附加开机、模式或风速更改，未执行|[参数草案](evidence/F13-company-ac-parameter-draft/03-result.png)|
|F14|Java23下单次只读确认单与正文一致：11月4日10:00检查一次，不开机、不调参；condition仅评估，不含控制。未创建任务|[修复后](evidence/F14-company-once-readonly-proposal/java23/03-result.png)、[原解释错误](evidence/F14-company-once-readonly-proposal/03-result.png)|
|F15|替换只读目录中的Node进程后，页面重开并追问，保留已选空调原状态、两报告合计与模拟补数限制，无重新生成或副作用重发|[恢复追问](evidence/F15-node-restart-business-recall/03-result.png)|
|F16|从真实已保存报告卡打开26楼报告，加载完成后显示441.56度、22台电表和模拟补数提示；不把骨架屏算通过|[实际预览](evidence/F16-saved-report-preview/04-loaded-report.png)|
|F17|真实燃气历史台账读取，说明仅取得20条、覆盖未完整、历史名称与当前目录冲突，不冒充已恢复|[台账](evidence/F17-company-gas-ledger/03-result.png)|
|F18|真实点击“引用本报告”后追问，原报告限制保留，明确不能作为真实运行或节能证据|[引用追问](evidence/F18-report-reference-followup/03-result.png)|
|F19|承接第一条历史报警，分别说明当时发生/上报状态与当前未知，未通知或创建任务|[恢复边界](evidence/F19-historical-alert-recovery-boundary/03-result.png)|
|F20|真实读取35台授权摄像头和位置分组，展示实际名称；不自动播放、不控制|[摄像头](evidence/F20-company-video-discovery/03-result.png)|
|F21|用户明确选择第一台后，生成11月1日09:00–10:00录像卡；未播放，明确卡片不证明录像存在或完整|[录像卡](evidence/F21-video-recording-card/03-result.png)|

## 历史真实界面路径（不等于当前定版通过）

|ID|业务结果与边界|证据|
|---|---|---|
|U01|新MySQL Office会话真实查询当前区域照明：当时3盏、2开1关；不继承未导入旧会话，不执行控制|[结果](evidence/U01/03-result.png)|
|U02|Company保留两份周报，切到Office是独立设备对话；Office照明提案未进入Company历史。基本来源切换有结果；保留Company任务确认卡的生命周期仍单独核实|[Company历史](evidence/U02/final-v17/02-company-history.png)、[Office查询](evidence/U10/03-result.png)|
|U03|替换为无本地会话卷、应用目录只读的新Node后，追问“刚才关着的那盏”，正确恢复原清单并重新读取设备状态|[结果](evidence/U03/03-result.png)|
|U04|真实关闭照明提案最初误报候选过期；已修正目录版本与聚焦范围混用。之后3成员、2待操作、1排除，未执行|[修复后提案](evidence/U04/fixed-v13/03-result.png)|
|U04-R|草案重开后重核最初出现revision stale；已修正回放先物化过期状态。重核时真实设备已满足目标，返回无需操作而非假称已下发|[重核结果](evidence/U04/fixed-v14/02-new-confirmation.png)|
|U05|首次模型把一次性意图误解为每天重复；真实放弃后明确单次日期，形成一次性43电表任务确认卡。未点击创建；重开保持还需复核|[单次提案](evidence/U05/03-result.png)、[放弃错误周期卡](evidence/U05/04-discarded-recurring.png)|
|U06|真实授权上游报告可生成、预览、引用；数据含客户模拟补数，只证明业务管线与已保存事实，不证明真实节能|[报告预览](evidence/U06/final-v14/01-report-preview.png)|
|U06-P|曾出现预览提示模拟补数、助手却声称真实数据。Java报告读取与引用上下文现显式传递证据说明，放在大报告截断之前；v16引用追问明确不能作为真实运行或节能证据|[修复后引用解读](evidence/U06/quoted-provenance-v16/03-result.png)|
|O01|真实管理页面显示Java/MySQL权威状态；不再展示SQLite文件运维按钮|[管理界面](evidence/O01/mysql-health.png)|
|O02|完整MySQL备份恢复到独立库，启动同一v17实例，DevTools重开原对话并继续给出两楼层原数值和模拟补数限制；演练后已切回原隔离库，未覆盖在线库|[恢复后业务结果](evidence/O02/integrated-v17/03-result.png)|
|U06-G|v17生成报告与读取共用证据投影；新生成报告的解读明确模拟补数限制，保留26楼441.56度、27楼461.29度|[生成修复后](evidence/U06/generated-provenance-v17/03-result.png)|
|U07|真实只读查询在途SIGKILL Node，页面明确Engine不可用；更换Node后继续可调用Java工具，但全公司156照明的状态读取失败，未算完整查询通过|[中断](evidence/U07/03-interrupted.png)、[失败终态](evidence/U07/continued/04-terminal.png)|
|U08|独立Redis停止期间，真实对话仍能继续读取已提交周报上下文，保留441.56/461.29及模拟补数限制；随后Redis已恢复|[Redis停止时结果](evidence/U08/03-result.png)|
|U09|Office当前选区空调检索返回0，明确未取得开关、模式、温度，不把低相关候选强行纳入；仅记录范围/空结果行为，不当作空调完整能力通过|[空结果](evidence/U09/03-result.png)|
|U10|新Node恢复后Office真实照明查询：3盏当前全部关闭，带观测时间，未控制|[当前状态](evidence/U10/03-result.png)|
|U11|当前3盏关闭目标提案：3成员、0待操作、3无需操作，执行按钮禁用；未下发|[无需操作提案](evidence/U11/03-result.png)|
|U12/U13|公司一次性43电表提案与后续说明有结果；重开看到原卡已取消，不能把模型的“待确认”措辞当作权威。后来另出现非本轮发起的公司照明提案，本轮未确认|`evidence/U12/`、`evidence/U13/`|
|U14/U15|已另开冻结构建9444，真实查询3灯状态、准备未来单次3灯确认卡（保留目前已关闭设备），均未控制/创建。但蓝牙权限弹层仍遮挡，待人工拒绝后补无遮挡重开证据；不标最终通过|[查询](evidence/U14/03-result.png)、[确认卡](evidence/U15/04-confirmation-card.png)|

以上为历史路径，不与F系列重复计数，也不挪作当前同版验收；原业务恢复父票不因本次存储切换自动关闭。

## 当前已知问题 / 未验收

- 本轮F01–F21共21条代表业务路径已有实际界面证据，分别来自Java20–23迭代构建，不声称均在同一最终版本从头重跑，更不声称149条全部验收。最终Java24仅进一步补齐MySQL表审计字段并安全升级既有表；保留业务事实的工程诊断不冒充前端验收。
- 未抽取的实际任务创建/调度、生产控制、通知发送、PDF/表格下载、实际视频播放、全部安全/环境组合等不自动记为已验收；遵守用户不创建任务/不执行提案的边界。
- 六小时无活动会话按既有策略过期，不承诺永久恢复；不导入旧SQLite会话。新库中的会话与业务事实以本轮真实操作产生。
- 最终改至专用13215/13235端口后，蓝牙权限弹层再次出现。F22重开截图及最后一次实际观察仍被遮挡，**不计通过**；需在该窗口真实点击“拒绝”后补无遮挡结果。SDK取消无效、macOS辅助访问被拒绝，未裁图或伪造关闭弹层。
- 已隔离另一运行占用的会话库和服务端口；历史构建、失败及成功样本分别留档。唯一一次最终内联Standards/Spec审查见[审查记录](final-review.md)，构建及范围见[最终记录](evidence/final-validation-20261103.json)。精确崩溃点的全套前端证据、最终同版重放和未抽取场景仍未验收。

## 业务验收操作

DevTools9444使用`MySQL Final - Java 13215`，项目目录仍为`iot-agent-backend-business/var/business-preview/mp-mysql-v17-node10`，目录名不是当前版本号。冻结Java13215、Node13235从主worktree的`var/mysql-final-preview/`运行；上游3245、独立库3338/`iot_agent_mysql_final`和Redis6398/2不变。主线3217/3237及另一3215/3235、9441和缓存3207/3223/9431不操作。迁出进程不将原21条证据升级为主线最新构建全量通过；生产Runtime只读、不运行。

1. 通过真实客户登录、园区和Agent入口操作；不注入token、setData、假工具响应或伪造卡片。
2. Office/Company新会话查询、切换、引用、草案重核、任务提案、报告预览/解读均截图。任务不创建、控制不确认、通知不发送。
3. 替换本隔离Node/Java或恢复到独立MySQL后，在保留期内从同一账号和原业务对象继续。中断请求只说明已知/未知结果，不自动重放工具副作用。
4. 每个目录保留失败与修复后证据，记录构建和操作结果；截图未展示的行为不标通过。报告数据真实性边界必须与UI一致。

## 工程检查（不是验收）

- 最终代码重新运行未修改的官方Pi0.84.4 SessionBackendConformance：30/30，经Node→Java→独立真实MySQL；工程夹具已停止，不计业务通过数。
- 最终Java完整`mvn -q package`成功：267 tests、0失败、0错误、5跳过；Node通用回归25通过且构建成功。最终审查中另将工程夹具改为有界线程池并成功重新编译、重跑官方一致性验证。
- MySQL四张Pi表补齐自增id及create_time/update_time；对既有表非破坏升级，前后会话/报告/任务/会话分片数量均为2/2/0/217。这只证明升级保留，不证明新业务验收。
- S01–S04：公开Pi语义、writer fencing、版本/幂等提交与响应丢失的工程验证；不计入业务通过数。
- S05–S06：中断/Redis故障的持久性与安全工程检查和真实界面结果分别记录。
- S07旧会话导入已由用户取消；替换为一次性cutover marker、旧公开会话结束与新会话重启保留。旧SQLite文件留作离线历史，不导入、不删除、不自动回退。
- S08应用目录只读且无SQLite卷Node已用于U03真实追问。

## 终点

Java可维护全部业务定义、授权、执行规则、任务与报告；Node是可替换的通用Pi执行服务，无业务分支和数据库凭据。MySQL是业务及Pi上下文唯一持久权威，Redis不承担唯一事实。达到选定真实业务路径、恢复演练与截图要求，并处理最终审查问题后才闭票；未抽取场景仍明确标未验收。
