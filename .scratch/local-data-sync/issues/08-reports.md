# 08: 报告本地取证生成与已存报告详情/下载的本地授权复核

**What to build:** 报告两条读链路全部本地化：新生成报告（对话 create_energy_report、安全报告、任务/检查点补投报告）的取证改从本地基础数据计算——能耗口径消费 05 的原始电表样本，安全口径消费 07 的本地事件台账；已存报告详情与下载前的"当前授权复核"改用本地有效授权，不再为显示正文远程扫描设备目录。PDF/XLSX 渲染保持纯本地。报告事实不可变、补数不静默改写旧报告的语义保持。

**Blocked by:** [05-energy-history.md](05-energy-history.md)（本地能耗取证来源）、[07-safety-ledger.md](07-safety-ledger.md)（本地安全事件来源）；未完成不得开工。

**Status:** ready-for-agent
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- 取证本地化：报告取证入口（ReportEvidencePort 语义）的能源取证读本地电表样本并按既有统计口径计算，安全取证读本地事件与关联；范围解释（all/floor/location/nameMatch/selection）在本地目录快照上展开；生成时同时保存选中 meterIds 与当时完整授权 meterIds 的现行为保持，选择电表报告不再支付全目录扫描成本。
- 生成语义保持：声明式意图、上海时区期间/基期解析、无本期有效数据不生成空附件、coverage/partial/gaps 如实标注、检查点触发按 checkpointAt 锚定相对期间、幂等 reportId 命中已存事实直接复用——全部不变，只换数据来源。
- 授权复核本地化：已存报告详情/下载查本地有效主体授权，保持source/tenant/owner、引用设备及授权设备集合、种类与撤权的既有校验。不得远程扫目录；授权不可判定则不可用，不以旧授权放行。保留no-store、contentHash、文件名头及当前复核时机，本票不顺便重构下载鉴权流程。
- 旧报告与历史事实：已存 report_facts 不被补数改写；conversationFacts 的 historical=true、currentDeviceAuthorization=false 语义保持；引用追问（Agent report/energy_report 引用）解析到同一事实并继承原口径，不重新生成匿名报告。
- 消费面统一：Agent 报告工具与检查点报告路径（含 Agent checkpoint 桥的取证准备与查询）消费同一本地取证与复核入口；MeterExportEvidence 只投影已存事实的行为保持。

## 非目标
- 不做能耗/安全同步（05/07）和任务侧检查点执行（09）；本票提供完整本地取证/读取入口，复用report_facts。本票失去用途的回源代码随切换删除，全局出口清点归13。

## 行为验收
- [ ] 阻断客户数据上游出口后：能耗/安全新报告生成、已存报告详情、PDF/XLSX 下载全部成功，上游调用计数为0（身份核验例外另计）。
- [ ] 已存报告详情/下载的客户目录网络请求计数为0；允许继续经同名公开IotAdapter接口访问本地实现，不以禁止某个方法名调用冒充零回源证据。
- [ ] 撤权后读取已存报告返回 404；本地授权数据不可用时返回 unavailable，无旧授权伪装通过、无跨租户/主体可见。
- [ ] 上游补数后新生成报告采用修订数据且覆盖依据更新；已存旧报告内容与 contentHash 不变。
- [ ] 未覆盖窗口按既有准备/不可用契约，不当作EMPTY或零值；覆盖不完整说明缺数，只有满足原生成规则才发布报告。重复下载内容标识稳定。
- [ ] Redis 清空后报告读取/下载结果一致（从 MySQL 重建并重验当前权限）。
- [ ] 检查点补投报告与对话报告同口径：锚定窗口、幂等、执行身份校验契约测试不回退。
- [ ] 真实微信按总验收图抽样报告详情、下载与引用追问；在隔离程序证明当前权限复核、缺数及上游阻断读取，不在生产撤权造场景。
- [ ] 历史报告引用保留原事实及口径，同时遵守当前身份/可见性约束；不能以historical标记跳过必要授权，也不无故把合法历史执行证据误当实时设备状态。

## 证据与既有公开接口
- 公开符号：ReportGenerationService、ReportEvidencePort、ReportFactProducer、ReportService、ReportFactStore、ReportRenderer、ReportMeterScope、MeterExportEvidence。
- 既有测试接口：ReportGenerationContractTest、ReportFactProducerContractTest、ReportConversationHttpFixtureTest（公开 MockMvc）、ReportExportRendererTest、ReportJdbcIntegrationTest、ReportEvidenceFixture；测试经包根入口断言，不从 internal 导入，不开新测试框架。
