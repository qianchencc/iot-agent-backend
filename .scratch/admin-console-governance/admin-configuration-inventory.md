# 管理配置与控制台契约现状

范围：当前主线 AdminConfigurationStore 的30个已知字段、17个live字段、启动参数和实际管理 API。源码快照基线7479502；后续并发修改需重新核对。全表列举字段名，不记录任何真实配置值。

## 生效分类

- **L**：LiveAgentSettings 在每次 `active()` 中以最新已保存值覆盖启动版本；普通新运行实际消费。保存本身可影响下一次运行，不等待 `/apply`；在途运行保留已捕获快照。
- **S**：存储允许保存、视图按启动版本报告，需要后续应用；不表示业务消费者已读取。
- **X**：RetiredCacheSettings 明确拒绝保存并从视图／恢复中剔除。
- **N**：Java可保存，但未找到送至Node观测器的生效链路。

## 逐项字段（30项）

默认来自AdminModuleConfiguration.initial；这些是当前bootstrap默认，不是统一规范已落地。范围来自JdbcAdminConfigurationStore.validate。文件存储存在并行定义，今后需统一而非继续复制。

| API字段 | 类型／单位 | 当前默认与校验 | 当前消费／时机 | 控制台规划 |
|---|---|---|---|---|
| modelBaseUrl | URL | 必填http(s)，无userinfo/query/fragment | L：Java定义模型传Node | 模型连接 |
| modelId | string | 必填1–200字符 | L：普通及检查点模型 | 模型连接 |
| modelApiKey | secret | 必填1–4096字符 | L：私有运行契约，公开视图只有存在状态 | 密钥替换；不可回读 |
| contextWindow | integer/tokens | 128000；8192–2000000 | L：模型定义 | 模型能力 |
| maxOutputTokens | integer/tokens | 16384；256–128000，必须小于contextWindow | L：模型定义 | 模型能力 |
| reasoning | boolean | true；false要求thinkingLevel=off | L：模型定义 | 推理能力 |
| thinkingLevel | enum | low；off/low/medium/high | L：模型定义 | 推理能力 |
| supportsDeveloperRole | boolean | false | L：模型兼容定义 | 高级模型能力 |
| supportsReasoningEffort | boolean | true | L：模型兼容定义 | 高级模型能力 |
| supportsUsageInStreaming | boolean | true | L：模型兼容定义 | 高级模型能力 |
| maxTokensField | enum | max_completion_tokens；另允许max_tokens | L：模型兼容定义 | 高级模型能力 |
| maxAgentTurns | integer/轮 | 6；1–30；消费端fallback为8且允许至32 | L：普通运行；检查点另固定6 | 执行预算，须统一边界 |
| maxToolCalls | integer/次 | 10；1–1000 | L：普通运行；检查点另固定10 | 执行预算，不能宣称覆盖检查点 |
| officeInstructions | string | 空；作为业务基础指引的追加内容 | L：office新运行 | 办公室指令 |
| companyInstructions | string | 空；作为基础指引的追加内容 | L：company新运行 | 公司指令 |
| replyInstructions | string | 空 | L：两个来源新运行 | 回复规则 |
| runTimeoutMs | integer/ms | 300000；1000–600000；policy fallback180000、桥接fallback120000 | L：普通运行deadline；检查点固定180000 | 超时，先清楚定义适用范围 |
| draftTtlMs | integer/ms | 600000；30000–3600000 | S：草案消费另取agent.action-draft-ttl-ms，未发现此字段映射 | 接通生命周期语义前不能称已应用 |
| heartbeatMs | integer/ms | 15000；0–60000 | S：Java消费agent.heartbeat-ms，Node另SIDECAR_HEARTBEAT_MS | 区分两条流，接通后纳管 |
| readbackWaitMs | integer/ms | 6000；0–120000 | S：下发组件消费iot.gateway.readback-wait-ms | 控制回读；接通后纳管 |
| controlTimeoutMs | integer/ms | 25000；1000–120000 | S：当前下发构造参数仍来自iot.gateway.call-timeout-ms | 控制超时；接通后纳管 |
| scanIntervalMs | integer/ms | 15000；1000–60000 | S：实际scanner poll另键，未见映射 | 任务扫描；接通后纳管 |
| catalogTtlMs | retired | 初始化已不提供 | X：CONFIG_UNSUPPORTED | 不呈现可编辑控件 |
| authoritativeStateTtlMs | retired | 同上 | X | 不呈现 |
| actionIdTtlMs | retired | 同上 | X | 不呈现 |
| energyReadTtlMs | retired | 同上 | X | 不呈现 |
| langfuseEnabled | boolean | false；启用要求三变量齐备 | N：Node以三env是否齐备独立启用，未读此开关 | 接通Java→Node后才能交付开关 |
| langfuseBaseUrl | string | 空 | N/S：无实际传输 | 运行观测；明确未接通 |
| langfusePublicKey | secret | 空 | N/S | 只写入，绝不回显 |
| langfuseSecretKey | secret | 空 | N/S | 只写入，绝不回显 |

证据：`admin/internal/AdminModuleConfiguration.java:145-182`、`LiveAgentSettings.java:9-25`、`JdbcAdminConfigurationStore.java:31-38,61-73,77-107,286-331`；真实消费者见[java配置盘点](java-configuration-inventory.md)与[sidecar盘点](sidecar-startup-inventory.md)。

## 管理模块部署参数

| 当前读取键 | 默认／作用 | 统一方向 |
|---|---|---|
| admin.config-file / ADMIN_CONFIG_FILE | var/admin-config.bin；MySQL关闭时加密文件 | 正式事实权威MySQL；文件存量需显式迁移，不能并行当双权威 |
| admin.config-secret-key / CONFIG_SECRET_KEY | 空；64hex/32bytes，缺失fail closed | 部署秘密引用，不进入业务配置表 |
| admin.password / ADMIN_PASSWORD | 空；文件fallback | 单管理员部署凭据，外置YAML或受限secret文件；不经控制台修改 |
| admin.password-file / ADMIN_PASSWORD_FILE | var/admin-password | 旧fallback与web改密路径需干净切换，不能遗留第二套密码管理 |
| admin.password-minimum / ADMIN_PASSWORD_MIN_LENGTH | 6；实例构造限制1–128 | 部署安全策略，不随意暴露业务调参 |
| admin.origin / ADMIN_ORIGIN | http://127.0.0.1:3200 | 精确源、CSRF和同源部署，不提供通配禁用 |
| admin.supervisor.url / token | 空 | 部署进程应用通道；accepted不等于完成 |
| server.address / port / spring.profiles.active | 127.0.0.1/3200/local | 启动只读；当前version硬编码0.1.0，timezone为系统默认 |

环境锁：live字段不再因环境值存在锁定；其余部分字段仍锁。密钥不回读逻辑存在，但当前save要求modelApiKey非空，不具备任意必需密钥清空语义。

## 当前管理HTTP面

| 路径 | 当前事实 | 设计影响 |
|---|---|---|
| POST /api/admin/login；GET /session；POST /logout | 独立Cookie会话、CSRF、Origin和限流 | 保留并验证真实登录，不引入多管理员体系 |
| GET/POST /password | 配置密码存在时拒绝web改密；否则允许文件改密 | 目标改为部署管理；不能因文档更新宣称旧接口已移除 |
| GET/PATCH /config | 版本化视图与保存；actor当前operator | 保留冲突及秘密状态，目标admin归因 |
| POST /apply | 非live差异时可申请supervisor重启，默认busy恒false | 缺实际工作栅栏与完成回执；不可显示全部已应用 |
| GET /changes；POST /restore | 最近50条修订，恢复生成新修订 | 50是查询限制，不是保留策略；需要分页／完整历史设计 |
| GET /prompts | 返回active指引与view.revision | 混合版本时不能把revision当所有字段采用证明 |
| POST /test-model | 使用草稿覆盖saved进行实际模型连接 | 正式界面必须声明未保存，并防止错误带出秘密；本轮未调用 |
| GET /pi-session/health | Java/MySQL健康聚合 | 只读运维，不把旧Node文件备份恢复放回来 |

## 现有前端不是空骨架

当前`web/admin/src/views/HomeView.vue`已有模型、能力、预算、指引、快照、Pi健康、修订历史和恢复等内容，仍使用Arco；`api/admin.ts`仍声明已退役缓存字段。正式设计需要保留真实交互成果，拆分与替换控件，不以旧空骨架判断覆盖整个文件。成员准入与按用户审计仍无完整管理API；原型中的虚构成员、成功状态不作为实现证据。

## 必须先解决的差距

1. 统一字段描述／校验／默认／消费映射，修正桥接两个键、超时fallback、退役字段类型和未接通保存字段。

2. 明确保存与显式应用语义；保留已有每轮快照不变，禁止报告不存在的全局active确认。
3. 接通真实在途工作栅栏、应用回执与恢复；健康状态区分Java、Node、事实库和两个Agent来源。
4. 首次业务配置不完整时有安全初始化路径；不能要求完整模型密钥才建配置store、却又指望管理员登录后完成第一次配置。
5. no-.env切换必须覆盖Java、Node、Compose和工具；现有进程不在本次审计中切换。

## 2026-11-04 实施更新（ConsoleConfig 分片，票03）

- 字段唯一描述源：`admin/AdminSettingCatalog`（`GET /api/admin/config` 内嵌 `fields`：label/group/kind/unit/default/min/max/options/effect/secret/springKey/consumer/note）。视图另含 `deploymentSettings`（三项部署参数当前启动值）与 `runtime.observability`（sidecar 真实采用状态）。
- 生效分类变化：`heartbeatMs`、`scanIntervalMs` 升级为热字段（LiveAgentSettings）；真实消费者：ConversationFacade 每轮读取心跳（新增 LongSupplier 构造）、TaskScanLoop 每周期读取轮询延迟（改为自调度循环，属性为回退）。`scanIntervalMs` 管理默认 15000→1000 对齐 `agent.task.scanner.poll-ms`。
- `draftTtlMs`/`readbackWaitMs`/`controlTimeoutMs` 定义为部署上报（`DeploymentReportedSettings`）：保存拒绝 `CONFIG_READONLY_DEPLOYMENT`、恢复剥离、视图移除、pendingRestart 不再计入；控制台只读展示部署属性当前值。
- Langfuse 四字段：restart 生效。sidecar 启动时经 `GET /internal/agent/observability`（bridge-token 私有鉴权）拉取启动快照；单进程单 SDK（@langfuse/tracing 走全局 provider，多 SDK 不可替换，禁止热切换）。`/healthz` 上报 adopted 状态（enabled/revision/baseUrl/credentialsDigest=SHA-256(pk:sk)，无秘密）；管理端以 sidecar 上报与 active() 比对得出 `adopted`，保存/apply 不算采用。
- Node 干净切换：`MODEL_*`、`AGENT_THINKING_LEVEL`、`LANGFUSE_*` env 全部移除；`definition.model` 必填（未配置模型时运行明确失败、sidecar 可启动供控制台初始化）。
- 首次配置：种子放宽仅允许模型三元组为空（其余非法种子仍 fail closed）；管理员登录后补齐保存。管理员密码仅部署来源，`/api/admin/password` 与 `AdminConfigurationFile` 移除。
