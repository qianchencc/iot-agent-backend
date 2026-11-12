# iot-agent-backend

Java public backend plus an independent Node `agent-sidecar`: Spring Boot 3.5 / Java 21+ and Node 22. Java owns customer HTTP, identity, business instructions/tools, device/task/report behavior, MySQL business facts **and durable Pi Sessions**. Redis is coordination/cache, not session authority. Node owns only the generic Pi loop, streaming, tool transport and interruption checkpoints; no SQLite, database credentials or local session volume. `../iot-agent-runtime` is read-only behavior evidence, never a runtime dependency.

架构约定、模块边界纪律与 Runtime→Java 模块映射见 [AGENTS.md](./AGENTS.md)；issue tracker 与领域文档规则见 `docs/agents/`。

控制台已切换为 Vue3 / JavaScript / Element Plus，提供独立部署管理员登录、独立手机号名单、实际参数配置与按成员组织的公司／办公室操作审计。三个进程均以常规命令启动（Java 外置 `application.yaml`、Node 外置 `agent-engine.yaml`、控制台 `npm run dev`），不加载 `.env`；Node 从 Java 获取模型运行定义与启动观测快照，不再使用模型／Langfuse 环境回退。字段、启动方式与隔离验证证据见[控制台治理入口](.scratch/admin-console-governance/README.md)和[维护手册](web/admin/docs/maintenance.md)；当前固定开发实例与核对结果见下文，不表示已部署生产或完成真实业务验收。

2026-11-11 公司任务白名单接入：名单持久化保持原表，启动加载、管理提交后更新内存资格；普通请求不查名单库、不增加上游请求。任务HTTP入口与统一工具分派校验，公司工具列表及小程序入口按资格开放；办公室、设备权限、已确认任务与调度不变。单实例契约见[ADR0008](docs/adr/0008-standalone-phone-list.md)，工程检查、真实截图及未验边界见[证据](../mp/.scratch/company-task-access/evidence/TASK-ACCESS-01/result.json)。

## 常规本地启动

固定端口 Java `3381`、Node `3382`、管理控制台 `5185`。三个进程都用各自的常规命令启动：无启动器脚本、无自定义进程清单、不加载 `.env`。tmux 只是可选的手动终端——需要时自己 `tmux new -s backend` 开会话、逐窗口粘贴下列命令即可，不作为必需编排，也没有自动重启。

2026-11-12存储治理已部署：固定Java3381运行`var/sync-storage-20261112-093244/backend.jar`，只更新四组IoT持久化类，Node、前端和外置配置不变；两套MySQL已关闭无用途binlog并正规回收旧日志，目录／授权／安全事件减少重复正文写入。当前产物、空间实测、回归及未验边界见[执行入口](.scratch/sync-storage-governance/README.md)；下列较早发布记录仅作历史。

2026-11-06 21:22固定Java3381已更新为`var/fixed-energy-20261106-212234/backend.jar`，修复正常刷新误判授权变化、同主体合法换证重拉已有电表历史及本地统计逐表查询；Node3382仍使用`var/fixed-latest-20261106-194349/agent-sidecar/`，原外置YAML、控制台、MySQL、Redis和管理设置保持。定向42/42及打包通过；全量701项中670通过、28跳过，原有3项告警／视频断言失败仍在。重启后43台/237624条样本及全量覆盖保持，真实小程序日统计约0.89–1.76秒并正常展示；未备份或下发控制。[当前运行及验证边界](.scratch/agent-business-recovery/local-preview.md)；[此前任务命名部署证据](.scratch/task-title-naming/evidence/TITLE-02/deployment.json)及[18:46启动清理记录](.scratch/local-data-sync/evidence/fixed-latest-20261106.json)保留。

2026-11-09 历史能耗答复修正：模型摘要按 `buckets.items` 计数并保留分段、指标和位置排行；时间对比复用既有同表集和周分段计算 `periodComparisons`，不足完整可比周不计算比例。沿用既有补数规则，将 `dataProvenance` 同时交给卡片和模型，不改变采样或总量算法。固定3381仅替换原运行包中的三个能耗相关类及公司指引，运行配置22只更新能耗段落，Node3382、模型、凭据与其他设置不变。前端371项、186文件边界及微信编译通过；后端737项（708通过、29跳过）通过，明确排除原有一个视频回放失败。真实小程序结果及未验边界见[AG-080验证记录](../mp/.scratch/huly-test-execution/evidence/AG-080/20261109-fix-verification.json)。

2026-11-10 环境趋势 HTTP 完整窗口修复：`GET /api/agent/environment-history` 未传 `page` 时读取完整所选窗口，显式传页号仍保留原始明细分页；修补此前只覆盖 Agent 工具／引用、仪表盘仍停在最近100条的遗漏。前端不再传页号或要求手动续读，不补造缺测。35项相关后端回归、371项前端回归及微信编译通过，用户已确认通过；固定3381只替换HTTP控制器，Node、私有配置和既有业务实现不变。[修复与确认记录](../mp/.scratch/agent-business-recovery/evidence/environment-http-window-20261110.json)。

2026-11-10 周期任务核验改为后端本地事实：暂停、恢复、取消及草案确认不再实时拉取客户设备目录；移除实时核验／状态读取旁路，恢复与执行仅校验计划目标的本地授权及控制绑定，不受其他设备整体指纹变化影响。草案与任务预检复用既有60秒采样有效性，过期、未来、无时间或已标陈旧的状态不作NO_CHANGE依据；下发后核验仍要求真实采样不早于指令。上游读取底线已同步根与`src/main/AGENTS.md`，真实控制下发按用户决定保持原样。740项回归（711通过、29跳过，另排除既有一个视频回放失败）；隔离H2实跑连续启停0次上游访问，列表／详情／工作日日历一致，本地未就绪不补拉。固定3381已更新冻结修复包并通过局域网健康检查，Node／外置配置不变；真实计划写入／真机未验收。[工程证据](../mp/.scratch/plan-local-authorization/evidence/PLAN-TOGGLE-LOCAL/result.json)。

2026-11-10 新账号首访同步修复：沿用既有同步器，将授权目录、当前采集、历史补齐分开调度，新登记范围优先准备；电表专用授权先发布，已有历史继续共享，不等待整批设备状态。保留目录版本保护，同账号同凭据代次的当前采样在途时推迟目录重发，避免慢采样被每轮目录刷新持续丢弃；新凭据代次仍可正常轮换。不增加环境当前值共享，不改变采样／同步时间规则。742项回归中713通过、29跳过，另明确排除既有一个视频回放失败；隔离真实调度约5秒完成新账号授权，旧采集仍挂起、历史新增采集为0、越权仍拒绝。固定3381运行`var/cold-account-bootstrap-20261110-1343/backend.jar`；指定测试账号经本地已核验身份准确匹配，个人记录及对应Redis投影已备份后清理，其他用户与共享目录保留。局域网健康通过，真实开发版首访留给用户验收。

2026-11-10 报告导出改进：PDF使用指标概览、两期总量对比图、逐表对比、采样依据附录、重复表头与页码；XLSX按概览／逐表／采样依据及已保存的指标、分段、构成贡献分别出表，不再混合统计与对比导致重复计数。只有期间、范围、逐表值／状态／采样和覆盖一致的统计明细才由对比明细承载；零基期比例、缺数保持空值并说明，不重取或改写报告事实。使用用户提供报告的同一保存事实核对43台、913.04／908.81度及4.23度差额；工程验证、文件版面与固定实例采用情况见[导出改进证据](.scratch/agent-business-recovery/evidence/REPORT-EXPORT-20261110/result.json)。


```sh
# 0. 构建（首次先安装依赖：agent-sidecar 与 web/admin 各执行一次 npm ci）
mvn package
npm --prefix agent-sidecar run build

# 1. Java（终端一；java 不在 PATH 时用完整路径 /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java）
java -jar target/iot-agent-backend-0.1.0.jar \
  --spring.config.additional-location=file:./var/config/application.yaml
curl -s http://127.0.0.1:3381/healthz        # 健康后再起 Node

# 2. Node Agent Engine（终端二）
node agent-sidecar/dist/server.js --config ./var/config/agent-engine.yaml

# 3. 管理控制台（终端三）
cd web/admin && npm run dev                  # http://127.0.0.1:5185/login
```

配置来源（仓根非机密模板 `application.example.yaml`／`agent-engine.example.yaml`；私密副本 0600、Git 忽略，由部署所有者维护）：

- **Java `application.yaml`**：原生 Spring Boot 层级属性，含 `spring.datasource.url/username/password`、`spring.data.redis.url`、`agent.storage.mysql.enabled`、`agent.storage.redis.enabled`、`agent.bridge.base-url/run-timeout-ms`、`agent.task.credential-key`、`admin.config-secret-key/password/origin/config-file/password-minimum` 及既有原生 `iot.*`。Java 独占数据库、任务凭据、管理员与配置加密等安全根；桥接 Token 经 `spring.config.import: 'configtree:/absolute/path/to/shared/'` 从共享 secret 目录引入。
- **共享桥接 Token**：专用共享目录下唯一的私密文件 `agent.bridge.token`，Java（configtree 导入）与 Node（`bridge.token-file`）读同一文件；Node 只拿到这个文件，不接触 Java 的 `application.yaml`、管理员或数据库凭据。
- **Node `agent-engine.yaml`**：仅通用传输／资源设置，全部 kebab-case：`server.host/port`、`bridge.token-file`、`java.tools-url`、`java.sessions-url`（缺省按 `tools-url` 派生）、`java.token-file`（缺省同 `bridge.token-file`，测试夹具可显式指定独立回调 token）、`resources.java-tool-timeout-ms/run-timeout-ms/heartbeat-ms`。
- 模型、指引、生成参数与观测设置不是启动输入：MySQL 管理的可管理业务修订仍是权威，Node 每次 `/run` 从 Java 获取完整模型定义快照，启动观测从 Java 私有接口拉取。
- 需要固定版本时，把 jar 与 `agent-sidecar/dist` 复制到 `var/` 下带时间戳的目录，再用同一命令启动该副本，避免运行中的产物被下一次 `mvn package` 覆盖。
- **独立同步安全根**：公司目录要求 Java 外置 YAML 的 `agent.local-data.credential-key`（64位十六进制，独立于任务密钥；无默认），部署所有者须稳定保管，不传给Node。2026-11-06固定实例已配置并初始化本地同步表；各领域实现与未验边界仍按[本地数据执行图](.scratch/local-data-sync/README.md)推进，启动成功不等于14票全部完成。

控制台监听 `0.0.0.0:5185`（strict port，被占用直接失败，不漂移到 5186），仅代理 `/api/admin` 到 `127.0.0.1:3381`（`ADMIN_API_TARGET` 可覆盖）；`ADMIN_PROXY_ORIGIN`（默认 `http://127.0.0.1:5185`）必须等于 Java 的 `admin.origin`，而不是填 `0.0.0.0`。只有与来访 Host 同源的 HTTP Origin 才规范化为 Java 配置的源；外部 Origin 不改写，管理员密码、Cookie 和 CSRF 校验仍由 Java 执行。仅限可信开发网络；不是生产部署或公网 HTTPS 方案。Node 内部监听不因此对外开放。

历史统一启动器（`tools/start-fixed-backend.py`、launch-YAML 清单）已于 2026-11-06 退役并随 `tools/` 删除；当时的启动／重入、局域网登录验证记录见[控制台统一启动验证（历史）](.scratch/fixed-console-startup/README.md)。

## 本地基础设施（MySQL／Redis）

Compose 只是基础设施前置：标准命令、原生 Compose secrets 文件注入 MySQL 密码，不用变量注入，也不用 `.env`：

```sh
# 秘密文件一次性创建（0600，Git 忽略；业务预览栈在 var/config/infrastructure/business/）
# var/config/infrastructure/local/mysql-password 与 mysql-root-password，各写一个密码

docker compose --env-file /dev/null -f compose.yaml config        # 解析检查
docker compose --env-file /dev/null -f compose.yaml up -d mysql redis
```

- MySQL 8.4：`127.0.0.1:3307/iot_agent`；Redis 7.4：`127.0.0.1:6380`。MySQL 初始化脚本 `src/main/resources/db/mysql/agent-facts.sql`；持久卷 `var/docker/mysql`、`var/docker/redis`（均被忽略）。项目名、端口与卷保持不变。
- 密码只经 `var/config/infrastructure/local/mysql-password`／`mysql-root-password`（业务栈 `var/config/infrastructure/business/` 同名文件）提供；`--env-file /dev/null` 固定禁用隐式 `.env` 发现，secrets 文件缺失时 compose 直接失败，无弱默认。
- 已初始化卷的凭据以卷当初的初始化值为准：文件值不匹配时连接／健康检查显式失败，不自动重置或重建卷。仅首次新库在确认容器与卷归属本项目后执行 `up -d`。
- 业务预览栈单独执行：`docker compose --env-file /dev/null -f compose.business-preview.yaml …`（`127.0.0.1:3338`／`6398`，独立命名卷，凭据不轮换）。
- 两套开发MySQL均以 `--skip-log-bin` 启动：上游数据是可重建的本地读取副本，无实际复制／订阅用途时不保留binlog；正常事务、redo／undo及任务等记录仍持久化。现存历史容器已在原挂载上通过 `/etc/mysql/conf.d/99-iot-no-binlog.cnf` 生效，后续按仓内Compose重建也保持禁用。已有日志须通过MySQL管理操作清理，不能直接删数据文件；[实施与实测](.scratch/sync-storage-governance/README.md)。
- 原只读上游探针条目（`upstream-probe`）随 `tools/` 退役；其历史观察保留在[本地后端准备记录](docs/local-backend-preparation.md)。

Java 构建、测试、启动和本地 healthz 验证命令见 [AGENTS.md](./AGENTS.md)。独立 sidecar 的安装、构建、启动和私有 HTTP contract 见 [agent-sidecar/README.md](./agent-sidecar/README.md)。

## 技术规范对齐与局部模块改进

- 控制指令由 `iot.DeviceCommands` 统一校验，动作提案、编辑、任务与网关均拒绝小数、文本和溢出整数；版本参数由 `error.RequestValues` 区分缺省与显式非法值。HTTP 字段、响应及错误语义见 [Java 契约补充](docs/api/java-contract.md)。
- 任务报告汇总实现隐藏在 `task.internal.TaskReportSummaries`；零告警的合法 null 保留。任务详情与报告共用当前授权裁剪，部分设备失权时不公开无法拆分的汇总／原始事实。
- 结构资源与旧库升级统一归 `schema.SchemaMigrations`：规范代理主键、审计时间和字段注释，保留自然唯一键、Pi fencing／提交回执与一次性迁移记录。候选集使用会话内持久序号行分配句柄及唯一索引；不会逐次扫描全部历史句柄。
- 历史：2026-11-06凌晨原生启动改造源码快照经`mvn clean package`640项通过；当时Node34项、启动安全8项、进程／重启及Pi契约30/30通过，使用`var/fixed-native-20261106-014739/`。该旧发布副本已由上述18:46最新源码版本替换并依用户要求删除；[历史运行证据](.scratch/technical-spec-compliance/evidence/native-startup-20261106.json)保留，不作为当前版本通过证明。
- 技术规范初次改进及 2026-11-05 的637项构建／旧固定版本记录作为历史保留：[初次改进验证](.scratch/technical-spec-compliance/evidence/result.json)／[当轮固定实例证据](.scratch/technical-spec-compliance/evidence/fixed-latest-20261105.json)。以上均为工程证据，不是生产部署或真实微信／设备执行验收。


## MySQL Pi Session cutover

- Direct cutover, without importing old conversations. A transactional, one-time migration retires old public conversations; historical turns, drafts, tasks and reports are not deleted. Subsequent restarts preserve new MySQL conversations within the six-hour inactivity retention policy.
- Start Node with the conventional command (`node dist/server.js --config <agent-engine.yaml>`, see [常规本地启动](#常规本地启动)); its YAML carries only generic engine settings, never Java/MySQL/Redis/admin credentials. Business capability changes belong in Java, not Node.
- Administration exposes MySQL session health only. SQLite file backup/restore/capacity operations are removed. Back up the whole authoritative MySQL schema, including business facts, Pi tables and `storage_migrations`; restore into an independent database before any live cutover.
- Current scope, execution tickets and **real UI evidence / unresolved cases**: [Pi Session acceptance](.scratch/pi-session-mysql/acceptance.md). Tests and HTTP success are engineering diagnostics, never business acceptance.

## Task business maintenance

- `task/internal/TaskProposalNormalizer` owns one-time, interval, recurring and conditional task definitions; `TaskFacade` owns human confirmation and plan/task management. Model tool calls cannot grant confirmation authority.
- 新建任务按“日期或周期＋简要地点范围＋简要任务”命名，如“每周五早上办公室开灯”“11月7日公司用电报告推送”“工作日上午26楼运营部过道开灯”。名称概括业务，回路编号、设备全名、精确时刻和报告统计期间留在原结构化安排中，简称不改变实际成员或统计范围。前端既有`deviceDisplayName`继续负责设备行简称，不将该设备截短规则搬成任务命名算法；组件、展示文字、`title/label`读取优先级及Java命名归一逻辑均不变，不批量改写历史任务。
- 修改提案保留跨轮澄清前的改动意图；`template.label`仍为必填，按新安排概括名称，不在旧名后拼修改日志。仅下一次使用具体日期和新时段，不改父周期名称；整个周期使用新周期名称。改时间通过`template.phases[].at`表达，保持中国标准时间与时区一致；仅改名不必提交阶段。沿用既有前后快照、名称同步、阶段绑定和确认保存逻辑，不修改调度。源码只调整`shared-instructions.txt`及工具说明，未修改字段结构；2026-11-06启动最新源码后，经用户明确选择，仅同步管理端两段任务指引，修订14已生效，其余指引／模型参数保持。Office与Company实际指引预览均已核对，真实小程序仍未验收。[本轮隔离模型与契约验证](.scratch/task-title-naming/evidence/TITLE-02/result.json)保留最终输出、先前失败尝试和全量测试的既有失败，不作为真实小程序验收。原修改卡验证见[MP交接](../mp/plan/2026-10-27-task-presentation-handoff.md#2026-11-06修改确认卡快照修复)。
- 编辑提案必须带非空 `template`，两端工具 Schema 只对 `edit` 要求该字段，暂停／恢复／删除／跳过保持原契约。`TaskFacade` 拒绝缺失修改内容，保存前默认比较全部定义字段，包括报告请求、核验要求和任务目标；仅排除已有来源证明的运行元数据，并归一名称别名、阶段 ID 和等价时区写法。无实际变化返回 `TASK_CHANGE_UNCHANGED`，不生成卡片、不改原任务；正常仅改名称仍可提案。仅改时间时各阶段只给 `at`，后端按原数量和顺序复用完整阶段，保留设备动作、控制绑定及读取／核验要求；也兼容重传相同设备／指令的时间修改，不让模型重建控制绑定，重复指令的平铺别名不算实际变更。阶段数量变化不能借用旧绑定，增删或改动作仍须完整定义。模型跨轮澄清后须保留先前修改意图，并依据工具返回的 `before/after` 描述待确认提案，不能只在正文宣称新时间，也不得擅自用取消再新建代替修改。本修复不改变重新排期或当天执行规则。
- `TaskScanner` and `task/internal/RealTaskExecutionGateway` own durable checkpoint execution, interruption/recovery, device reservations and terminal report/notification timing. A completed checkpoint does not end an open interval.
- Java requires a stable `agent.task.credential-key` (64 hex characters) to encrypt task/plan execution credentials. Missing configuration fails confirmation closed; never rotate the key while encrypted identities exist or pass it to Node.
- `TaskPhaseIds` assigns only missing durable phase identities, shared by normal confirmation and interrupted-confirmation recovery.
- `TaskDetailProjector` owns the shared detail/list/calendar/timeline/report shape. Frontend cards retain the confirmed task/plan identity instead of polling a proposal ID as if it were a task.
- 2026-11-09：周期列表／月历按窗口展开全部合法发生日，周几／截止日与调度规则一致。单次修改保存与后续推进均使用覆盖后的实际时间；重启错过的阶段保留 `MISSED`，已被旧实现遗留的明确覆盖安排补回错过事实，不重发过去控制。MySQL 执行身份复制的幂等 SQL 已消除列歧义，避免物化事务整体回滚。设计、实跑和当前固定实例见[可靠性修复记录](.scratch/energy-weekday-reliability/README.md)。
- 2026-11-09：确认提案的目录指纹统一按 JSON 结构处理实时 `JsonNode` 与本地 `Map/List`，不因存取表示差异误判授权变化；真实绑定变化仍拒绝。放弃未确认提案保留当前版本CAS与归属校验，不再因超过确认有效期而拒绝放弃；已确认任务不走此入口取消。前端同步版本参数及结构化错误提示。见[确认与放弃修复证据](.scratch/task-confirmation-reliability/README.md)。
- 2026-11-09：Company／Office 新任务统一优先保存已核验身份姓名，不再对 Company 固定留空，模型不能覆盖创建人。已有空姓名的2个计划及5条任务已按核验身份受控补齐，仅改姓名、不改版本／排期／归属／状态；固定3381仅替换 `TaskFacade.class`，Node及配置不变。78项定向回归通过，扩展构建700通过／29跳过（排除3个既有无关失败）；真实微信两条原计划分别显示马云飞和刘志龙。见[修复范围、备份与验收证据](.scratch/task-creator-names/README.md)。
- 2026-11-09：区域时间轴按真实阶段跨度／显式区间过滤日期窗口，兼容内部JsonNode与公开Map区间；即时历史不再被每天重复返回。前端跨日按具体记录ID合并并保留较新版本，不折叠同一计划的不同日期；即时历史的“操作草案”旧标签仅在读取投影改为“操作记录”，不改写事实。真实区域截图与验证边界见[区域重复卡修复](.scratch/region-timeline-reliability/README.md)。
- `storage` factories expose MySQL task facts, phases, plans, projections, confirmation recovery and completed Office action history; JDBC implementations stay under `storage/internal`. Confirmation and management writes are transactional; external authorization/device calls stay outside those transactions.
- Task detail's “现在状态” uses the existing credential/source/device-set-isolated `DEVICE_STATES` read snapshots, not request-thread upstream crawls. Task visibility and lighting kind are checked against the held authorized catalog; background loaders recheck the live scope. Durable phase start/end facts change the state-read key, so a new phase cannot reuse a pre-execution observation. Cold reads report preparation, and state failures never fabricate “off”. Office state loaders read only the requested kind/office pages, with at most six concurrent reads; actual control authorization and verification remain uncached. See [read-only runtime and miniapp evidence](.scratch/agent-business-recovery/evidence/TASK-STATE-READ-01/result.json).
- Office region attention projects actual unread exceptional task results from local facts on every read; directory snapshots never cache its boolean. Only `partial/failed/interrupted/missed` qualify, using current read authorization and per-viewer monotonic watermarks. Region acknowledgement rechecks fresh Office membership. See [parity checks and frontend limits](.scratch/agent-business-recovery/evidence/REGION-ATTENTION-01/README.md).
- `GET /api/agent/safety-events/latest?source=company&type=gas&deviceId=<optional>&to=<zoned-ISO-time>` resolves a static message entry to the latest authorized safety record at the captured query time (`gas/smoke/water`). A supplied device never widens to another device/type. The existing identity-isolated `SAFETY_EVENTS` snapshots distinguish this selection and query time; pagination is scanned to completion (at most 20 pages), and incomplete/failed reads never masquerade as an empty ledger. The response contains a real `safety_event` reference or null after a complete empty scan; it does not send a message or control a device. Regression: `LatestSafetyAlertContractTest`; real miniapp entry evidence and unaccepted native-card/login branches are recorded in the frontend repository’s `.scratch/message-card-jump/spec.md`.
- Runtime is read-only parity evidence. Task, report and notification behavior belongs in Java; the generic Node/Pi engine is not the business extension point.
- 2026-11-09：监控候选检索只保留候选与处理记录，不提前读状态或生成视频卡；正式入口由 `read_device_states` 按已选目标生成，明确整层查看仍支持该范围列表。未知在线态保留未知入口，真正失败不生成正常卡。工具与公司监控指引区分单台／列表和“入口已生成／画面已播放”；既有管理指引只同步监控段落，其余配置不变。前端隐藏与摄像头名称相同的位置行。见[监控卡修复及微信验收边界](../mp/.scratch/video-selection/issues/VIDEO-01.md)。
- Engineering checks and real miniapp acceptance are tracked separately in [business recovery](.scratch/agent-business-recovery/README.md). Missing UI evidence remains **not accepted**, even when the build and contract tests pass.

### 确认卡与正文分工（2026-11-05）

- Tool Bridge 保留完整 result 供业务事件／确认卡使用；任务、修改、取消及操作提案通过既有 modelResult 提供目标摘要与身份、排除和状态未知，不再把逐设备清单重复写入模型工具正文。候选任务第一项仍允许继续编排第二项。
- 正常确认回复采用 confirmationReply 一句话；固定操作回执不再重复类别／台数。正常卡片以外的未完成目标、歧义、未知、失败及用户要求的解释必须保留；不做前端字符串删句，不放行无产物空答复，不改变确认或执行。
- 前端公共清单、阶段差异和预检参考改进及真实微信证据见[前端交接](../mp/plan/2026-10-27-task-presentation-handoff.md#2026-11-05确认单与正文表达分工)。本片 Java 只替换固定3381发布物中的两个类和三份指引，保留其他发布内容、Node3382及模型设置；回退配置在 `var/task-expression-20261105-144844/launch-before.yaml`，不提交凭据。
- 工程：共享正常化／HTTP任务提案在完整599项检查中通过（574通过、25跳过）；最终compile和Java可运行程序覆盖摘要、未知／排除、原始清单、两任务延续与确认边界。并行新增告警测试的编译错误不属于本片，未绕过或删除；工程证据不替代实际模型与微信验收。


## Energy history composition

- Daily, meter statistics, comparisons and reports read the same persisted raw observations through `EnergyHistory`. The requesting owner's dedicated meter grant remains mandatory; published samples and complete coverage can be reused across owners only within the same gateway, tenant and source. Duplicate source records resolve to their latest published revision. Boundary samples, exact decimals, statistics and upstream concurrency remain unchanged.
- A newly authorized owner reuses fresh data and existing complete history without another full scan. Background collection still refreshes current observations and performs the existing nightly full reconciliation; genuinely uncovered meters remain preparing/partial. No raw-history copy, deletion, new cache or schema migration is introduced. The earlier [energy cold-load evidence](.scratch/energy-cold-load/evidence/ENERGY-COLD-01/README.md) is historical; current implementation and acceptance limits are in the [reliability fix record](.scratch/energy-weekday-reliability/README.md).

## Office directory pagination

- Office 照明关联目录分页显式使用 `sort=id,asc`，避免默认顺序不稳定造成跨页重复、目录无法准备及任务持续加载。完整性校验、授权过滤、其他目录接口和前端保持不变。
- 固定 Java 3381 运行包为 `var/fixed-office-pagination-20261107-203057/backend.jar`，仅在原运行包上替换该路由类，未混入工作区其他改动；Node 3382 与配置不变。构建、回归边界和真实微信界面证据见 [OFFICE-DIRECTORY-PAGE-01](.scratch/local-data-sync/evidence/OFFICE-DIRECTORY-PAGE-01/README.md)。
