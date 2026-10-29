# 本地后端准备记录

## 边界

- 只准备本机 `iot-agent-backend`、Node Agent Engine 探针、MySQL 和 Redis。
- 客户云端生产后端未连接、未部署、未重启、未修改。
- 客户生产上游 `https://ncim.577119.com` 仅执行身份和设备目录只读请求；不执行控制、任务 scanner、通知或配置变更。
- `.env` 是本机 0600 文件，包含模型密钥和从 ego-lite 当前会话捕获的临时测试身份；不提交、不打印、不写入测试产物。

## 已准备

### 本地依赖

```sh
docker compose --env-file .env up -d mysql redis
docker compose --env-file .env ps
```

- MySQL 8.4：`127.0.0.1:3307/iot_agent`
- Redis 7.4：`127.0.0.1:6380`
- MySQL 初始化脚本：`src/main/resources/db/mysql/agent-facts.sql`
- 持久卷：`var/docker/mysql`、`var/docker/redis`（均被忽略）

本机 Colima Docker daemon 原来指向不可达的 `10.5.12.112:7890`。已在 Colima VM 内保存备份并切换到可达的 `host.lima.internal:7897`，仅影响本机 Docker 拉镜像链路。

### 模型与 Pi

`.env` 注入：

- `MODEL_BASE_URL`
- `MODEL_API_KEY`
- `MODEL_ID`
- `AGENT_THINKING_LEVEL`

`.scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/sidecar.mjs` 已改为使用真实 `pi-agent-core` + `pi-ai` Chat Completions 模型，而不是 faux provider。探针仍只提供 `echo_tool`，不能冒充完整业务 Tool Bridge。

启动探针：

```sh
set -a; . ./.env; set +a
node .scratch/iot-agent-backend-refactor/prototype/agent-engine-bridge/sidecar.mjs
```

探针只监听 `127.0.0.1:3211`。Java 探针可监听 `127.0.0.1:3201`，不会占用主后端 `3200`。

## 已验证

### 真实模型与 Pi / Java Tool Bridge

通过 Java bridge probe 发起了一次真实模型运行，观察到：

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

重复探针：

```sh
node --env-file=.env tools/probe-upstream-readonly.mjs
```

该探针只输出主机名、HTTP/业务码、数量和 `principalIdPresent`，不输出 token、用户资料、设备 ID 或原始响应。

已观察到：

- `/sso-api/account/me`：HTTP 200，业务码 200，存在 `content.id`；
- `/micro-api/api/office/office/member/my/office`：HTTP 200，返回 1 个当前授权办公室；
- Office lighting：成功读取 3 条目录记录；
- Office access：成功读取 2 条目录记录；
- Office AC：成功读取 2 条目录记录；
- Office video 路径本次返回非成功业务状态，记录为 0，不作“无视频设备”推断。

## 尚未宣称完成

- Java 已迁移 Runtime 的 `/sso-api/account/me` 身份核验和稳定 principal owner 计算；`WebClientIotAdapter` 仍是 scope shell，尚未迁移多源 Office 目录聚合、分页和 `content` 归一化。
- 当前 Pi 探针的 `echo_tool` 不是业务工具集合；Java Tool Bridge 仍需承载 Runtime 的设备发现、状态读取、观察、草案和任务工具。
- MySQL / Redis 已启动并健康，但 Java 业务事实仓储尚未切换到真实 JDBC/Redis 实现。
- 未执行任何真实设备控制或任务执行。
