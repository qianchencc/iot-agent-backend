# 03: Office 区域目录关系与 Company/Office 候选发现本地化

**What to build:** Office（office）身份的区域列表、区域-设备成员关系本地化，Company/Office 两来源的设备候选发现（DeviceCandidateQuery→DeviceCandidateSet）改为在本地目录快照上展开；既有候选集事实表（device_candidate_sets）、会话内句柄 sN、租户/主体/来源/会话/目录快照绑定与隔离语义原样保持。regions、区域设备、devices/candidates 与 /tools/find_device_candidates 的 HTTP/Agent 读取不再触发上游；本地缺失时明确报错，不回源、不拼凑成员。

**Blocked by:** [02-company-catalog.md](02-company-catalog.md)（复用其同步骨架与隔离域；未完成不得开工）

**Status:** in-progress（持久同步与公开读取工程闭环已验证；真实微信UI未验收）
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- schema：Office 区域表与区域-设备成员关系表（区域ID/设备ID 用真实来源ID；隔离域与建表规则同02；经 SchemaMigrations 登记）。
- 同步：区域与成员关系随目录完整批次幂等写、短事务发布，挂接02骨架各自范围行与进度/覆盖；Company 与 Office 互不混写；上游区域能力与分页变更口径按01核实结论（G2）执行并记录。
- 读取：区域列表、区域设备、候选发现走本地入口（包根公开）；候选匹配语义与 DeviceCandidateMatcher 现口径一致，目录指纹取自本地快照；complete/total_matches 语义不变，未完成筛选如实 complete=false。
- 双入口一致：/tools/find_device_candidates 与 HTTP 候选端点共用同一本地入口，Agent 与小程序行为一致。
- 版本切换：已存在候选集事实不重写；旧句柄解析仍绑定原快照，但每次使用须校验当前主体授权/来源，撤权或已失效成员不能因历史快照重新获得权限。
- 交互保持：Focus Region 会话绑定核验、region_seen 已读标记（既有本地事实）行为不变；候选句柄持久，断线恢复后仍可引用。
- UI：办公侧区域切换、区域设备列表、对话中候选选择与展开正常；目标区域本地缺失→明确错误提示，不回源。
- UI错误：缺本地覆盖按既有准备/不可用协议；只有已知完整范围能证明不存在时才返回原NOT_FOUND语义，不能把未同步误报区域不存在。

## 非目标
- 不做区域时间轴/日历/未读聚合（11）；不做最新状态读取（04）；不做视频候选（10）；不做能耗/环境/安全数据面。

## 行为验收
- [x] 区域与候选读取不再触发上游（契约测试断言）；Redis 空仍可完整读取。
- [x] 候选集成员、目录指纹与本地快照一致；句柄分配、会话隔离与既有 DeviceCandidateSetStore 契约不回退。
- [x] 目录同步半批失败不发布：已有完整版本仍可读；无完整版本明确准备/不可用。已发布目录上的候选展示截断保留complete/totalMatches真实含义，不把半份目录冒充完整范围。
- [x] 跨租户/跨主体/跨来源互不可见；他主体会话不能引用本会话候选句柄。
- [x] MySQL不可用、本地覆盖未准备、完整范围中对象不存在分别保持既有错误语义，无回源兜底。
- [ ] 办公区域切换与候选选择开发者工具 UI 截图验证（含错误态）。
- [x] 上游区域分页漂移时整窗重扫安全：不残留半批成员关系。
- [x] 目录切换不重写旧候选事实；旧句柄对仍有权成员保持语义，撤权后的引用/使用被拒，不能直接返回范围外旧成员。
- [x] region_seen/RegionAttentionReader 行为不回退（既有集成测试保持通过）。
- [x] Agent 工具与 HTTP 候选入口行为一致（同一契约测试覆盖）。
- [x] 复用既有候选事实表与句柄机制，不建立第二套候选库；运行隔离程序验证重启后的引用/隔离。真实UI按总验收图抽样，不进行生产确认/控制。

## 证据与既有公开接口
- 公开符号：IotAdapter.loadOfficeRegions、IotAdapter.findDeviceCandidates、DeviceCandidateQuery、DeviceCandidateSet、DeviceCandidateRecord、DeviceCandidateSetStore、OfficeRegion、OfficeDeviceSource。
- 既有测试接口：DeviceCandidateSetStoreBehaviorTest、CompanyOfficeObservationAdapterContractTest、RegionAttentionHttpIntegrationTest；测试经包根入口，不从 internal 导入，不开新测试框架。

## 2026-11-06 实施与证据

- 包根 `DeviceDirectory` 统一Company/Office，`DeviceDirectoryBatch`绑定区域、完整服务端设备、同步时间与陈旧标记。新增`local_office_regions`；成员关系复用来源隔离的`local_company_offices`，不建第二套关系库。区域、目录、授权、位置和成员同事务同版本发布；空区域独立保留。
- Office采集复用真实member/myOffice/association协议；成员分页完整性、重复ID、缺失ID或页号矛盾直接拒绝，不继续采设备。失败批次保留旧版本。HTTP区域目录不再从ReadSnapshots缓存正文授予权限。
- 候选匹配继续用既有matcher与事实表；65个完整成员只展示50个时保留totalMatches=65、complete=false及完整服务端成员。旧句柄不因新增无关设备失效，不重排原成员；使用前核对当前设备类型及原区域授权，撤权成员不能借旧快照恢复。
- 实际jar烟测发现区域attention仍经旧TASK_READ_SCOPE_V1准备/回源；已切换TaskReadCatalog普通读至同一持久目录，保留空区域权限。任务变更的实时核验不变，任务状态与只读阶段尚属04/09待实施。
- 定向回归通过：CompanyDirectoryBehaviorTest、IotAdapterContractTest、DeviceCandidateSetStoreBehaviorTest、CompanyOfficeObservationAdapterContractTest、TaskReadLatencyContractTest、TaskReadScopeContractTest、TaskLightingStateSnapshotContractTest、AutomationSummaryReaderContractTest、ArchitectureTest。另显式启用独立MySQL运行RegionAttentionHttpIntegrationTest通过：异常事实点亮、按查看者已读隔离、后续异常重新点亮、本地撤权区域不可确认。
- 独立真实MySQL、Redis禁用、Java34781和协议夹具34789实跑：首次preparing无假空regions；12次完整Office采集发布一个有设备区域和一个空区域；未知区域404；HTTP候选落库为s1；公开候选不暴露controlRef；区域attention组合和普通读零新增Office来源请求。停止并重启同一jar、完成独立身份核验后，目录无新采集即恢复；关闭来源后区域设备和/tools/find_device_candidates仍成功，持久句柄继续分配s2。程序均已停止，固定服务/数据库未切换。
- 新增测试仅用合成身份/设备/地点；来源停机是隔离夹具故障，不是真实设备动作。新增持久回归验证跨页成员重复导致整批失败、旧目录仍可读、下一周期从头重扫后原子替换；Company/Office数据库停止均失败关闭且零来源回退。剩余未验项为真实微信区域切换、候选展开和错误态截图。
- MySQL集成正常退出时仍观察到既有TaskScanLoop在调度器shutdown后重排抛TaskRejectedException；测试断言通过不代表该关闭竞态已修复。未改任务生命周期，记录到12运行边界。
