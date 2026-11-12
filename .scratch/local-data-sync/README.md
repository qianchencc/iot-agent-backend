# 本地业务数据与独立同步：完整执行地图

日期：2026-11-06。
状态：**01契约门禁已闭合；02公司目录、03 Office区域与候选、04状态/能力/草案边界、05原始能耗与六路计算、06环境当前与历史已完成持久读取和隔离工程烟测，真实UI尚未验收；07—14尚未实施完成，业务尚未验收。** 用户已要求按地图完整推进并维护各层AGENTS；主要实现与整合由主代理承担，子代理只作只读调研。不据此授权生产迁移、真实控制或擅自重启。

入口：[完整规格](spec.md) · [最终效果与验收矩阵](acceptance.md) · [首票：同步契约门禁](issues/01-sync-contract.md)

2026-11-11新增[本地数据副本与必要同步改进执行入口](../sync-storage-governance/README.md)，含五张实施／验收票；[规格](../sync-storage-governance/spec.md)与[只读证据](../sync-storage-governance/evidence.md)区分事实、暂存和binlog。用户已明确上游副本可重建，不为其扩展灾备或复杂迁移；日志按实际用途处理，逻辑只做必要且简洁的改进。当前仅发票，未改运行配置；首次及每日全量、约60秒更新、独立授权和本地读取契约不变。

2026-11-06阶段入口已启动：Java`34811`／Node`34812`，独立微信项目`var/local-data-preview-20261106-160316/mp-weixin`已打开且运行时确认指向34811；真实公司首屏334台及环境值已显示，首次能耗全量仍在进行。固定3381/3382不变；这是02—06阶段预览，不代表07—14完成或业务票已验收。[入口和隔离边界](../agent-business-recovery/local-preview.md#本轮本地同步阶段验收2026-11-06-1603) · [启动证据](evidence/stage-preview-20261106.json)。

## 1. 终点与范围

终点不是“Agent多接几处缓存”，而是：**HTTP、Agent、引用、只读任务、报告取证共用后端持久数据；Redis只加速；客户业务数据采集统一由独立同步执行。**

全部现有普通业务能力在范围内：Company/Office目录、区域、候选、能力描述、状态、草案准备、能耗全窗口统计与比较、环境、安全、报告、任务/计划/通知、只读执行阶段、视频准备、dashboard、时间轴/日历、引用和跨轮追问。平台事实写入沿原业务事务，不伪装成同步；身份核验、真实控制与执行回读、视频开流为显式外部出口。

最终用户效果、覆盖边界及性能方向见[验收第1节](acceptance.md#1-最终预期效果)。本轮不承诺模型推理1秒、无限历史首次即时、未知撤权实时感知或无权访问时仍用旧缓存成功。

## 2. 设计依据与纠正

- 已确认：MySQL持久事实/数据副本、Redis可重建结果；结构化查询字段＋必要JSON扩展；现有业务事实库不复制。
- 已确认：独立加密托管已核验主体的同步凭据；当前约60秒；首次及每天上海深夜全量同步该主体合法可访问数据/历史，不改统计口径、不缩30天/同比/自定义。
- 2026-11-06最新全量决定覆盖此前首次仅按需和夜间有限范围限制；既有[能耗冷启动记录](../energy-cold-load/issues/01-shared-history.md)保留为历史。全量不是“固定24个月缓存预热”，不设24个月边界。用户说明全量最多十来分钟；深夜是启动时刻，不新增允许运行时段或跨夜轮转。
- 旧策略已有后台更新，不是从零设计；本次更换其基础数据来源和生命周期。当前ReadSnapshots刷新活跃查询、默认闲置30分钟淘汰，不能承担持续持久同步。
- 本轮隔离探针只使用现有编译ReadSnapshots和受控时钟，无Redis/上游：历史条目加载一次，闲置31分钟后淘汰，上海午夜无刷新，下次请求才重新登记。这是机制证据，不是本次同步实现已通过。
- 原始设计、历史验收与当前运行构建区分；本地图不撤回既有业务成功，也不拿旧截图证明新实现。

## 3. 执行票据与依赖

各票为独立文件；领域票贯穿数据入库、同步、现有公开读取入口和对应展示行为。只有01是明确的实施前决策票；02起交付可运行业务，不先交空表、通用框架或只会返回preparing的脚手架。

| 票 | 直接阻塞项 | 可独立观察的交付 |
|---|---|---|
| [01 同步契约门禁](issues/01-sync-contract.md) | 无 | 同步身份/凭据、数据契约、覆盖/校正范围、新鲜度约束有证据且可实施 |
| [02 公司目录与授权](issues/02-company-catalog.md) | 01 | 完整目录同步持久化后，HTTP/Agent查目录不回源，Redis空仍可读 |
| [03 Office区域与候选](issues/03-office-candidates.md) | 02 | Office区域/设备关系及两端候选本地查询，持久句柄和完整成员保持 |
| [04 状态与草案准备](issues/04-state-drafts.md) | 03 | 当前观测、能力描述和草案准备本地化；真实控制安全例外不变 |
| [05 原始能耗与全统计](issues/05-energy-history.md) | 02 | 本地样本支持日/月/比较/夜间/自定义；新窗口按需补缺 |
| [06 环境观测](issues/06-environment-history.md) | 03 | 当前与历史测点数据统一入库并从HTTP/Agent读取 |
| [07 安全台账](issues/07-safety-ledger.md) | 02 | 台账列表/详情、处置变化及关联基础信息本地化 |
| [08 报告取证与读取](issues/08-reports.md) | 05、07 | 本地取证生成报告，旧报告详情/下载不远程扫目录且保持授权 |
| [09 任务及只读执行](issues/09-tasks.md) | 04、06、08 | 提案/列表/通知/只读阶段/checkpoint不回源；原任务生命周期不变 |
| [10 视频元数据与开流分离](issues/10-video-metadata.md) | 03、07 | 本地候选与窗口准备，真正开流保留独立上游动作 |
| [11 全入口组合一致性](issues/11-unified-consumers.md) | 04、05、06、07、08、09、10 | dashboard、跨域引用追问、时间轴/日历和资料聚合全部统一 |
| [12 连续同步与恢复](issues/12-sync-recovery.md) | 04、05、06、07 | 跨领域断点、迟到校正、午夜停机、凭据暂停恢复有运行证据 |
| [13 干净切换与出口约束](issues/13-clean-cutover.md) | 11、12 | 普通入口回源清零，失去用途旧loader/包装清理，例外出口可验证 |
| [14 最终验收与受控发布](issues/14-acceptance-release.md) | 13 | 隔离故障工程证明、约20真实微信代表路径、单一最终构建及发布恢复方案 |

### 可并行前沿

01闭合后执行02。02完成后，03、05、07是独立可推进分支；03后可做04、06；05与07后可做08；03与07后可做10。09需要准备期状态、环境及报告能力；11需要组合能力全部到位；12可在11前并行进行。13、14是最终共同收口。

上述并行仅是依赖允许，不代表可同时改同一共享文件。共享接口/配置/SQL迁移/前端入口须指定一个集成负责人，按契约协调；不覆盖其他线程改动。每个领域票先迁完自身所有普通消费者，不把基本Agent接线拖到11或回源删除拖到13才算本票可用。

## 4. 首票门禁，不隐藏未决细节

01决策门禁已按[共享契约LDS-20261106](issues/01-sync-contract.md#可实施契约-lds-20261106主代理裁决)闭合：独立加密托管、身份与授权隔离、来源记录键/完整性/修订、首次及每日全量、失败与旧值规则均已明确。源码调查与公开身份入口烟测见同票；它们不证明新增持久同步已经实现。02可开始；03—14仍按表中实现依赖推进，ready-for-agent不是完成状态。共同约束：

- **G1：** 合法同步主体、凭据来源/托管许可、登记/暂停/轮换/恢复；不假设存在服务账号。
- **G2：** 来源对象及记录唯一性、权限视图差异、分页/时间筛选/修订/删除能力。
- **G3（业务决定已明确）：** 首次及每天上海深夜全量同步合法来源可访问历史，无24个月边界；用户说明最多十来分钟，定时启动后跑完，不加跨夜机制。
- **G4：** 新鲜度、旧值可用条件与授权有效期各自的规则；不把缓存TTL当授权。

代码/配置/已有契约可回答的由执行者核实；确实需要客户或用户取舍时一次列清问题并阻塞相关实施，不猜值。已保存原始历史默认不增加自动删除，正式保留期另行确认。完成地图不等于这些运行前提已经验证。

## 5. 实施纪律与切换

1. 01确定共享接口和数据契约后，02以真实公司目录闭合最小同步链，包含持久登记、完整发布、基础重启恢复及普通读；不创建独立通用同步平台。
2. 每个领域以现有公开接口为测试面，schema沿唯一迁移入口，测试不能跨模块import internal。导出符号变更前查引用，迁移全部调用者。
3. 每票已有范围内实现完整切换，禁止“本地失败再走旧上游”的成功兜底。未迁移领域在隔离集成中明确标记，不提前宣称全局本地化。
4. 领域票有定向行为回归与实际程序烟测；所有变更集成后做一次交付边界完整审查/回归，不把文档检查当运行证明。
5. 既有模型/会话/任务事实及并发管理台改动保持。部署前读取当时最新运行入口，不把本文中的历史端口/产物作为启动命令。
6. 新库和旧库升级都需验证；备份并独立恢复演练后，由部署所有者安排最终构建切换。旧程序回退不等于数据回滚；不自动恢复旧回源链路。
7. 清理本次切换后失去用途代码和文档，保留历史证据及真实例外。不得借清理删除不相关改动。

## 6. 验收与最终效果的证据边界

- 实现覆盖：全部普通入口清单，不抽样遗漏代码路径。
- 工程证明：[E01—E16](acceptance.md#3-工程验收必要不变量)，包括客户数据出口阻断、Redis空、跨身份、分页半失败、重复/迟到、30分钟闲置、崩溃和午夜恢复。
- 真实业务：[U01—U20建议样本](acceptance.md#4-真实微信代表业务路径)。约20条而非死数量；以用户真实微信开发者工具结果和截图为准，未抽到项保持未验收，已发现缺陷不能藏进抽样边界。
- 性能：分别记录本地读取/计算、模型、工具、流交付与前端可见，不用缓存毫秒读伪装首次完整同步或整轮模型性能。
- 安全：不控制生产设备、不确认生产任务、不写通知/配置、不重启他人服务；真实动作只用隔离fixture证明安全契约，不伪装真实执行。

## 7. 当前源码导航与历史依据

以下仅作定位，实施须读当时最新源码：

| 关注点 | 入口 |
|---|---|
| 当前Agent装配 | [AgentBusinessConfiguration](../../src/main/java/ltd/qianc/iotagent/agent/internal/AgentBusinessConfiguration.java) |
| 统一普通读与真实例外边界 | [LocalReadIotAdapter](../../src/main/java/ltd/qianc/iotagent/iot/internal/LocalReadIotAdapter.java)；[DeviceObservations](../../src/main/java/ltd/qianc/iotagent/iot/DeviceObservations.java) |
| 缓存公开契约、固定模板 | [ReadSnapshots](../../src/main/java/ltd/qianc/iotagent/readmodel/ReadSnapshots.java) |
| 活跃条目刷新/恢复机制 | [SnapshotReadModel](../../src/main/java/ltd/qianc/iotagent/readmodel/internal/SnapshotReadModel.java) |
| 能耗原始样本与本地计算 | [EnergyHistory](../../src/main/java/ltd/qianc/iotagent/iot/EnergyHistory.java)、[CompanyEnergyObservationComposerImpl](../../src/main/java/ltd/qianc/iotagent/iot/internal/CompanyEnergyObservationComposerImpl.java) |
| 环境原始样本、质量与本地窗口 | [EnvironmentHistory](../../src/main/java/ltd/qianc/iotagent/iot/EnvironmentHistory.java) |
| HTTP观察能力清单 | [ObservationOperationEnum](../../src/main/java/ltd/qianc/iotagent/iot/ObservationOperationEnum.java) |
| 报告正文与当前授权读取 | [DefaultReportService](../../src/main/java/ltd/qianc/iotagent/report/internal/DefaultReportService.java) |
| 身份核验与时限 | [SecurityConfiguration](../../src/main/java/ltd/qianc/iotagent/auth/internal/SecurityConfiguration.java) |
| 现有表结构 | [MySQL结构目录](../../src/main/resources/db/mysql/) |

约束与来源：[配置与数据治理](../../docs/configuration-governance.md)、[Java业务与通用引擎ADR](../../docs/adr/0004-java-business-definition-generic-agent-engine.md)、[Java/MySQL会话ADR](../../docs/adr/0005-java-owned-mysql-pi-session.md)、[整体业务恢复](../agent-business-recovery/README.md)、[旧缓存地图](../iot-agent-backend-refactor/map.md)、[能耗复用证据](../energy-cold-load/evidence/ENERGY-COLD-01/README.md)。

## 8. 当前状态

01门禁已闭合。02/03通过`iot.DeviceDirectory`统一Company/Office完整目录、授权、区域和候选读取；独立加密托管凭据并后台完整发布，保留服务端绑定且隔离公开字段。撤权代次、重新准入和不可解密凭据不能复用旧授权。04的`DeviceObservations`保存真实采样/部分缺数/独立状态同步进度；普通状态、能力和草案准备共用本地读取，确认/执行/回读使用显式实时出口。05的`EnergyHistory`将专用电表授权、原始decimal样本、完整历史覆盖与补缺范围持久化，六路HTTP/Agent能耗计算共用本地样本，保留既有统计公式。06的`EnvironmentHistory`统一本地环境历史窗口与质量，当前值复用DeviceObservations；未知采样时间不补当前、缺值不补零、不拼造历史楼层。区域任务attention及当前照明状态也使用本地授权，不覆盖任务执行事实。07—14尚未完成；02—06真实UI仍未验。固定实例未由本轮切换，不存在“已上线”“全部普通读取零回源”或“真实微信已通过”的本轮结论。

02工程证据：CompanyDirectoryBehaviorTest、IotAdapterContractTest、IotAdapterConcurrencyContractTest、ArchitectureTest等定向回归通过。原AgentReadIotAdapter缓存转发夹具已随04统一入口删除，行为由持久目录/状态测试覆盖。真实MySQL隔离库烟测验证迁移重复执行、冷读零来源请求、独立后台HTTP采集、实例重建且读取投影为空/来源关闭后本地读取、Agent保留observationOnly、凭据非明文、无外键，以及撤权再准入不复用旧授权。单设备夹具读取15.18ms，不代表真实目录或全量历史性能；没有真实设备动作。

03工程证据：Office完整批次/空区域/半批失败/Company隔离/持久完整候选与当前授权回归通过；独立真实jar与MySQL、Redis禁用实跑首次准备→12次来源请求完整发布→区域设备及候选读取→区域attention零增量回源→重启→关闭来源仍可读，句柄s1后持久分配s2。分页漂移恢复、数据库故障与显式启用的region_seen真实MySQL集成均通过；真实UI仍未验，细目见03票。

04工程证据：11组定向回归119项中118通过、1条件跳过；前端335项及180文件边界检查通过。真实jar+独立MySQL、Redis禁用，模拟源通过生产Office协议提供有采样的true与无采样的false；普通状态和区域状态均为PARTIAL，逐设备采样与同步元数据一致，普通读取未增加数据源请求。重启后客户数据出口503、身份核验仍可用，旧值/原始时间保持且标stale。全部来源（含身份）断开时，重启身份核验503失败关闭，未拿旧值越过身份门禁。前端新增质量提示的微信编译、实际画面与草案完整确认烟测未收口，不冒充真实UI或设备控制验收；详情见04。

05工程证据：最新20组能耗/IoT/动作/目录/观察/任务读取/ArchUnit定向构建199项中198通过、1条件跳过。独立冻结jar+MySQL、Redis禁用实跑六端点200且普通读数据源调用增量0；2020高精度数字原样存取，30个08:00日窗口各用量10。最终同一产物重启，客户数据全503而身份核验可用，六路仍读本地原值/原采样；独立全量授权刷新失败后六类缓存均stale，未用失败尝试刷新成功时间。原始传输以Source类型与普通适配器隔离，MySQL upsert歧义和来源浮点提前舍入已修复。真实能耗UI未验，RUNNING崩溃等跨领域恢复继续归12；见05。

06工程证据：环境行为13项与Agent业务定义18项通过，另运行观察/组合定向package；前端Agent339项及182文件边界检查通过。独立冻结jar+MySQL、Redis禁用，冷历史preparing→后台完整发布→本地partial；2020高精度数字原样存取，NULL曲线缺值与未知时间999不伪造成有效峰值。两段历史与dashboard普通读数据源增量0；重启后客户数据出口503而身份核验可用，样本/当前值/真实时间不变，全量失败后stale且原updatedAt保留。真实微信编译/画面未验，见[06票及运行证据](issues/06-environment-history.md#工程证据2026-11-06)。


全套Java回归曾运行648项，3项失败、25项跳过：AgentToolBridgeContractTest的alerts聚合与视频准备两项、SafetyReviewToolContractTest的视频卡投影一项，均为modelResult断言。此前基线也有同项失败，不据此声称本轮全套通过；其余定向通过不能替代集成与真实UI验证。

## 9. 本轮文档验证

2026-11-06 已运行文档结构检查：17份规划文档、14张票编号/必需字段/阻塞依赖与契约一致且无环；96个本地链接与标题锚点、9个旧入口相关链接有效；22个ObservationOperationEnum能力均进入验收表，另列非枚举普通入口。验收矩阵含16组工程场景与20条建议微信路径。

已逐票整合校正缺覆盖与空数据、授权失效与旧值、候选跨会话、独立视频授权、来源样本唯一键及真实执行例外的边界。以上为规划阶段文档验证，不证明实现或业务通过；当前代码与工程验证进度见第8节。后续主代理负责实施、整合与验证，子代理仅作只读调研。
