# 缓存与业务完整地图

## 当前执行入口（2026-11-02）

- 用户最新要求：前端只请求 backend 当前已有结果，不等待 backend 同步上游。认证/授权不等于每请求重新扫描完整目录；普通读查询与后台同步必须分离。
- 权威规格：[后端当前结果读取](cache-current-read-spec.md)。31–40 的 resolved 是设计讨论状态，不代表实现/验收完成；与本规格冲突的旧措辞不再作为实施依据。
- 实现票47–50全部完成；47+48、49+50分别完成一次双轴审查，模型均为gpt-6.1-terra，发现已修复并验证。最终业务及故障证据见[验收记录](evidence/cache-current-read-2026-11-02.md)。
- 两类后台更新时间：当前数据默认约60秒，完整历史窗口上海深夜更新；不是统一5秒过期。5秒/60秒不应变成用户请求等待上游的门槛。
- Redis承载可重建读取结果，MySQL持久屏障及恢复代际保证单实例安全恢复；正常退出恢复已有结果，异常退出重新准备。不迁移MySQL/sidecar SQLite业务事实，不建设统一缓存/预热平台。
- 撤回45号“上游采样不足”和“首屏已稳定解决”结论。43台/30天已有数据；原适配器错滤统一流及不完整测时才是问题。按50重新验收。
- [51 历史能耗区间复用](issues/51-energy-history-range-reuse.md)已实现，ready-for-human。撤回“带日期空响应可证明历史无数据”的判断：上游时间参数不是可靠的readTime过滤；已沿用Runtime无筛选流定位并复用自然月块。2025-11-29的43表原始边界差120.96度与backend一致，相邻历史窗口约0.5秒可读；实际微信回看由用户验收，见[51证据](evidence/energy-history-range-reuse-2026-11-02.md)。


## Destination

形成覆盖 `mp` 前端、`iot-agent-backend` Java/Redis/MySQL、Node sidecar 与 Runtime 既有业务语义的统一缓存地图：每个业务能力都明确数据类别、实时性、缓存责任方、失效/刷新、事实来源和业务体验检查方式；地图收敛后生成必要的实现票据，最终支持小程序顺畅使用，不为追求时间数字引入过度复杂的缓存策略。

## 初期地图记录（保留决策来历）

- 以下是最初调查阶段的边界；当前已进入47–50号实现与验证，不再是“仅盘点未实施”。以下旧提案与当前规格冲突时，以当前执行入口和整改规格为准。
- 规范：MySQL 是 Business Fact 权威；Redis 只承载可重建缓存、分布式锁、幂等辅助、限流和运行期协调；Pi Session 由 Node sidecar SQLite 承载。
- Runtime 是行为证据，不作为 backend 运行时依赖；生产云 Runtime 不在本地图阶段修改。
- 用户已澄清：backend cache 与 frontend cache 是两层不同问题，不能合并描述：backend cache 的目标是减少 backend→upstream；frontend cache 的目标是减少 UI→backend。
- 用户已澄清：backend 可以约 5 秒获取一次最新进度，但这表示后端的刷新/更新策略，不表示每个用户请求都全量访问上游。
- 用户已澄清：frontend 手动刷新只绕过/刷新 frontend 自己的快照；它不等价于强制 backend 再访问 upstream。
- 用户已确认：当天所有读请求先纳入“当前数据”范围，默认采用约 1 分钟的前端使用策略；后续仍需区分 backend 更新频率与 frontend 请求频率。
- 用户提出业务体验目标：首屏可见数据尽量接近 3 秒、主要子页面尽量接近 1 秒、设备查询工具尽量接近 1 秒、手动刷新尽量接近 2 秒；这些是发现明显业务等待的检查基线，优先于后端实现结构，但不要求严格硬门槛或复杂策略。
- 用户认为权限撤销等极端边缘不应单独扩张本次缓存设计；本地图保留现有认证/授权约束，但不另造一套撤权缓存机制。
- 已有盘点：[缓存需求与 Redis 架构审计](issues/30-cache-architecture-audit.md)。该审计发现 Redis 原语已存在但业务尚未接入，前端已有 60 秒同身份读快照，Java dashboard 只有进程内 15 秒/5 分钟 fresh/stale cache。

## Decisions so far

- [缓存需求与 Redis 架构审计](issues/30-cache-architecture-audit.md)：当前 Redis 尚未成为实际业务缓存层，Runtime 多类缓存尚未完整迁移；先盘点和决策，不直接把所有内容搬入 Redis。
- backend cache 与 frontend cache 已明确分层：前者负责 upstream 访问复用/后端最新快照，后者负责 UI 使用期间减少 backend 请求；frontend 手动刷新不强制 upstream refresh。
- [冻结缓存新鲜度与固定模板](issues/31-cache-freshness-templates.md)：Backend 分历史快照、当前数据、查询结果三类；Frontend 使用约 1 分钟读快照；事实/事务不进入普通 cache；不设计统一 TTL/统一 Redis cache。
- 当前数据的“实时”是更新/读取策略，不等于每个请求全量读取；backend 约 5 秒更新能力与 frontend 约 1 分钟使用策略必须分别建模。
- [Backend 最新快照刷新机制](issues/39-backend-refresh-mechanism.md)：采用组合策略；高频核心当前数据按业务能力定时刷新，低频/带查询条件读取按请求触发并在约 5 秒内合并在途或复用最近结果。
- [全业务能力缓存分类矩阵](issues/33-business-capability-cache-matrix.md)：首批必要 backend 矫正覆盖首屏当前数据、常用目录/状态、Agent 候选查询和低频查询在途合并；事务/事实、视频 URL、确认/执行/readback、执行态与详情不进入普通读快照。
- [前后端缓存责任与失效协作](issues/34-frontend-backend-cache-ownership.md)：Frontend 只对显式只读路径复用快照；手动刷新只 bypass frontend；Backend 逐步提供当前结果和在途合并；写操作局部失效，不改变 MySQL 事实/CAS。
- [当天实时数据 1 分钟模板与强制现读例外](issues/36-realtime-one-minute-exceptions.md)：当天读请求纳入当前数据范围但不统一复杂化；控制、readback、执行态/详情、视频短地址、会话保持实时/事实语义。
- [物联 Agent 业务性能目标与缓存地图校正](issues/40-business-performance-slas.md)：时间目标用于发现明显等待并做必要的局部 backend/frontend 逻辑矫正；首屏按可见数据、工具只计 Tool Bridge/Java 执行，不为达成数字强行引入复杂预热、索引或缓存分层。
- [历史数据深夜刷新与迟到修正](issues/35-historical-data-refresh-policy.md)：历史默认深夜刷新；补数和迟到采样不新增复杂主动失效机制，实际问题再做局部修正；Report Fact 仍由 MySQL 负责。
- [缓存存储、Redis 协调与 MySQL 事实分工](issues/37-cache-storage-coordination-placement.md)：frontend 使用现有内存读快照，backend 先做简单进程内最新结果/在途合并；Redis不作为全局统一cache，MySQL/SQLite继续承载事实。
- [缓存验收、故障矩阵与推进顺序](issues/38-cache-acceptance-and-rollout-map.md)：先查明显重复读取/串行等待，再做首屏、常用子页面和工具的必要局部矫正；不先建设统一 Redis cache 或复杂预热平台。
- 权限撤销不作为独立缓存设计票；现有认证/授权校验作为业务不变量保留，避免为低概率边缘扩张缓存体系。

## 原待查项与本轮处置

- 首屏/常用子页读取已由47、49切为backend已有结果；后台活动同步不进入普通HTTP等待链，初次缺数明确准备中。
- 前端已有1分钟复用、手动刷新只问backend、退出/换身份作废；显隐合并到旧任务及stale元数据问题已在49修复，245项回归、155文件边界与微信编译通过。
- 真实30天30/30日、43表；三个月129/129与十二个月516/516个逐表月数据及环比均有数值，43表年度同比有效。最终首次后台准备约25/40秒，热HTTP为毫秒级；不能把已有结果即时返回说成首次长报表3秒内生成。
- 历史扫描边界错误和固定密度猜页数由48修复；不要求客户为现成数据提供新接口。
- 多实例 Redis lease、容量指标和故障降级暂不实施，除非实际部署形态或业务 smoke 证明必要。

## Out of scope

- 本地图阶段不修改生产云 Runtime、不执行生产切换、不改变客户上游协议。
- 实现已按47–50在本地mp/backend完成；新增51只记录本次历史滚动核查及候选修正方向，不借缓存整改重写无关业务或生产云Runtime。
- 与缓存无关的页面视觉、模型 prompt、视频播放器体验和客户平台模块不进入本地图，除非被缓存身份/实时性直接阻塞。
