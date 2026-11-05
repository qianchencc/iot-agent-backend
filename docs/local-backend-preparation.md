# 本地后端准备记录

> 2026-11-06 更新：启动与基础设施入口已切换为常规命令（Java `java -jar … --spring.config.additional-location`、Node `node dist/server.js --config`、Compose 原生 secrets 文件），`tools/` 启动器与 launch-YAML 已删除。规范见[配置与数据治理](configuration-governance.md)，三进程启动命令见[仓根常规本地启动](../README.md#常规本地启动)。本文早期命令与 `.scratch` 历史证据保持原貌。

## 边界

- 只准备本机 `iot-agent-backend`、Node Agent Engine 探针、MySQL 和 Redis。
- 客户云端生产后端未连接、未部署、未重启、未修改。
- 客户生产上游 `https://ncim.577119.com` 仅执行身份和设备目录只读请求；不执行控制、任务 scanner、通知或配置变更。
- 凭据只存在于 Git 忽略的私密文件（均 0600，由部署所有者维护）：Java 外置 `application.yaml`、Node 外置 `agent-engine.yaml`、共享 `agent.bridge.token` 及 Compose secrets `var/config/infrastructure/**`。仓内模板 `application.example.yaml`／`agent-engine.example.yaml` 不含任何真实凭据，必填秘密留空；缺失时相关进程 fail closed，不以弱默认启动。不提交、不打印、不写入测试产物。

## 当前入口

### 本地依赖（MySQL／Redis）

Compose 为基础设施前置。MySQL 密码经原生 Compose secrets 文件提供（`var/config/infrastructure/local/mysql-password`、`mysql-root-password`，0600，Git 忽略；首次创建时写入对应密码）：

```sh
docker compose --env-file /dev/null -f compose.yaml config       # 解析检查
docker compose --env-file /dev/null -f compose.yaml up -d mysql redis
```

- MySQL 8.4：`127.0.0.1:3307/iot_agent`；Redis 7.4：`127.0.0.1:6380`。
- MySQL 初始化脚本：`src/main/resources/db/mysql/agent-facts.sql`；持久卷：`var/docker/mysql`、`var/docker/redis`（均被忽略）。
- 密码不走变量注入；`--env-file /dev/null` 固定禁用隐式 `.env` 发现，secrets 文件缺失时 compose 直接失败，无弱默认。
- 已初始化卷的凭据以卷当初的初始化值为准：文件值不匹配时连接／健康检查显式失败，不自动重置或重建卷。仅首次新库在确认容器与卷归属本项目后执行 `up -d`。
- 业务预览栈单独执行 `docker compose --env-file /dev/null -f compose.business-preview.yaml …`（`127.0.0.1:3338`／`6398`，secrets 在 `var/config/infrastructure/business/` 同名文件，独立命名卷，凭据不做轮换）。

（历史）本机 Colima Docker daemon 原来指向不可达的 `10.5.12.112:7890`，已切换到可达的 `host.lima.internal:7897`，仅影响本机 Docker 拉镜像链路。

### 后端进程

三个进程均以常规命令启动，无启动器脚本、无 launch 清单：Java `java -jar <backend.jar> --spring.config.additional-location=file:/absolute/application.yaml`，Node `node dist/server.js --config /absolute/agent-engine.yaml`，控制台在 `web/admin` 执行 `npm run dev`。外置 YAML 键、共享 `agent.bridge.token` 文件与固定端口 `3381`／`3382`／`5185` 见[仓根常规本地启动](../README.md#常规本地启动)。模型与观测设置不是启动输入：管理控制台保存的 MySQL 配置修订是权威，Node 每次 `/run` 携带完整模型定义快照，启动观测从 Java 私有接口获取。

### 真实客户上游只读（已退役）

原 `upstream-probe` 条目（经 `tools/run-local.py` 执行 `tools/probe-upstream-readonly.mjs`，持浏览器捕获的 `IOT_GATEWAY_BASE_URL`／`IOT_TEST_AUTHORIZATION`／`IOT_TEST_TENANTCODE` 临时只读身份）已随 `tools/` 于 2026-11-06 退役。历史观察结果见下文“已验证”。如需再次只读探测，另行以受控方式执行：凭据不进入任何后端进程环境，只输出主机名、HTTP/业务码、数量和 `principalIdPresent`，不输出 token、用户资料、设备 ID 或原始响应。

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

当时以 `node --env-file=.env tools/probe-upstream-readonly.mjs` 重复探针（后曾收编为 `tools/run-local.py` 的 `upstream-probe` 条目，两者均已退役），已观察到：

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
