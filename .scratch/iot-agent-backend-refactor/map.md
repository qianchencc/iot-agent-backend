# 缓存与业务完整地图

## Destination

形成覆盖 `mp` 前端、`iot-agent-backend` Java/Redis/MySQL、Node sidecar 与 Runtime 既有业务语义的统一缓存地图：每个业务能力都明确数据类别、实时性、缓存责任方、失效/刷新、事实来源和业务体验检查方式；地图收敛后生成必要的实现票据，最终支持小程序顺畅使用，不为追求时间数字引入过度复杂的缓存策略。

## Notes

- 本地图阶段只做盘点、决策和问题记录，不修改业务实现；后续实施必须拆为独立票据。
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

## Not yet specified

- 哪些页面数据必须在 backend 提前准备，哪些可以在页面打开后并行读取；首屏可见数据的实际组合和当前慢点仍需业务抽样确认。
- frontend 1 分钟策略与页面打开、手动刷新、后台轮询、在途请求的具体交互仍需在实现验收中检查。
- 设备查询工具是否存在实际超过约 1 秒的目录重复读取或串行上游调用，需通过 backend 业务 smoke 定位。
- 历史补数/迟到采样只有在真实验收发现问题时再补局部修正。
- 多实例 Redis lease、容量指标和故障降级暂不实施，除非实际部署形态或业务 smoke 证明必要。

## Out of scope

- 本地图阶段不修改生产云 Runtime、不执行生产切换、不改变客户上游协议。
- 本地图阶段不直接重写 mp/backend 缓存代码；实现工作在地图决策完成后按票据推进。
- 与缓存无关的页面视觉、模型 prompt、视频播放器体验和客户平台模块不进入本地图，除非被缓存身份/实时性直接阻塞。
