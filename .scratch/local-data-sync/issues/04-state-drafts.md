# 04: 最新状态/能力描述/草案准备与当前状态展示本地化（真执行保留）

**What to build:** 设备最新状态、能力描述、控制草案（ActionDraft）所需的设备事实改读本地库；当前状态展示（devices/states、区域状态、dashboard 状态信息）本地化并携带真实采样时间。真正确认、下发执行、执行回读复核仍是显式例外，走真实上游，不用本地状态替代；状态旧可展示但不作控制证据。

**Blocked by:** [03-office-candidates.md](03-office-candidates.md)（本地目录/成员关系先行；未完成不得开工）

**Status:** in-progress（工程已验证；独立微信编译/真实UI及确认全链路隔离烟测待收口，不阻塞后续独立领域实施）
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- schema：设备最新状态表（对象ID+隔离域唯一；状态字段结构化、扩展才 JSON；observed_at 采样时间与本地写入时间分列）与能力描述快照（随目录批次）；经 SchemaMigrations 登记。
- 同步：最新状态按约60秒当前同步节奏幂等 upsert（批写成功才推进进度，失败保留上次完整）；能力描述随目录批次；状态不回填历史（历史属05/06）。
- 读取：devices/states、区域状态及 Agent 状态工具走本地入口（包根公开）；部分设备无最新状态→PARTIAL 并明示缺失清单，EMPTY/UNAVAILABLE 按 ObservationStatusEnum 如实返回；Redis miss 从 MySQL 重建并重验权限。
- 同源一致：dashboard 状态信息、区域状态与 devices/states 读同一本地入口，不各自实现。
- 草案与执行边界：ActionDraft 成员与设备事实来自本地目录/状态/能力；授权与绑定核验、确认、执行、回读（action_readbacks）保持真实上游链路；状态陈旧可用界限按01核实结论（G4）执行并记录。
- UI：状态页、区域状态、dashboard 状态信息显示本地数据与采样时间；同步停摆时显示陈旧提示而非伪造新鲜；错误态明确。
- 观测缺失：无 observed_at 的样本不伪造时间，按 PARTIAL/UNAVAILABLE 如实透传。
- UI错误：陈旧态与错误态区分展示，不用加载动画掩盖。

## 非目标
- 不做能耗（05）、环境历史（06）、任务只读阶段（09）和dashboard跨域组合（11）；本票自身失去用途的回源loader随切换删除，全局清点归13。

## 行为验收
- [x] 状态与能力普通读取不触发上游；Redis禁用的MySQL实际程序仍可读。
- [x] 响应携带真实采样时间；刷新/同步失败不更新采样时间列，无来源时间保留null。
- [ ] 草案准备基于本地事实；通过隔离生产协议夹具验证确认→执行→回读仍使用专门权威出口，不下发生产控制。
- [x] 跨身份/租户/凭据代次读取他主体设备状态、旧状态或能力被拒。
- [x] 部分缺失→PARTIAL含缺失设备清单，不假完整；MySQL不可用→显式503无回源兜底。
- [ ] 状态展示与草案确认卡（不点击确认）按真实微信抽样验证，含旧状态/缺数边界；隔离程序实际运行证明状态数据来源与时间，不只测试。
- [x] 能力由已发布目录类型/只读标记与既有业务规则确定，与目录版本一致；草案不引用已下线设备。
- [x] dashboard状态段与devices/states共用DeviceObservations，包含相同质量和采样元数据。
- [x] 状态表无外键，幂等upsert；失败整批不发布，迟到/无时刻样本不覆盖已有更新采样。
- [x] Agent状态工具与HTTP状态、区域、草案、任务现态共用本地入口，事件/模型/前端保留质量，不补now。

## 证据与既有公开接口
- 公开符号：IotAdapter.readDeviceStates、IotAdapter.readObservationStates、IotAdapter.inspectDeviceCapabilities、DeviceStateReadResult、ObservationStatusEnum、AcCommandCapabilities、ActionDraftFacade。
- 既有测试接口：OfficeDeviceStateReadContractTest、ObservationAdapterContractTest、DeviceCandidateSetStoreBehaviorTest；测试经包根入口，不从 internal 导入，不开新测试框架。

## 本轮工程证据（2026-11-06）

- 结构：`local-device-states-v1`经SchemaMigrations登记；`local_device_states`结构化布尔/空调/能耗/环境字段，DECIMAL(65,30)严格无损入库，扩展JSON保留，来源采样/本地写入分列；`local_device_state_sync`独立批次成功进度。
- 同步：沿目录登记与约60秒节奏，目录成功不代表状态成功；状态批写/成功进度同事务，失败保留旧批，权限拒绝撤销可读；读取前后本地授权与凭据代次重验。能力无外部调用，由同批目录事实及静态业务规则确定。
- 动作边界：准备/普通状态使用统一LocalReadIotAdapter；ActionDraft确认、任务真实变更与执行的授权用`revalidateAuthorizedDeviceScope`，预检/执行回读用`readLiveDeviceStates`。实时回读也只用来源真实采样，缺时刻不伪造已确认。
- 119项定向回归118通过、1条件跳过，覆盖持久状态重启、跨主体/代次、迟到、缺数、严格decimal批次回滚、本地dashboard一致性、旧状态不判NO_CHANGE、动作真实出口/回读契约及ArchUnit。前端335项和边界检查通过；去掉把Java数字装箱类型或旧缓存准备机制当业务契约的断言。
- 实际进程：独立端口34781/34789与隔离MySQL schema，Redis禁用；实际jar采集两台合成Office灯，一台true有真实fixture采样时间、一台false没有时刻。HTTP与区域响应PARTIAL且无伪造全局时刻，逐台reportedAt及同步cache一致；这些普通读取未增加客户数据请求。重启后客户数据出口503、身份核验仍可用，状态与原采样/同步时间未改，stale+partial如实返回。全部来源含身份关闭的尝试返回ACCOUNT_IDENTITY_UNAVAILABLE，符合G4，不能以旧状态越过身份失效。
- 前端草案/区域/观察卡/dashboard保留后端质量字段；按用户最新决定，不再显示未知采样/同步/读数时间或旧状态注记，首页环境卡不单列采样行。独立微信编译与185文件边界检查通过，首页无采样辅助行已实际查看；其他卡片仍需真实逐项走查。约20条整体业务抽样仍未收口；确认→执行→回读的合成HTTP/进程证据见下条补充，未替换固定3381/3382，助手未确认生产控制。
- 草案状态边界现保留原始BusinessException，不新增等待或重试。新增真实H2持久状态回归：尚无成功状态批次返回DEVICE_STATE_PREPARING、不创建草案；后台成功写入无采样时刻的false后，同一目标可生成待确认草案且不回源。7组草案/目录/能耗/ArchUnit定向构建71项，70通过、1条件跳过；不等于已修复确认后目录失效问题。
- 确认后目录失效根因已修复：删除`ActionReadSnapshotUpdates`，不再以确认HTTP响应触发`ReadSnapshots.invalidate`轮换共享授权代次。真实401/403、凭据轮换、跨主体/来源隔离保留；本地现态继续由后台同步发布，不把真实回读结果伪装成整个目录已刷新。确认成功/拒绝/中断及各自重复确认、后续同步更新、原文失败轮丢弃后重新执行均有行为回归；6组74项全过。
- 17:43实际jar+隔离MySQL（Redis禁用）经HTTP完成候选→提案→单台选择→确认→草案现态→连续两次“你好”；共享授权代次未变化，原本false且无采样时刻的现态仍可读，重复确认只下发一次。设备和Agent Engine均为合成协议夹具，后者只返回终态以证明Java准备阶段可通过；不声称真实模型/微信或设备验收通过。夹具和34781烟测进程已停止，[结果](../evidence/action-confirmation-scope-20261106.json)。
- 本轮完整`mvn package`：698项中667通过、28条件跳过、3项既有失败（`AgentToolBridgeContractTest`的告警汇总/视频回放与`SafetyReviewToolContractTest`单候选回放，均为`modelResult`断言）；不删断言、不宣称全绿。随后`mvn -DskipTests package`成功生成与烟测相同jar并仅更新34811。前端348项及185文件边界检查通过；固定3381/3382与Node34812未替换。

## 2026-11-08：视频候选卡在线状态

- 视频检索后由Java按同一会话候选集确定性读取已有本地状态，卡片复用状态事件投影；删除固定online=null及卡片生成时间冒充observedAt的旧投影。真实未知仍为null，读取失败保持错误，不取流、不改播放器/Node/同步/配置。
- 公开入口回归覆盖本地持久在线、离线、未知、全部未知及逐设备真实时间；66项定向含ArchUnit全部通过。全量712项中683通过、28跳过，既有安全回放用例`SafetyReviewToolContractTest.singleCandidatePreparesRealPlaybackCardAndProjectsVideoPlaybackBlock:81`仍失败，未扩展修改。
- 同账号原13台摄像头的真实本地数据经只读诊断授权夹具和生产投影生成13台online=true、0台unknown、observedAt=null；真实前端标签助手得到13个“查看画面”。无客户上游调用或视频取流，不作为微信/播放验收。[脱敏工程证据](../evidence/video-candidate-state-20261108.json)。
- 已将固定Java3381更新为`var/fixed-video-state-20261108-231135/backend.jar`，PID15433，SHA256=`46816a507a345a7981713f9e6e8350c85b2e27368254a719c8c38f9054b996f3`；Node3382/PID32963、原配置/表结构/业务记录数保持，旧环境完整窗口产物保留回退。微信重新查询与实际播放仍待验收，既有历史卡片不回写。
