# 08: 安全告警台账、周报与事件引用

**What to build:** 公司 Agent 提供只读安全告警能力：按烟感、燃气、水浸分类的台账，支持日期区间筛选与部分类别缺失时的如实呈现；对话可查询告警、生成最近一周摘要与报告；告警详情显示事件来源、当前设备名称与安装位置（未核验信息明确标注）；从告警设备检索同地点/同楼层摄像头候选，给出以原事件时间为中心的 15 分钟前后录像卡片；支持“引用到对话”后继续检索与建议。所有告警/视频事实只读，建议不得声称已通知或已处置。本票不承诺回放可播放，真实播放由 [09](09-real-video-live-playback.md) 验收。

**Blocked by:** [01: Java 业务定义与通用 Node 执行引擎](01-java-business-generic-engine.md)；[07: 服务端取证的报表、导出与引用](07-evidenced-reports-exports.md)

**Status:** ready-for-agent

**依赖说明：** 台账与只读查询在 [01](01-java-business-generic-engine.md) 后即可开发；但本票验收包含“最近一周告警摘要与报告”，该产物必须复用 [07](07-evidenced-reports-exports.md) 的真实持久报表与导出能力。为避免伪造并行安全或临时假报告，整票以 07 为前置；不得用一段模型摘要冒充可存档报告。

## 说明书章节

第 4 章 4.4（安全功能块、三类台账、筛选、详情与周报）、4.5（同地点/同楼层摄像头候选、事件前后15分钟回放及关联直播卡片、引用与处理建议）、4.6（只读、诚实建议及人工确认）。逐条案例见[验收矩阵](../acceptance.md)，完整规则见[规格](../spec.md)。

## 本票必须自包含的规则

- 告警数据只读；台账按类型（烟感、燃气、水浸）与日期区间筛选，分页/排序语义与 Runtime 一致。
- 某类别无数据或上游未提供时必须显示“暂无/不可用”并说明范围，不得用 0 条冒充“无告警”，也不得合并类别掩盖缺失。
- 详情必须保留事件原始时间、来源；当前设备名称/位置若未经核验要明确标注，不能把候选位置写成已确认。
- 最近一周摘要与报告使用 [07](07-evidenced-reports-exports.md) 的报表事实：包含范围、期间、覆盖、缺失说明与可下载产物；报告可被后续引用。
- 摄像头候选按告警设备同地点或同楼层检索，只返回当前授权摄像头；提供关联直播和录像卡片，录像窗口为事件时间前后各15分钟，保留原事件窗口，不替换为当前时间。
- “引用到对话”必须携带告警事件标识与原时间窗，回复可继续检索候选与给出建议；建议措辞为核实/跟进建议，禁止声称已通知、已出警或已处置。
- 告警与视频事实不写生产；本票不创建任务、不发送通知、不执行设备动作。
- 直播/回放卡片需有可用入口和准确上下文；真实视频帧播放由[09](09-real-video-live-playback.md)负责，与本票组合验证。本票不得以样例视频或静态图宣称播放通过。

## 验收条件

- [ ] 真实公司账号在前端输入“最近有哪些消防告警”，台账列出真实记录，含时间、设备与类型；按烟感/燃气/水浸筛选和日期区间筛选均生效。
- [ ] 选取真实无数据/上游缺失类别，前端如实显示暂无或不可用且说明范围；没有相应事实时该分支保持未验收。故障注入只作单独工程回归，不冒充真实业务缺数。
- [ ] 打开一条告警详情，显示事件来源、原始时间、当前设备名称与位置；未核验字段有明确标注，截图可核对。
- [ ] 输入“整理一下最近一周的告警并生成报告”，得到复用 [07](07-evidenced-reports-exports.md) 能力的真实报告（预览、可下载、持久事实），内容含期间/范围/覆盖/缺失；追问可引用同一报告。
- [ ] 在告警详情点击“按当前位置查找监控”，返回授权摄像头候选，展示关联直播及原事件±15分钟录像卡片；分别保留两个入口截图。
- [ ] 点击“引用到对话”并发送消息，助手保留原事件/时间窗，继续检索候选并提供关联直播/回放入口和处理建议；不声称已通知或已处置。
- [ ] 全程无生产写入、无任务、无通知、无设备动作；证据含截图、脱敏诊断、构建/账号/来源/区域；回放播放证据留给 [09](09-real-video-live-playback.md)。

## 可追溯 Runtime 业务参考职责

对照 Runtime 的告警源、分类、日期筛选、详情字段、未核验标注、关联摄像头检索、±15 分钟窗口与引用事件契约，记录“Runtime 行为 → Java 只读事实/引用 → 前端证据”。Runtime 只读，不修改、不切换生产。

## 工程回归与真实前端验收的区别

工程回归可验证台账/详情/引用字段与接口未破坏；业务完成必须由真实前端台账、详情、报告、候选与引用对话证明，不以接口列表或单测替代。

## 隔离与安全约束

业务修改只在隔离 worktree；原 backend 与 Runtime 只读，必要的真实前端修复另用独立 mp worktree且保持原契约。仅查询真实授权只读上游；不写生产、不发送告警通知、不创建任务、不执行设备动作。授权直播/录像取流属于允许的读取，不因协议使用POST而误判为设备控制。

## 证据要求

证据落在 `.scratch/agent-business-recovery/evidence/<CASE-ID>/`，覆盖 `COMP-SAFE-01`、`COMP-SAFE-02` 及验收矩阵中的周报/引用案例；含步骤、结果、截图、脱敏诊断、报告样本、构建/账号/来源/区域。旧失败或仅有接口结果不构成通过。

## 本票尚未验收

本票仅发布规格，尚未执行任何验收；不得以台账可读、卡片渲染或报告接口成功宣称业务完成。

## 实现记录（2026-11-02，工程完成，业务验收未开始）

对照 Runtime `apps/runtime/src/safety/events.ts`、`src/iot/observation.ts`、`src/iot/adapter.ts` 完成后端只读实现，全部为查询，无生产写入/任务/通知/设备动作：

- `iot/internal/SafetyLedgerComposer.java`（新增）：台账与详情的本地组合层。台账按 3 次上游分页读取（每页 100）滚动扫描，设备按授权安全范围（`loadAuthorizedSafetyScope`，仅 safety 家族，非全目录）与名称分类（烟/水浸/燃气）过滤，范围外计 `excludedCount`、类型不可核实计 `unknownCount`；游标为 owner 绑定（`owner=sha256(tenantCode\0authorization)`、type、窗口、page、offset、before-key）并经 HMAC-SHA256 完整性签名（域分隔 `iotagent:safety-ledger-cursor:v1`；密钥取 `iot.observation.safety-cursor-secret`，回退共享 `CONFIG_SECRET_KEY`，均未配置时用进程内临时密钥，重启后游标作废、如实报 `INVALID_SAFETY_CURSOR` 让用户重查；游标内不携带任何凭据），MAC 验签先于字段校验：同租主改写 page/offset/before/窗口再重编码、或跨租主/换类型/换窗口一律 `INVALID_SAFETY_CURSOR` 400；窗口含时区校验，默认 `to=now`；响应含 `observedAt/window/total(null)/scanned/excludedCount/unknownCount/coverageComplete/nextCursor`。详情先核验设备在当前安全范围内，再按 deviceId 过滤最多 5 页（Runtime `alarmPageCap`）扫描事件身份，找不到返回 404 `SAFETY_EVENT_UNAVAILABLE`，不做首页假 404。
- `iot/internal/WebClientSafetyMetadataReader.java`（新增）：经安全范围 hub 绑定读 iot-center 设备详情（`/micro-api/api/iot-center/device/{hub}`），名称/位置 trim 后为空即 null，失败按“未核验”返回 `{name:null,location:null}`（401/403 保持类型化），上游 5xx/超时有界重试一次。
- `WebClientObservationAdapter`：`parseSafety` 改为诚实原始页解析（items+last+scanned），删除 limit 截断、假 total、恒空 nextCursor 与单事件分支；`SAFETY_EVENT` 不再上游路由（直达即失败，fail-closed）。
- `CompanyOfficeObservationAdapter`：`SAFETY_EVENTS/SAFETY_EVENT` 切换至组合层；“按位置找监控”复用同一权威事件读取（深度分页查找），当前位置空白不再伪造成空位置。
- `ObservationController`：移除 cursor 503 停用桩；详情路由补 `source=company` 校验（404 `SAFETY_SOURCE_UNAVAILABLE`，对齐 Runtime）；`page` 参数不再进入台账白名单。
- 工程回归（未执行，留给统一构建）：`SafetyLedgerObservationContractTest`（续页不重不漏、跨租主/篡改游标、过滤与计数、深页事件、合法空台账、非法窗口、诚实元数据缺失）、`SafetyCameraObservationContractTest`（已迁移到原始页上游桩）。
- 报告/对话消费方（ReportEvidenceService、Agent 工具、引用解析）经组合层自动获得真实覆盖语义：`total` 恒为 null（Runtime 契约），覆盖不完整时如实 partial/缺口。

剩余：真实前端台账/详情/周报/摄像头候选/引用对话验收（COMP-SAFE-01/02 等）全部未开始；证据目录未创建。回放可播放由 09 验收。
