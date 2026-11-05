# Agent 读取入口回退记录

状态：本轮新增的缓存切换已回退；固定 Java 实例恢复到 `var/fixed-latest-20261105-181818/backend.jar`。Node、MySQL、Redis、Pi Session 及其他线程改动未动。

## 结论

- 用户要求的权威来源是后端已有业务数据；客户上游只由后台同步和真实动作出口访问。
- Redis 是缓存；`ReadSnapshots`／`SnapshotReadModel` 是读取缓存投影，不是后端业务数据源。
- 普通读取的 cache miss 或 loader 应从后端已有数据构建结果，不应直接访问客户上游。
- Agent、HTTP、报告、任务和引用应复用同一个既有读取入口和缓存策略。修复业务遗漏时不得新增第二套 realm、缓存 key 或冷启动状态机。

## 本轮处置

本轮曾新增授权目录和观察读取包装，导致 Agent 使用新的缓存域，不能复用既有读取结果；该实现和对应测试已回退。后续只调查 Agent 绕过既有读取入口的调用接线，并以调用次数和真实业务结果验证。

## 当前最小实现

- Agent 使用 `IotAdapterFactory.agentReads(...)` 接入既有 `COMPANY_DEVICES`、`OFFICE_REGION_CATALOG`、`DEVICE_CANDIDATES`、`DEVICE_STATES` 读取项；未新增 realm、缓存 key 或数据库表。
- 该装饰器只影响 Agent 普通读取；真实控制、视频取流、动作回执和未有既有读取项的能力继续使用原适配器。
- 冷读由既有 `ReadSnapshots` 后台准备，调用方看到明确的准备状态；已有正文命中时不触发上游调用。
- 新增公开工厂契约测试覆盖目录冷读／命中、候选命中、状态命中；ArchUnit 与这些测试通过。

## 当前验证

- 增量包：`var/agent-read-cutover-20261106-0008/backend.jar`，仅包含 `IotAdapterFactory`、`AgentReadIotAdapter`、`AgentBusinessConfiguration` 及其依赖的本轮类；固定 Java 当前运行该包，Node 未重启。
- 受影响公开契约与 ArchUnit：通过；主源码编译通过。
- 真实小程序首次冷读在 5.75ms 目录阶段返回准备中；后台准备完成后同一业务再次读取，目录 2.22225ms、Java 前置 103.19225ms、总耗时 15.8624s，统计工具 0.115s。界面显示 43 台电表、132.35 度；Langfuse trace `5b5796d8909f88331a8791a2ccfbc28d` 有对应 model/tool 记录。
- 干净 `mvn -q clean test` 共642项：613通过、25跳过、4失败、0错误。失败为当前其他线程的 Agent 投影／视频断言及既有能耗夹具断言；本轮新增读取契约和 ArchUnit 不在失败项中。
