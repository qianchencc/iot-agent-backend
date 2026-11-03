# 07: 服务端取证的报表、导出与引用

**What to build:** 公司 Agent 按真实业务规格生成声明式报表，而非由模型自由编造版式：报表声明包含统计口径、期间、授权范围、各表数据依据与覆盖情况、缺失说明；生成前可预览，确认后可下载有效 PDF 与 XLSX；报表作为持久业务事实保存，支持关闭会话后重新打开并通过同一报表引用继续追问；报告附件仅在公司 Agent 可用。本票同时提供周期报表所需的真实报表产物能力，供 [11](11-recurring-plans-reports.md) 集成；不伪造周期调度本身。

**Blocked by:** [05: 能耗统计与夜间分析](05-energy-statistics-night-analysis.md)；[06: 能耗对比与页面/对话一致性](06-energy-comparisons.md)

**Status:** ready-for-agent

## 说明书章节

第 3 章 3.4（对话生成报告、下载 PDF/导出表格、统计口径说明、各电表依据与覆盖、可存档分发、周期报表经定时任务、仅公司 Agent 可用）、3.5（报表卡片可查看明细与追问）；第 5 章 5.6（报告长期保存、下载、导出、引用追问）。逐条案例见 [验收矩阵](../acceptance.md)，完整规则见 [规格](../spec.md)。

## 本票必须自包含的规则

- 报表结构由服务端声明式定义驱动（所需章节、指标、数据依据、覆盖与缺失字段、导出格式），模型只能选择业务意图与参数，不得发明章节、数据或结论。
- 每个数值必须可追溯到统计口径、表集合、期间与样本覆盖；报表内显示缺失/未覆盖项，不以 0 或旧值补齐。
- 生成前提供预览，预览内容与实际导出文件一致；未预览不得直接声称已生成可下载报表。
- PDF 与 XLSX 必须是真实可解析文件（非 HTML 改名、非空壳），包含报表标识、期间、范围、生成时间；导出失败返回明确错误，不留半成品。
- 报表作为持久业务事实写入 MySQL，含归属 principal/tenant/source/范围；会话结束后仍可读、可再次导出、可被引用追问。
- 引用必须解析到同一报表事实（不是重新生成或按标题猜）；追问时继承原报表期间、范围与口径。
- 报告附件只在公司来源可用；办公室来源请求报表时明确说明不可用，不降级为假报告。
- 周期报表入口由 [11](11-recurring-plans-reports.md) 接入；本票只保证报表产物与重复生成可复用，不实现周期计划、不创建真实任务。
- 本票不写生产数据、不创建任务、不发送通知。

## 验收条件

- [ ] 真实公司账号在前端输入“公司本月的能耗报告”，先看到包含统计口径、期间、范围、逐表依据与覆盖的预览；预览可关闭或进入导出。
- [ ] 下载 PDF 与 XLSX，两者均可被标准阅读器/表格软件打开，内容与预览一致；XLSX 数据可计算，PDF 含中文与报表标识。
- [ ] 报表内明确显示缺失/未覆盖项及原因；不出现由模型编造的电表、数值或结论。
- [ ] 关闭并重新进入会话后，按报表引用继续追问（如“把 26 楼部分单独列出来”），系统解析到同一报表事实并继承原口径，不重新生成另一份匿名报表。
- [ ] 在办公室 Agent 申请报告附件时，前端明确提示仅公司可用，不产生可下载的假报告。
- [ ] 报表记录中可查到归属、期间、范围、构建与生成时间；重复下载同一报表得到相同内容标识。
- [ ] 全程不写生产业务数据（除隔离环境自有 MySQL 的本地报表事实）、不创建真实任务、不发送通知；证据含截图、文件样本、脱敏诊断、构建/账号/来源/区域。

## 可追溯 Runtime 业务参考职责

对照 Runtime 的报表组件、字段与导出契约、附件归属和引用语义，记录“Runtime 报表行为 → Java 声明式报表/事实 → 前端与文件证据”。Runtime 只读，不修改、不切换生产。

## 工程回归与真实前端验收的区别

工程回归可验证报表事实结构、导出接口与引用解析；业务完成必须由真实前端预览、真实文件打开、重启后引用证明，不能以接口返回 URL 或单测替代。

## 隔离与安全约束

业务修改只在隔离 worktree；原 backend 与 Runtime 只读，必要的真实前端修复另用独立 mp worktree且保持原契约。报表事实只写隔离环境自有 MySQL；不写生产、不创建任务、不发送通知、不上传敏感内容到外部。

## 证据要求

证据落在 `.scratch/agent-business-recovery/evidence/<CASE-ID>/`，覆盖 `COMP-ENE-04` 及验收矩阵中的报告/引用案例；保存预览截图、PDF/XLSX 样本（脱敏）、文件校验信息、引用追问截图、构建/账号/来源/区域。旧“仅有接口结果”不构成通过。

## 本票尚未验收

本票已完成公司真实对话生成报告附件、打开预览、重启隔离 Java 后重开同一报告，以及前端按钮下载 PDF/XLSX 的定向验收，见 [COMP-ENE-04 证据](../evidence/COMP-ENE-04/README.md)。引用追问、其他期间/安全报告及真机原生阅读器未在本轮复验；不得将本次定向成功扩展为本票全部验收。

## 实现记录（2026-11-02，ReportConsumerCorrections）

- 定时相对期间锚定（P1）：`DefaultReportFactProducer` 对 checkpoint 触发的报表先以 `checkpoint.checkpointAt`（阶段 bound 时间）把 `previous day/week/month` 解析为绝对窗口再取证；重试跨日/跨月仍回到原检查点窗口，解析结果随事实持久化（`requirements.period.from/to`），reportId 由 taskId+phaseId 决定、重试命中已存事实直接复用。对话自然查询（`DefaultReportGenerationService` → `ReportEvidenceService.build` 单参入口）保持按当前时钟解析，显式 absolute 期间语义不变。锚定语义与 Runtime `resolveEnergyReportSpec(spec, anchor)` 一致（按发生时刻而非执行时刻）；backend 沿用既有 08:00 计量日界，未改边界口径。
- 巡检块渲染（P2）：`ReportPdfRenderer`/`ReportXlsxRenderer` 新增 `ac_inspection` 分支（XLSX 第 4 张 Inspection 表，数值仍写数值单元格）。逐设备呈现检查前状态（开/关机·模式·设定温度）、处置（已下发调到X℃ / 已下发未确认回读 / 这次未调整 / 无需调整 / 状态未知）、平台回读值与服务端原因；节能估算恒标「估算值，非电表实测」，假设参数（每升 1℃ 节电比例、单台功率、电价、调前均值、目标、平均调升、台数）、计算式、原因与 potential 标记全部保留；缺设备行的检查点显示「本检查点没有取得设备结果」，无估算块不产生任何节电表述。前端 `shared/ReportPreviewContent.vue` 增加同口径区块（视图模型 `pages/agent/domains/reports/report-view.mjs`，经 `lib/reports.mjs` 引入），沿用 rpc 样式体系，不改版式。
- 新增回归：`ReportFactProducerContractTest` 两个定时锚定用例（两次发生各自解析到各自前一窗口；跨日/月延迟重试产出与原事实一致，且取证 span 断言实际采窗）；`ReportExportRendererTest`（PDF 内容流解码文本与 XLSX sheet4 单元格断言，含缺设备/缺估算不造数）；前端 `test/agent-report-inspection.test.mjs`（指令与回读分离、未确认/未调整保留、估算口径与空态）。
- 协同口径：`disposition=unconfirmed` 为 TaskExecutionCorrections 票 10/12 新增值，本票渲染按「已下发，未确认回读」处理，不计入已调整；safety 台账渲染未改动（属 SafetyLedgerCompletion 票 08 面）。
- 历史实现时状态：当时测试与构建尚未执行。2026-11-02 本轮定向验证及真实前端/下载文件结果已更新在下方；真机和未抽验分支仍不宣称完成。

## 已发现问题收口（2026-11-02）

- 报告工具“未接入”的实际原因：报告生成、读取、检查点产物及 Controller 在组件扫描阶段使用 `ConditionalOnBean`；后声明的 MySQL 存储 Bean 尚不可见，导致整个报告模块被跳过。改为与存储模块一致的 `agent.storage.mysql.enabled=true` 显式条件，依赖由构造注入强制满足；未修改 Node、未加入假报告或成功兜底。`ReportApplicationLifecycleTest` 按真实扫描顺序复现 `ReportGenerationPort` 缺失，修复后经公开生成/读取/导出路径通过。
- 真实按钮下载的旧 PDF 发现中文乱码：手写 PDF 声称使用 STSong-Light，但没有内嵌可移植字体；仅解码内容流的旧测试不能证明阅读器可用。已切换 Apache PDFBox 3.0.8 和内嵌子集化 NotoSansSC 字体，按真实字宽换行、不裁掉长行；测试改为标准 PDF 解析器，验证中文事实、数值及字体嵌入，另用独立阅读器渲染真实下载文件。
- PDF/XLSX 补齐原预览已有的实际采样覆盖、逐表起止累计示数、客户模拟补数披露；共享 `MeterExportEvidence` 只投影已保存事实，不重算或重取测量值。XLSX 保留用量和累计示数的数值单元格，缺值仍为空，不补零。
- 字体来源：[Noto CJK 官方字体](https://github.com/notofonts/noto-cjk/blob/main/Sans/Variable/TTF/Subset/NotoSansSC-VF.ttf)，按 wght=400 固化为静态 TTF；源 SHA256 `d68bafcb48a2707749396aa12bbbd833cb70401f3a9a689fd2902c7e0d295964`，资源 SHA256 `07c3c75fc1e95052a40d1b0267a8f79fe3220b20c136da185984690738c76bca`。版权元数据保留，SIL OFL 1.1 随 `src/main/resources/reports/LICENSE` 分发。PDFBox 来自 [Maven Central](https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox/3.0.8/)。
- 本轮 9 组定向回归共 66 项通过，覆盖报告生成/导出/扫描生命周期、取证、能耗、台账、草案恢复及巡检结果；不是全仓测试全绿声明。删除了以表头存在推断“虚构估算”的错误测试及依赖精确换行/措辞的断言，不为通过测试改写真实业务事实。
