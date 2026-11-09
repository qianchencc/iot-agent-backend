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
- 本地持久数据与独立同步实施按根目录 `.scratch/local-data-sync/` 规格及01门禁推进。目录普通读统一经 `iot.DeviceDirectory`；后台同步独立托管凭据，不复用任务授权。新增领域、密钥恢复或部署前读执行票的当前实现与未验边界，再迁移该领域所有普通消费者。
- Company/Office区域与设备必须从同一`DeviceDirectoryBatch`读取；空Office区域不能因没有设备丢失。候选事实保留完整成员及原句柄/顺序，使用时重验当前成员与区域授权，不以整目录指纹变化拒绝仍有效的旧句柄。任务普通读权限同样走本地批次，真实变更/执行校验保持独立。
- 当前状态普通读统一经`iot.DeviceObservations`从MySQL读取并重验本地目录授权；禁止再用HTTP/草案/任务`ReadSnapshots`热正文绕过重验。视频候选卡在后端按同一会话候选集读取本地状态并复用状态事件投影，不依赖模型追加状态调用，不固定填unknown或补生成时间。`observedAt`只表示来源采样，缺失保留null；`cache.updatedAt`表示持久同步成功时间，不能用投影重建时间代替。失败/迟到保留旧观测并标陈旧或缺数，换凭据代次不可读旧代次状态。
- 普通草案准备使用本地目录、状态与按目录类型/只读标记确定的能力；旧值或采样时刻未知不得判断NO_CHANGE。真实确认/绑定重验使用`IotAdapter.revalidateAuthorizedDeviceScope`，执行预检与回读使用`readLiveDeviceStates`；Agent/HTTP/区域/dashboard事件必须透传状态质量与逐设备采样时间，不能补当前时间。
- 草案普通状态读取须保留已有`BusinessException`的业务码与拒绝状态，不把目录/状态准备、当前授权拒绝统一包装成“上游状态读取失败”；缺采样时间不等于缺设备状态，不因null采样阻止生成待确认草案，也不能据此判NO_CHANGE。
- 动作确认结果与授权代次分离：成功、拒绝、中断及重复确认均不因HTTP响应清除本地目录或轮换共享授权屏障；现态沿既有后台同步更新，执行结果沿真实回读保存。真实401/403撤销与凭据轮换仍按原授权规则封闭读取。
- 能耗普通读统一经`iot.EnergyHistory`取得MySQL样本与覆盖；同网关、租户、来源按当前用户的专用电表授权复用已发布历史，授权隔离不等于每用户重采。六类HTTP/Agent统计沿既有Composer；未覆盖保持preparing/partial。采样、同步成功、尝试时间分列，读取不能刷新历史采样时间。
- `IotAdapterConfiguration.Source`与`ObservationAdapterConfiguration.Source`只供后台采集注入；同步器不能注入普通本地读取包装器，否则专用授权会依赖尚未发布的自身数据。原始数字从来源字节直接按BigDecimal解析，再以DECIMAL(65,30)持久化；只在既有统计公式边界转换，禁止先经Double解码。
- 环境历史普通读统一经`iot.EnvironmentHistory`，当前值复用`DeviceObservations`；Agent查询与环境期间引用由服务端设置内部`fullWindow=true`，一次读取窗口内全部本地样本并统一统计，模型工具不再接受page；HTTP原始明细仍每页100条。原始历史的deviceCode没有已证明的设备/楼层关联，不能从当前目录拼造历史归属。未知采样时间保留null与原文，缺值不补零；未知时间、冲突或来源质量未经核实的样本不得参与区间峰值。HTTP、Agent事件与环境引用透传partial/覆盖，不以当前时间补observedAt。
- 通过公开模块入口／HTTP 契约验证版本冲突、失败不发布、身份隔离和真实应用；不以源码字段存在、表有数据或类型通过替代端到端证据。
- admin 的远端观测采用判断归内部 `AdminObservabilityStatus`，Controller 只映射 HTTP；任务公开构造签名不得接受 internal 类型，ArchUnit 同时检查完整依赖、公开泛型签名与全部测试产物。
- 自续投的任务扫描循环必须在 Spring 容器关闭事件先于调度器停止续投，再沿既有 `stopAndDrain` 排空；不能仅依赖较晚的 `@PreDestroy`，也不能靠忽略拒绝异常实现停机。
