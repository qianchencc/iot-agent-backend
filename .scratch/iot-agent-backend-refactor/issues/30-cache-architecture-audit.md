# 30: 缓存需求与 Redis 架构审计

**类型：** 盘点记录，不执行代码改动

**范围：** `iot-agent-backend` 全部 Java 业务模块、Node sidecar、当前本地 Redis/MySQL 配置，以及原 `iot-agent-runtime` 的缓存语义。

**结论摘要：** 当前后端虽然已经启动并接入 Redis，但 Redis 目前不是实际的业务缓存层。代码中只有通用 `RedisCoordinationStore` 及 Lettuce adapter，业务模块没有调用它；真正缓存业务响应的是 Java 进程内的 Company dashboard cache。Runtime 中已经存在的目录、状态、AC actionId、能耗历史页、能耗报告快照、Company stale fallback 等缓存语义尚未完整迁移。此前票据 03 的“Redis 承载缓存、锁、幂等和限流”是目标性基础设施记录，不等于这些能力已经被所有业务模块实际使用。

**Status:** audit-complete; implementation-not-started

## 1. 当前实现盘点

### 1.1 Redis

- `RedisCoordinationStore` 只提供无类型的 `get/put/delete`、锁、幂等标记、限流计数接口。
- `LettuceRedisCoordinationStore` 已实现 Redis String value、带租约的 `setIfAbsent`、Lua owner 校验释放锁、幂等键和计数窗口。
- 除 `StorageIntegrationTest` 的通用连通性测试外，未发现 Conversation、IoT、Action、Task、Report、Admin 或 sidecar 业务调用 Redis。
- 没有统一 key namespace、数据类型/序列化约定、租户/身份隔离约定、最大 TTL、版本/generation、指标或业务 owner。
- `get/put` 允许任意字符串 key/value，当前接口过浅，调用者必须自行知道所有 key 语义；这不是可复用的深模块缓存接口。

### 1.2 Java 进程内缓存/运行态

- `CompanyOfficeObservationAdapter` 有 `dashboardCache` 和 `dashboardJobs`：
  - fresh 15 秒；stale 5 分钟；只缓存完整、无错误、AVAILABLE 的 dashboard response；
  - 每次请求先重新加载 Company Authorized Device Scope；
  - `dashboardJobs` 只合并当前 JVM 内的并发请求；
  - 没有容量上限、显式清空/generation、跨实例共享或 Redis 失效通知。
- `ConversationFacade.active/toolContexts/toolContextsById` 是进行中的运行上下文和短期 bridge 绑定，不是业务缓存，也不能作为重启后的事实来源。
- `AdminSessionManager` 的登录 session/失败尝试记录是进程内临时安全状态，不属于业务缓存。
- 未发现 Java IoT 适配器实现目录快照、权威状态、AC actionId、能耗分页或 Company stale fallback 缓存。

### 1.3 MySQL

- MySQL 已承载 Conversation、NDJSON event、Action Draft/Receipt/Readback、Task/Plan/Phase/Notification、Report/Audit、Region Seen 等 Business Fact。
- MySQL 是当前仓库规范规定的事实权威；Redis 不得替代这些事实。
- 当前没有 Runtime 对应的 `energy_history_pages` 或 `energy_report_snapshots` 缓存/快照表。
- Report facts 是业务事实，不应改为 Redis 缓存；昂贵的能耗读取快照需要另行决定是可重建缓存还是持久化派生快照。

### 1.4 配置

- `AdminModuleConfiguration` 和配置持久化 schema 已暴露 `catalogTtlMs`、`authoritativeStateTtlMs`、`actionIdTtlMs`、`energyReadTtlMs`。
- 这些配置目前主要作为配置视图/校验字段存在，未接入 Java IoT adapter 的实际缓存实现。
- 因此这些 TTL 目前是“已记录但未生效”的配置，修改它们不能改变实际缓存行为。

## 2. Runtime 中已经明确的缓存需求

| 需求类型 | 缓存内容 | Runtime 语义 | 权威来源 | Java 当前状态 |
| --- | --- | --- | --- | --- |
| 目录快照 | Authorized Device Scope、设备身份、位置/能力绑定 | 通常 60 秒；按身份隔离；`fresh` 扫描、确认、引用、任务/计划详情不得复用共享快照；授权失败清除 | 客户目录/当前授权范围 | 未迁移通用目录缓存 |
| 目录在途合并 | 同一身份、同一观察种类的进行中读取 | 并发请求共享一次上游读取；完成/失败即清除；取消只影响本消费者 | 上游读取 | Java 仅 dashboard 有局部 JVM job 合并 |
| Company 观察 stale fallback | 最近成功的环境/安全/能耗目录 | 暂时性上游失败时最多 30 分钟；401/403 不得兜底；安全设备名称/位置降级为未核验 | 最近成功目录，但权限仍需当前身份验证 | 未迁移 |
| Company dashboard 响应 | 按身份和 section 的完整概览 | fresh 15 秒；stale 5 分钟并后台刷新；部分/失败结果不替换旧值 | 当前授权 scope + 上游观察 | Java 有单 JVM 版本，隔离/失效语义不完整 |
| 权威设备状态 | Company AC 等需要减少重复详情读取的状态 | 默认 3 秒；控制确认和回读 fresh 读取绕过；fresh 结果不写回普通状态缓存 | 当前上游状态 | 未迁移 |
| AC actionId | AC 详情动作模板/动作 id | 按设备编码约 10 分钟；找不到不猜 URL | 当前设备目录/动作模板 | 未迁移 |
| 能耗历史页 | 原始分页页，不是最终报告 | 10 分钟；key 包含身份、上游 origin、首页完整指纹、页码；每次查询仍按当前授权电表集合裁剪 | 上游历史 + 当前 scope | 未迁移 |
| 能耗报告快照 | 完整 Company meter-report / daily report | SQLite 持久；10 分钟 stale-while-revalidate；不完整/授权失败不得替换；冷查询返回 preparing；失败有 60 秒 retry window | 当前授权目录 + 上游历史；报告事实另存持久层 | 未迁移报告快照层 |
| 控制幂等 | Action Draft/Task proposal 的 request key 与终态 | 业务事实必须由 MySQL CAS/唯一键决定；Redis 只能做协调/短期去重辅助 | MySQL Business Fact | Java 事实幂等已有；Redis 未参与 |
| 并发锁 | 确认执行、任务扫描、周期物化、报告刷新等跨实例互斥 | owner 校验、租约、超时恢复；锁丢失不能伪造成功 | MySQL 状态/CAS + Redis 协调 | 主要依赖 MySQL CAS/JVM 同步，未统一跨实例策略 |
| 限流 | 上游读取、视频取流、管理登录等短窗口计数 | Redis 计数器/窗口；失败策略必须明确；不能影响事实保存 | Redis 仅协调 | Redis adapter 有原语，业务未接入 |
| Agent/模型缓存 | Provider/SDK 的 prompt prefix/cacheRead | 只作为模型调用性能指标和诊断，不是业务数据缓存；按请求前缀、重建、压缩分别分析 | 模型 provider telemetry | Node 有 usage/trace 记录，不应与 Redis 业务缓存混用 |

## 3. 缓存设计原则（待后续实现票据落实）

1. **事实与缓存分离：** MySQL 保存可恢复、可审计、影响业务状态的事实；Redis 只保存可丢弃的响应缓存、锁、幂等辅助、限流和 in-flight 协调。
2. **按缓存类别定义语义：** 不把目录快照、权威状态、原始历史页、最终报告、运行中的 job 和锁都塞进一个 `get/put`。
3. **身份隔离必须显式：** key 至少区分 tenant、稳定 principal/credential generation、source；授权范围变化必须有 generation 或 fingerprint 机制，不能只依赖 ownerKey 后复用旧响应。
4. **敏感读取默认 fresh：** 控制确认、控制下发前重核、任务/计划详情、报告引用成员和 readback 不得只相信普通缓存；必须按 Runtime 语义绕过或重新验证。
5. **失败不得污染缓存：** 部分结果、授权失败、未完成分页、错误响应不得写入可复用缓存；授权失败应清除该身份相关缓存。
6. **stale 必须显式：** stale-while-revalidate 只能用于允许陈旧数据的只读展示；响应必须能表达 stale/refreshing/error，不能把旧数据伪装成当前实时事实。
7. **跨实例与单实例分开：** in-flight 合并可先做 JVM 内部实现；需要跨实例互斥的刷新/扫描/控制协调才使用 Redis lease，并保留 MySQL CAS 作为最终裁决。
8. **缓存 key 不能包含明文凭据：** 使用不可逆身份摘要；不记录 authorization、controlRef、hub id、API key 或原始 upstream payload。
9. **可观测性是接口的一部分：** 至少区分 hit/fresh/stale/miss/refresh/error/evicted/authorization-cleared/lock-contended，并按 source 与缓存类别统计。
10. **TTL 必须有 owner：** 管理配置中的 TTL 只有在实际 adapter 消费后才算完成；每个 TTL 需要定义默认值、上限、0 的含义、失效条件和测试。

## 4. 当前问题与风险

- **Redis 实际未承载业务缓存。** 票据 03 的目标描述与当前代码使用情况不一致，存在“基础设施已完成、业务已接入”的误判。
- **缓存语义不统一。** Runtime 有多种缓存类型和 fresh/stale 规则，Java 只迁移了 dashboard 的局部版本。
- **Company dashboard key 可能复用旧授权快照。** Java key 使用 tenant + stable ownerKey + section；同一 principal 的授权范围发生变化时，即使本次 scope 重新读取成功，旧 dashboard response 仍可能在 TTL 内返回。需要 scope fingerprint/generation 或按 credential identity 隔离并在范围变化时清除。
- **dashboardCache 无容量上限和显式 generation。** 长期运行会保留过期 entry，只有下一次同 key 访问才会判断过期；没有统一 clear/eviction/指标。
- **多实例部署会重复访问上游。** `dashboardJobs` 只在单 JVM 合并；Redis 未接入，横向扩展后无法共享 in-flight 或刷新锁。
- **配置存在但不生效。** `IOT_CATALOG_TTL_MS`、`AUTHORITATIVE_STATE_TTL_MS`、`ACTION_ID_TTL_MS`、`ENERGY_READ_TTL_MS` 目前不能驱动对应 Java 适配器行为。
- **Redis 原语没有业务调用和失败矩阵。** 未验证 TTL 到期、owner 锁竞争/续租/过期、跨实例重复刷新、Redis 不可用时的降级和限流失败策略。
- **能耗成本控制缺失。** 能耗历史分页和报告快照未迁移；重复业务查询可能重复读取客户上游，且未形成 Runtime 的 preparing/stale/refreshError 语义。
- **测试覆盖不足。** 当前 Redis 集成测试只验证 put/get、幂等首次/重复、delete；没有覆盖真实业务 seam、过期、并发、授权变化、重启、Redis 故障和跨 JVM 语义。

## 5. 此前方案实际解决的问题

- MySQL/Redis 分工方案首先解决的是：重启、断线、重试后仍可信的 Business Fact 不能放进 Redis 或进程内内存；因此 MySQL 被定义为事实权威。
- Redis 原语方案预留的是：多实例下的短期缓存、分布式锁、幂等辅助、限流和运行期协调，目标是降低上游压力、避免重复刷新/扫描、限制突发流量，并允许 Redis 丢失后由 MySQL/上游重建。
- Runtime 的进程内缓存方案解决的是：单实例上游延迟、并发重复读取、昂贵分页、报告首次准备和短时上游 503；它并没有把这些缓存等价为业务事实。
- Java 当前 dashboard cache 只解决了其中“Company 概览重复读取和 stale-while-revalidate”的一部分，不能证明 Redis 方案或 Runtime 全部缓存语义已经落地。

## 6. 后续票据建议（本记录不执行）

- **P0：缓存契约与 key/身份隔离模块。** 先定义类别、key、序列化、TTL、scope fingerprint/generation、失效和指标；避免各业务直接拼 Redis key。
- **P0：Redis 协调深模块。** 在现有 adapter 之上提供有业务语义的 cache/lease/idempotency/rate-limit seam，定义 Redis 故障策略和跨实例测试。
- **P1：IoT 目录/状态/actionId 缓存迁移。** 对齐 Runtime 的 fresh 绕过、授权失败清除、in-flight 合并和 stale fallback；控制/确认不得依赖陈旧缓存。
- **P1：Company dashboard 缓存修正。** 先解决 scope fingerprint、容量/淘汰、generation 和指标，再决定是否放入 Redis。
- **P1：能耗历史页与报告快照。** 区分可重建原始页缓存和需要持久恢复的报告派生快照；保持当前授权重核，不能用 Redis 替代报告事实。
- **P1：锁、幂等与限流业务接入。** 逐项绑定 Action/Task/Report/Admin 的 owner、租约、CAS 和失败恢复语义。
- **P2：缓存故障与性能验收。** 覆盖 Redis down、TTL 到期、并发刷新、授权撤销、进程重启、多实例重复上游、完整/部分/失败结果。

**本票据不改变现有业务代码和运行配置；后续实现前需先确定上述缓存类别与 MySQL/Redis 分工。**
