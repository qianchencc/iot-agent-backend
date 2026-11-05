# 本地后端准备记录

> 2026-11-05 更新：本机根 `.env` 已分类归档并删除，必要配置已迁入私密 YAML，[清理与运行证据](../.scratch/root-dotenv-retirement/evidence/result.json)。规范见[配置与数据治理](configuration-governance.md)。旧 `.env.example` 已删除，仓库不再提供任何“复制为 `.env`”的模板。本文下述命令为现行入口；早期观察与 `.scratch` 历史证据保持原貌。

## 边界

- 只准备本机 `iot-agent-backend`、Node Agent Engine 探针、MySQL 和 Redis。
- 客户云端生产后端未连接、未部署、未重启、未修改。
- 客户生产上游 `https://ncim.577119.com` 仅执行身份和设备目录只读请求；不执行控制、任务 scanner、通知或配置变更。
- 凭据只存在于 Git 忽略的私密启动 YAML：`var/fixed-backend/launch.yaml` 与 `var/local-tools/launch.yaml`（均 0600，由部署所有者维护）。仓库内模板 `fixed-backend.example.yaml`／`local-tools.example.yaml` 不含任何真实凭据，必填秘密留空；缺失时相关入口 fail closed，不以弱默认启动。不提交、不打印、不写入测试产物。

## 当前入口

### 本地依赖（MySQL／Redis）

将模板 `local-tools.example.yaml` 复制为私密 `var/local-tools/launch.yaml`（0600）并填入必填显式凭据后：

```sh
python3 tools/run-local.py --config var/local-tools/launch.yaml --run infrastructure-local --check
python3 tools/run-local.py --config var/local-tools/launch.yaml --run infrastructure-local -- config --quiet
```

- MySQL 8.4：`127.0.0.1:3307/iot_agent`；Redis 7.4：`127.0.0.1:6380`。
- MySQL 初始化脚本：`src/main/resources/db/mysql/agent-facts.sql`；持久卷：`var/docker/mysql`、`var/docker/redis`（均被忽略）。
- `compose.yaml` 不再提供 `MYSQL_PASSWORD`／`MYSQL_ROOT_PASSWORD` 弱默认：必须经条目环境显式注入，缺失时 compose 直接报错；非机密默认（库名、账号名、端口）保留在 compose 文件内。
- compose 条目固定 `--env-file /dev/null` 且 `COMPOSE_DISABLE_ENV_FILE='1'`：目录下即使残留 `.env` 也不会被隐式加载。
- 已初始化卷的凭据以卷当初的初始化值为准：注入值不匹配时连接／健康检查显式失败，不自动重置或重建卷。仅首次新库在确认容器与卷归属本项目后执行 `-- up -d mysql redis`。
- 业务预览栈用 `--run infrastructure-business`（`compose.business-preview.yaml`；`BUSINESS_MYSQL_PASSWORD`／`BUSINESS_MYSQL_ROOT_PASSWORD` 显式注入，独立命名卷，凭据不做轮换）。

（历史）本机 Colima Docker daemon 原来指向不可达的 `10.5.12.112:7890`，已切换到可达的 `host.lima.internal:7897`，仅影响本机 Docker 拉镜像链路。

### 模型与 Pi

模型与观测设置不再是环境输入：管理控制台保存的 MySQL 配置修订是权威，Node 每次 `/run` 携带完整模型定义快照，启动观测从 Java 私有接口获取。历史原型阶段曾以 `.env` 注入 `MODEL_BASE_URL`／`MODEL_API_KEY`／`MODEL_ID`／`AGENT_THINKING_LEVEL` 并运行 `.scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/sidecar.mjs`（真实 `pi-agent-core` + `pi-ai`，但探针仅提供 `echo_tool`，不能冒充完整业务 Tool Bridge）；该入口已被固定后端启动器取代：

```sh
python3 tools/start-fixed-backend.py   # 私密配置 var/fixed-backend/launch.yaml；模板 fixed-backend.example.yaml
```

### 真实客户上游只读

```sh
python3 tools/run-local.py --config var/local-tools/launch.yaml --run upstream-probe
```

`upstream-probe` 在独立条目中持有 `IOT_GATEWAY_BASE_URL`／`IOT_TEST_AUTHORIZATION`／`IOT_TEST_TENANTCODE`（浏览器捕获的临时只读身份），不进入任何后端进程环境。该探针只输出主机名、HTTP/业务码、数量和 `principalIdPresent`，不输出 token、用户资料、设备 ID 或原始响应。

## 已验证（历史记录，当时的命令形式已被上文取代）

### 真实模型与 Pi / Java Tool Bridge

通过 Java bridge probe 发起过一次真实模型运行（当时经 `.env` 注入模型配置），观察到：

```text
run.started
→ pi.agent_start
→ pi.tool_execution_start
→ Java echo_tool
→ pi.tool_execution_end
→ message.delta
→ message.completed
→ run.completed
```

模型返回了可展示文本，说明本地模型配置、`pi-agent-core`、`pi-ai`、Node sidecar、Java Tool Bridge 和 NDJSON 转换链路已连通。

### 真实客户上游只读

当时以 `node --env-file=.env tools/probe-upstream-readonly.mjs` 重复探针（现为 `upstream-probe` 条目），已观察到：

- `/sso-api/account/me`：HTTP 200，业务码 200，存在 `content.id`；
- `/micro-api/api/office/office/member/my/office`：HTTP 200，返回 1 个当前授权办公室；
- Office lighting：成功读取 3 条目录记录；
- Office access：成功读取 2 条目录记录；
- Office AC：成功读取 2 条目录记录；
- Office video 路径本次返回非成功业务状态，记录为 0，不作“无视频设备”推断。

## 尚未宣称完成（当时记录，多项此后已交付，见 README 与治理证据）

- Java 已迁移 Runtime 的 `/sso-api/account/me` 身份核验和稳定 principal owner 计算；当时 `WebClientIotAdapter` 仍是 scope shell，多源 Office 目录聚合、分页和 `content` 归一化此后的交付见 README。
- 当时 Pi 探针的 `echo_tool` 不是业务工具集合；业务工具此后由 Java Tool Bridge 承载。
- 当时 Java 业务事实仓储尚未切换到真实 JDBC/Redis 实现；存储此后按 MySQL 事实库与 Pi Session 交付。
- 未执行任何真实设备控制或任务执行。
