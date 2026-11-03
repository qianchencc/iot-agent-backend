# Runtime 业务语义迁移：已确认事实与待核验项

日期：2026-11-02。类型：只读核查记录，不是实现完成或业务验收报告。

## F1：公司全灯任务被缩成两路（已确认，未修复）

用户原话「3分钟后把公司的灯全关了」。真实轮次 `49a4c820-544c-4a5c-900c-43819f711e3b`，开始于 2026-11-02 14:55:59（上海时间）。隔离 MySQL 中记录：

|事实|核查结果|
|---|---|
|来源|company|
|26楼候选集|`93f8171e-04b5-4cfb-9410-2a4a46ba7300`；totalMatches=81；members=81；complete=false|
|27楼候选集|`913b2fe4-c922-405c-a5c1-33c0e68abd2a`；totalMatches=75；members=75；complete=false|
|提案引用|上述两个候选集|
|提案标签|全楼照明关闭|
|提案首阶段动作数|2|
|首阶段时刻|2026-11-02T06:59:00Z，即上海14:59|
|核查时状态|PENDING_CONFIRMATION；本次核查没有创建任务或下发控制|

候选集 complete=false 与持有完整 members 并存，必须区分「模型展示窗口不完整」和「服务端真实成员是否完整」。不能一律放开不完整标记；必须核验集合成员、总数、授权和来源语义。

真实工具轨迹：4次 find_device_candidates；第1次 propose_task 失败；第2次成功，落盘 task.proposed 并进入 run.awaiting_confirmation。首个工具开始14:56:11，成功提案14:57:25，与界面约87秒一致。轨迹证明检索与重试发生，尚不足以把所有耗时归因于某一个模块。

前端 TaskProposalCard 默认每阶段展示4行，有剩余时显示「还有N项·展开」。本案落库只有2动作，因而不是UI把156路折叠成两路。相对3分钟对应14:59未发现独立时间计算错误；不以本例另立时间bug。

## F2：全范围工具契约及强约束缺失（代码与F1相互印证）

- 原 Runtime 公司任务目标：`apps/runtime/src/agent/company-phase-task-parameters.ts`，包含 allAuthorized、scopeTexts/excludeTexts、selection、每阶段独立 targets；服务器展开、核验与预检，不由模型逐台抄写。
- 原 Runtime 另一候选集任务路径：`apps/runtime/src/agent/pi-runner.ts` 的 expandLightingScope / proposeTaskTool，区分完整集合、局部展示和逐设备选择，展开照明范围后核验不完整候选。
- Java：`AgentToolCatalog.taskPhase` 主要暴露 at/actions/read，actions单次最多50；没有等价的公司targets/全授权范围表达。`AgentBusinessToolDispatcher.proposeTask` 直接传入 TaskFacade，`TaskProposalNormalizer` 的 candidateSetIds 检查只拒绝完全没有具体设备引用，授权校验针对已列设备，不证明已列动作覆盖用户声明的整个目标。
- 结论：有权操作两台不等于正确实现全公司目标；禁止只调大actions上限、给模型追加「必须全部」或前端补标题来结案。

## F3：业务prompt被压缩（文本差异已确认，逐项行为等价待完成）

原 Runtime `agentSystemPrompt` 组合来源指引、公司阶段目标、发现、任务、设备指代、失败恢复等规则；Java `AgentBusinessRunPolicyProvider` 有独立重写的来源/公共/任务指引，但未保留公司阶段目标整套契约，多个发现、排除、能耗解释及故障恢复细则也被压缩。并非每个文字差异都等于功能缺陷；后续必须逐项映射规则和可观察反例，不能宣称「大部分/所有prompt错误」。

## F4：调用守卫与收尾等价性不足（源码差异，部署版本需冻结）

原 Runtime Agent 包含 beforeToolCall / afterToolCall / prepareNextTurnWithContext：重复调用指纹、相同错误限制、无进展收口、确定提案后的调用阻断及保留候选事实的无工具收尾。所查通用sidecar实现主要具有轮次、调用预算和Java halt；所查Java Agent模块未找到完整等价机制。不能因此断言Pi底层循环已被替换；两者均使用Pi。

本工作区sidecar源码/产物正被另一线程修改以推进MySQL会话迁移，源码当前值不自动代表运行中的3235-v5。此项实施前须冻结原Runtime提交、Java源码/包和sidecar提交/产物，分别记录已运行事实与源码差异，再复现和补齐。

## F5：此前修复的状态，不归零、不夸大

- 电表卡片用户已确认可用；普通区间采样边界、草案恢复当前revision/保留错误、报告模块注册、PDF中文字体、导出采样依据、巡检结果投影已有修复。报告真实前端与下载证据见[既有报告记录](../agent-business-recovery/evidence/COMP-ENE-04/README.md)。
- 这些修复不证明整个Business Agent Definition、所有工具契约和调用策略已与原Runtime等价。
- 核查时3215运行的是业务修复隔离预览v11，不是最新工作区全源码构建，也未吸收另一线程的Pi Session MySQL迁移。最终票据必须交付可重建的完整集成版本，不能继续把拼接预览当最终交付。

## 不变的架构依据

[ADR0003](../../docs/adr/0003-java-api-node-agent-engine.md)保留Pi及Java/Node双运行时；[ADR0004](../../docs/adr/0004-java-business-definition-generic-agent-engine.md)明确业务定义、schema、prompt、上下文、权限、事实及公开投影归Java；[ADR0005](../../docs/adr/0005-java-owned-mysql-pi-session.md)把会话持久化方向交给Java/MySQL，不把物联业务放回Node，也不要求Java重写Pi。

本记录仅核查，不启动子代理，不修改/运行生产Runtime，不执行设备控制、创建任务、发送通知或重放有副作用的工具。
