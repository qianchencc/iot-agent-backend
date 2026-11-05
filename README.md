# iot-agent-backend

Java public backend plus an independent Node `agent-sidecar`: Spring Boot 3.5 / Java 21+ and Node 22. Java owns customer HTTP, identity, business instructions/tools, device/task/report behavior, MySQL business facts **and durable Pi Sessions**. Redis is coordination/cache, not session authority. Node owns only the generic Pi loop, streaming, tool transport and interruption checkpoints; no SQLite, database credentials or local session volume. `../iot-agent-runtime` is read-only behavior evidence, never a runtime dependency.

架构约定、模块边界纪律与 Runtime→Java 模块映射见 [AGENTS.md](./AGENTS.md)；issue tracker 与领域文档规则见 `docs/agents/`。

控制台已切换为 Vue3 / JavaScript / Element Plus，提供独立部署管理员登录、独立手机号名单、实际参数配置与按成员组织的公司／办公室操作审计。启动入口使用显式 YAML，不加载 `.env`；Node 从 Java 获取模型运行定义与启动观测快照，不再使用模型／Langfuse 环境回退。字段、启动方式与隔离验证证据见[控制台治理入口](.scratch/admin-console-governance/README.md)和[维护手册](web/admin/docs/maintenance.md)；未部署到生产，主开发实例不因本轮验证而切换。

Java 构建、测试、启动和本地 healthz 验证命令见 [AGENTS.md](./AGENTS.md)。独立 sidecar 的安装、构建、启动和私有 HTTP contract 见 [agent-sidecar/README.md](./agent-sidecar/README.md)。

## 技术规范对齐与局部模块改进

- 控制指令由 `iot.DeviceCommands` 统一校验，动作提案、编辑、任务与网关均拒绝小数、文本和溢出整数；版本参数由 `error.RequestValues` 区分缺省与显式非法值。HTTP 字段、响应及错误语义见 [Java 契约补充](docs/api/java-contract.md)。
- 任务报告汇总实现隐藏在 `task.internal.TaskReportSummaries`；零告警的合法 null 保留。任务详情与报告共用当前授权裁剪，部分设备失权时不公开无法拆分的汇总／原始事实。
- 结构资源与旧库升级统一归 `schema.SchemaMigrations`：规范代理主键、审计时间和字段注释，保留自然唯一键、Pi fencing／提交回执与一次性迁移记录。候选集使用会话内持久序号行分配句柄及唯一索引；不会逐次扫描全部历史句柄。
- 当前完整源码干净隔离构建 `mvn package`：637 项、0 失败／错误／跳过；Node 34 项和 Pi Session 工程契约 30/30 通过。固定 Java `3381`／Node `3382` 已切换到 `var/fixed-latest-20261105-181818/` 的冻结产物，真实业务库升级前完成私密备份和独立恢复预演，升级后33表及原业务事实核验通过。[初次改进验证](.scratch/technical-spec-compliance/evidence/result.json)／[最新固定实例证据](.scratch/technical-spec-compliance/evidence/fixed-latest-20261105.json)。这是工程证据，不是生产部署或真实微信／设备执行验收。


`java` 不在 PATH：直接运行 jar 用 `/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java`。

## 固定开发后端：tmux 常驻

固定 Java `3381`、Node `3382` 和管理控制台 `5185` 统一通过下列入口启动，不再挂在 Agent 临时服务或普通前台终端上：

```sh
/Users/qianchen/anaconda3/bin/python tools/start-fixed-backend.py
tmux -L iot-agent-backend attach -t fixed-backend
```

- 需要 tmux、Python 3 和 PyYAML；本机上述 Python 已具备依赖。默认部署会话含 `java`、`node`、`console` 三个窗口；`Ctrl-b d` 只脱离，不停止服务。控制台依赖先在 `web/admin` 执行 `npm ci` 安装。
- 私密部署配置为 `var/fixed-backend/launch.yaml`（0600，Git 忽略），指定产物、工作目录和分进程环境；非机密模板为仓库内 `fixed-backend.example.yaml`，必填秘密（桥接 Token、MySQL 密码、`TASK_CREDENTIAL_KEY`／`CONFIG_SECRET_KEY`／管理员密码）留空，缺失即 fail closed。不加载 `.env`（旧 `.env.example` 已删除），不重建或自动升级后端源码；模型／可管理业务配置仍按现有 MySQL 修订消费。
- Java／Node直接启动配置指定的冻结产物；控制台启动配置指定的 Vite 入口，以 `web/admin` 为工作目录。各进程仅接收自己的显式环境，控制台不接收 Java／Node 的密码、数据库、模型或桥接凭据。
- 当前固定版本及 PID／SHA256 以[最新运行证据](.scratch/technical-spec-compliance/evidence/fixed-latest-20261105.json)为准；Java、Node 均在 `var/fixed-latest-20261105-181818/`，不运行可被 Maven 覆盖的 `target/` 产物。升级只改私有 YAML 的产物路径和 Node 工作目录，原分进程环境及控制台进程保持不变。
- 重复启动保留所有存活窗口，只补起已配置但缺失／退出的组件；端口被其他进程占用则拒绝替换，不杀占用者。状态查看：`tmux -L iot-agent-backend list-panes -s -t fixed-backend -F '#{window_name} pid=#{pane_pid} dead=#{pane_dead}'`。
- 控制台监听 `0.0.0.0:5185`，本机访问 `http://127.0.0.1:5185/login`，同网访问 `http://<本机局域网IP>:5185/login`。仅限可信开发网络；不是生产部署或公网 HTTPS 方案。Node 内部监听不因此对外开放。
- 控制台的 `console` 部署段如下；路径按实际安装位置填写。`ADMIN_PROXY_ORIGIN` 必须等于 Java 的 `admin.origin`／`ADMIN_ORIGIN`，而不是填 `0.0.0.0`。自定义后端专用配置不含 `console` 时只启动 Java／Node。

```yaml
console:
  executable: /opt/homebrew/opt/node@22/bin/node
  artifact: /absolute/repo/web/admin/node_modules/vite/bin/vite.js
  working_directory: /absolute/repo/web/admin
  environment:
    PATH: /opt/homebrew/opt/node@22/bin:/opt/homebrew/bin:/usr/bin:/bin
    ADMIN_API_TARGET: http://127.0.0.1:3381
    ADMIN_PROXY_ORIGIN: http://127.0.0.1:5185
```

- Vite 仅代理 `/api/admin`。只有与来访 Host 同源的 HTTP Origin 才规范化为 Java 配置的源；外部 Origin 不改写，管理员密码、Cookie 和 CSRF 校验仍由 Java 执行。端口被占用时直接失败，不自动漂移到5186。
- tmux 解决终端／Agent 退出导致的失活，不提供进程崩溃自动重启或系统重启自启动。
- 启动／重入、局域网地址真实登录及访问边界证据见[控制台统一启动验证](.scratch/fixed-console-startup/README.md)。

## 本地基础设施与上游只读探针

本地 MySQL／Redis 与只读上游探针不再有 `.env` 入口。非机密模板 `local-tools.example.yaml` 复制为私密 `var/local-tools/launch.yaml`（0600）并填入必填显式凭据后，经 `tools/run-local.py` 执行；该入口只执行选定条目、只传条目自身的显式环境：

```sh
python3 tools/run-local.py --config var/local-tools/launch.yaml --run infrastructure-local --check
python3 tools/run-local.py --config var/local-tools/launch.yaml --run infrastructure-local -- config --quiet
python3 tools/run-local.py --config var/local-tools/launch.yaml --run upstream-probe
```

- `compose.yaml` 不再有 MySQL 密码弱默认：`MYSQL_PASSWORD`／`MYSQL_ROOT_PASSWORD` 必须显式注入（业务预览栈的 `BUSINESS_*` 同理，用 `--run infrastructure-business`）。compose 条目固定 `--env-file /dev/null` 并设置 `COMPOSE_DISABLE_ENV_FILE='1'`，目录下即使残留 `.env` 也不会被隐式加载。
- 已初始化的 `var/docker/mysql` 与业务预览卷保持当初的初始化凭据：注入值不匹配时连接／健康检查显式失败，不自动重置或重建卷。仅首次新库在确认容器／卷归属本项目后执行 `-- up -d mysql redis`。
- `upstream-probe` 在独立条目中持有 `IOT_GATEWAY_BASE_URL`／`IOT_TEST_AUTHORIZATION`／`IOT_TEST_TENANTCODE`（浏览器捕获的临时只读身份），只输出脱敏统计，不进入任何后端进程环境。

## MySQL Pi Session cutover

- Direct cutover, without importing old conversations. A transactional, one-time migration retires old public conversations; historical turns, drafts, tasks and reports are not deleted. Subsequent restarts preserve new MySQL conversations within the six-hour inactivity retention policy.
- Start Node through `tools/start-sidecar.py`; it strips Java/MySQL/Redis/admin credentials from the child environment. Business capability changes belong in Java, not Node.
- Administration exposes MySQL session health only. SQLite file backup/restore/capacity operations are removed. Back up the whole authoritative MySQL schema, including business facts, Pi tables and `storage_migrations`; restore into an independent database before any live cutover.
- Current scope, execution tickets and **real UI evidence / unresolved cases**: [Pi Session acceptance](.scratch/pi-session-mysql/acceptance.md). Tests and HTTP success are engineering diagnostics, never business acceptance.

## Task business maintenance

- `task/internal/TaskProposalNormalizer` owns one-time, interval, recurring and conditional task definitions; `TaskFacade` owns human confirmation and plan/task management. Model tool calls cannot grant confirmation authority.
- 新建任务命名采用“区域／对象＋动作或检查目标”，日期、时段和重复规则独立展示，不把整句请求或“今天／明天／后天”等相对日期当名称。创建入口同时规范化`title`和`label`：已有相对日期前缀的一次性任务按结构化执行时间转为上海时区具体日期，周期任务去掉该前缀、不固定首日日期；不改变执行时刻，不批量改写历史任务。见[命名工程验证](.scratch/task-title-naming/evidence/TITLE-01/result.json)，无真实任务确认或设备下发，前端创建路径不据此记为已验收。
- `TaskScanner` and `task/internal/RealTaskExecutionGateway` own durable checkpoint execution, interruption/recovery, device reservations and terminal report/notification timing. A completed checkpoint does not end an open interval.
- Java requires a stable `TASK_CREDENTIAL_KEY` (64 hex characters) to encrypt task/plan execution credentials. Missing configuration fails confirmation closed; never rotate the key while encrypted identities exist or pass it to Node.
- `TaskPhaseIds` assigns only missing durable phase identities, shared by normal confirmation and interrupted-confirmation recovery.
- `TaskDetailProjector` owns the shared detail/list/calendar/timeline/report shape. Frontend cards retain the confirmed task/plan identity instead of polling a proposal ID as if it were a task.
- `storage` factories expose MySQL task facts, phases, plans, projections, confirmation recovery and completed Office action history; JDBC implementations stay under `storage/internal`. Confirmation and management writes are transactional; external authorization/device calls stay outside those transactions.
- Task detail's “现在状态” uses the existing credential/source/device-set-isolated `DEVICE_STATES` read snapshots, not request-thread upstream crawls. Task visibility and lighting kind are checked against the held authorized catalog; background loaders recheck the live scope. Durable phase start/end facts change the state-read key, so a new phase cannot reuse a pre-execution observation. Cold reads report preparation, and state failures never fabricate “off”. Office state loaders read only the requested kind/office pages, with at most six concurrent reads; actual control authorization and verification remain uncached. See [read-only runtime and miniapp evidence](.scratch/agent-business-recovery/evidence/TASK-STATE-READ-01/result.json).
- Office region attention projects actual unread exceptional task results from local facts on every read; directory snapshots never cache its boolean. Only `partial/failed/interrupted/missed` qualify, using current read authorization and per-viewer monotonic watermarks. Region acknowledgement rechecks fresh Office membership. See [parity checks and frontend limits](.scratch/agent-business-recovery/evidence/REGION-ATTENTION-01/README.md).
- `GET /api/agent/safety-events/latest?source=company&type=gas&deviceId=<optional>&to=<zoned-ISO-time>` resolves a static message entry to the latest authorized safety record at the captured query time (`gas/smoke/water`). A supplied device never widens to another device/type. The existing identity-isolated `SAFETY_EVENTS` snapshots distinguish this selection and query time; pagination is scanned to completion (at most 20 pages), and incomplete/failed reads never masquerade as an empty ledger. The response contains a real `safety_event` reference or null after a complete empty scan; it does not send a message or control a device. Regression: `LatestSafetyAlertContractTest`; real miniapp entry evidence and unaccepted native-card/login branches are recorded in the frontend repository’s `.scratch/message-card-jump/spec.md`.
- Runtime is read-only parity evidence. Task, report and notification behavior belongs in Java; the generic Node/Pi engine is not the business extension point.
- Engineering checks and real miniapp acceptance are tracked separately in [business recovery](.scratch/agent-business-recovery/README.md). Missing UI evidence remains **not accepted**, even when the build and contract tests pass.

### 确认卡与正文分工（2026-11-05）

- Tool Bridge 保留完整 result 供业务事件／确认卡使用；任务、修改、取消及操作提案通过既有 modelResult 提供目标摘要与身份、排除和状态未知，不再把逐设备清单重复写入模型工具正文。候选任务第一项仍允许继续编排第二项。
- 正常确认回复采用 confirmationReply 一句话；固定操作回执不再重复类别／台数。正常卡片以外的未完成目标、歧义、未知、失败及用户要求的解释必须保留；不做前端字符串删句，不放行无产物空答复，不改变确认或执行。
- 前端公共清单、阶段差异和预检参考改进及真实微信证据见[前端交接](../mp/plan/2026-10-27-task-presentation-handoff.md#2026-11-05确认单与正文表达分工)。本片 Java 只替换固定3381发布物中的两个类和三份指引，保留其他发布内容、Node3382及模型设置；回退配置在 `var/task-expression-20261105-144844/launch-before.yaml`，不提交凭据。
- 工程：共享正常化／HTTP任务提案在完整599项检查中通过（574通过、25跳过）；最终compile和Java可运行程序覆盖摘要、未知／排除、原始清单、两任务延续与确认边界。并行新增告警测试的编译错误不属于本片，未绕过或删除；工程证据不替代实际模型与微信验收。


## Energy history composition

- Daily and meter statistics reuse the same identity- and authorized-meter-scoped natural-month raw samples. Comparisons and reports keep their existing calculation rules, left anchors, requested upper bounds and current-month refresh; upstream concurrency remains six. A failed shared stream does not discard healthy meters from best-effort reports or bypass authorization failures.
- First reads no longer sweep 24 months automatically. Current, selected and visible historical windows prepare on demand; the miniapp retains held results and explicitly labels preparation. Engineering proof, the energy-only fixed-development deployment and remaining visual acceptance limits are recorded in [ENERGY-COLD-01](.scratch/energy-cold-load/evidence/ENERGY-COLD-01/README.md).
