# Java 后端与资源配置
配置／启动／存储变更先读根 `docs/configuration-governance.md`；具体键名、消费者与表清单见 `.scratch/admin-console-governance/README.md`。该记录区分当前代码与目标规范，现存差距不因文档更新视为修复。

- 配置从统一设置入口取得类型化有效值。新增参数须登记来源、单位、范围、秘密等级、消费者和应用时机；同一键不在多个消费者维护不同默认值或静默兜底。
- 外置 YAML 管部署启动，MySQL 管可管理业务配置；不新增 `.env` 加载、任意业务处环境读取或 Node 业务默认值。存量入口按盘点逐项迁移。
- 基础设施在 storage 配置边界复用 Boot 3 的 `DataSourceProperties`／`RedisProperties`；保留显式开关，支持的具体键见治理规范。`admin.initial.*` 仅空配置库求值，已有修订读取失败不能触发重播种。
- Java 为普通对话和任务检查点提供完整、版本绑定的 Business Agent Definition。新运行取新快照，在途运行保持旧快照；保存不冒充全部消费者应用成功。
- 数据库、加密根、管理员与桥接凭据属于部署侧。前端／审计只见秘密配置状态和变更字段名；模型密钥只能经私有运行契约送至引擎。
- 用户归属使用核验的 tenant、identity realm、principal；source 是 company／office 来源维度。成员展示不能靠 owner hash 反解或借用当前登录名。
- 查询权限、设备范围、准入、操作授权分别校验；修改提示词不能扩大权限。审计明确区分建议、人工确认、下发受理、回读与自动任务执行。
- 修改持久化时同步 SQL 资源、Java DDL 和旧库升级，记录读写者、唯一键／幂等、版本、归属、敏感字段和删除／保留语义；Pi Session 遵循 ADR0005，不改回 Node 本地库。
- 普通读取先复用既有后端数据入口；Redis 读取缓存和 `ReadSnapshots` 投影不能替代后端业务事实。普通读取的 loader／cache miss 从后端已有数据构建，不直接访问客户上游；上游访问只留给后台同步和真实动作出口。
- Agent、HTTP、报告、任务和引用读取必须接同一既有读取入口。修复单个业务遗漏时先修接线和调用方，不新增第二套缓存 realm、缓存 key 或“准备中”状态机。
- 通过公开模块入口／HTTP 契约验证版本冲突、失败不发布、身份隔离和真实应用；不以源码字段存在、表有数据或类型通过替代端到端证据。
- admin 的远端观测采用判断归内部 `AdminObservabilityStatus`，Controller 只映射 HTTP；任务公开构造签名不得接受 internal 类型，ArchUnit 同时检查完整依赖、公开泛型签名与全部测试产物。
- 自续投的任务扫描循环必须在 Spring 容器关闭事件先于调度器停止续投，再沿既有 `stopAndDrain` 排空；不能仅依赖较晚的 `@PreDestroy`，也不能靠忽略拒绝异常实现停机。
