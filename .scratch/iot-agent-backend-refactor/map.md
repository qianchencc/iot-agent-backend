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
- 权限撤销不作为独立缓存设计票；现有认证/授权校验作为业务不变量保留，避免为低概率边缘扩张缓存体系。

## Not yet specified

- backend 最新快照的粒度：按来源/业务能力/设备集合/查询键如何划分，哪些请求可以复用同一份快照。
- 哪些页面数据必须在 backend 预热，哪些可以在页面打开后并行读取；首屏“所有数据”的精确定义。
- frontend 1 分钟策略覆盖所有当天读请求时，页面打开、手动刷新、后台轮询和在途请求如何协作并满足 SLA。
- 设备查询工具的 1 秒预算是否只计算 Tool Bridge/Java 业务执行，是否排除模型生成与网络传输。
- 手动刷新 2 秒是否要求触发 upstream refresh，还是只要求拿到 backend 最新快照。
- “日期过了基本不变”是否允许客户补数、迟到采样、历史修正；允许时如何主动/被动失效。
- 能耗原始分页、能耗报告快照、报告 Business Fact 的存储分工。
- Redis 是否只做协调，还是承担 backend 跨实例读快照；若承担，key/容量/重启/故障规则如何定义。
- 多实例刷新锁、指标、容量、重启恢复和 Redis 故障降级的统一契约。

## Out of scope

- 本地图阶段不修改生产云 Runtime、不执行生产切换、不改变客户上游协议。
- 本地图阶段不直接重写 mp/backend 缓存代码；实现工作在地图决策完成后按票据推进。
- 与缓存无关的页面视觉、模型 prompt、视频播放器体验和客户平台模块不进入本地图，除非被缓存身份/实时性直接阻塞。
