# 09: 任务提案准备/列表详情通知与只读阶段本地化（真实执行与事务生命周期保持）

**What to build:** 任务链路的普通读取全部本地化：提案准备（授权目录、目标解析、能力核验、状态预读）、任务/计划列表详情、通知与未读、区域/设备时间轴日历、照明状态展示、只读阶段（安全台账窗口、AC 检查、Agent 检查点）统一走本地读入口。任务只读阶段是普通消费者、不是同步器：不内联采集、不等待上游、不把定时只读任务当独立数据同步；缺数走与普通查询一致的登记路径。真实控制下发、执行前即时授权/绑定/状态复核、执行回读、事务与恢复生命周期保持真实链路不变。

**Blocked by:** [04-state-drafts.md](04-state-drafts.md)（本地状态/能力）、[06-environment-history.md](06-environment-history.md)（环境读入口）、[08-reports.md](08-reports.md)（本地取证入口）；未完成不得开工。

**Status:** ready-for-agent
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- 授权目录读取：TaskReadCatalog语义下的普通任务授权快照改本地，缺覆盖保持preparing 503，明确拒绝fail closed。管理操作沿既有身份、版本与事务校验；“fresh”不机械等同远程全量扫描，也不借本次本地化削弱最终确认/执行规定的权威复核。
- 提案准备：范围展开、地点、候选/能力/状态预读和指纹消费本地数据；Candidate Set事实与sN/dN_k绑定不变，历史句柄使用时复核当前权限。真正控制确认/执行的安全复核保留既有专门出口，不能误迁入普通缓存。
- 列表/详情/通知/时间轴/日历：既有 MySQL 任务事实读取保持，授权裁剪来源换本地；通知游标、可见性过滤、周期计划通知规则不回退；未读水位语义不变。
- 照明状态展示：DEVICE_STATES 快照 loader 改本地最新状态（04），设备集合与执行时间水位键语义保持。
- 只读阶段：安全台账窗口读本地事件（07）、AC 检查读本地状态（04）、Agent 检查点的取证准备与查询走本地取证（08/05）；只读阶段与普通查询共用入口且同口径；执行身份加密托管与 resume 重绑凭据保持。
- 真实执行例外不变：actions/AC 调温的 dispatch、执行前即时 scope/绑定/状态复核、回读、设备 reservation、取消/人工变更让行、扫描并发与终态事务（通知/审计/报告补投）原样保留真实上游与本地事务边界；本票不以任何方式用本地状态替代执行安全复核。
- 创建人显示名（任务侧）：不再以 SSO 调用充当普通读取；有本地合法资料用本地，缺失明确显示缺失（与 11 统一口径）。

## 非目标
- 不做各领域同步或凭据轮换/跨领域恢复调度（12），不改任务生命周期/通知/候选存储。清理本票已失用途的回源读取与接线；全局出口约束归13。

## 行为验收
- [ ] 阻断客户数据上游出口后：提案创建/详情、任务列表/详情/通知/时间轴/日历、照明状态、只读阶段执行全部正常，上游计数为0（真实执行阶段另计）。
- [ ] 提案准备不再触发远程目录/候选/能力/状态调用（契约测试断言）；候选成员与指纹和本地目录一致；历史候选句柄跨会话恢复仍可解析且复核当前权限。
- [ ] 只读任务完整执行且结果与普通查询同口径；缺数登记同步需求并如实 preparing/PARTIAL，不内联采集、不等待上游、不伪成功。
- [ ] 真实控制链路行为经隔离 fixture 契约测试证明不回退（执行前复核/回读来自真实出口响应、reservation、让行、重启不重发）；不要求生产草案确认或生产执行作为验收。
- [ ] 冷授权快照 preparing 503 与已有内容不被覆盖语义保持；明确拒绝后不返旧授权裁剪结果，与网络失败保留旧值分开。
- [ ] MySQL 不可用→UNAVAILABLE 明确错误，无回源兜底；Redis 清空后任务读取一致。
- [ ] 隔离程序运行任务读取与只读阶段，观察真实结果/终态；微信按总验收图抽样已有任务、通知和未确认提案，不为本票创建生产任务、发送通知或确认控制。

## 证据与既有公开接口
- 公开符号：TaskFacade、TaskProjectionStore、TaskExecutionGateway、TaskConfirmationService、DeviceCandidateSetStore、ReportEvidencePort、RequestIdentity。
- 既有测试接口：TaskHttpContractFixtureTest（公开 MockMvc）、TaskReadScopeContractTest、TaskReadLatencyContractTest、TaskLightingStateSnapshotContractTest、TaskRangeTargetContractTest、OfficeTaskScopeContractTest、RealTaskExecutionGatewayBehaviorTest、TaskLifecycleRecoveryContractTest；测试经包根入口断言，不从 internal 导入，不开新测试框架。
