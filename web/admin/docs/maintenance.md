# web/admin 维护手册

运维/部署/升级/排障；开发规范见同目录 `AGENTS.md`。

## 环境变量（部署必须给全）

| 变量 | 作用 | 缺省/后果 |
|---|---|---|
| `ADMIN_ORIGIN` | 控制台浏览器源，非 GET 请求 `Origin` 必须精确相等 | 默认 `http://127.0.0.1:3200`；配错 → 所有写操作 403 `ADMIN_ORIGIN_REJECTED` |
| `ADMIN_PASSWORD` | 初始管理员密码（部署托管） | 设置后控制台改密功能禁用（409 `ADMIN_PASSWORD_DEPLOYMENT_MANAGED`） |
| `ADMIN_PASSWORD_FILE` | 本地密码文件路径 | 默认 `var/admin-password`；无 `ADMIN_PASSWORD` 且无文件 → 登录 `503` 不可用 |
| `ADMIN_PASSWORD_MIN_LENGTH` | 密码最小长度 | 默认 6，范围 1–128 |
| `ADMIN_CONFIG_FILE` | 加密配置存储 | 默认 `var/admin-config.bin` |
| `CONFIG_SECRET_KEY` | 配置加密密钥（hex） | 缺失 → `FailClosedAdminConfigurationStore`，配置接口 503 |
| `ADMIN_API_TARGET` | 前端 dev 代理目标 | 默认 `http://127.0.0.1:3200`，仅 dev server 用 |

## 启动

```sh
# 后端（仓根）
JAVA_HOME=/opt/homebrew/opt/openjdk \
ADMIN_PASSWORD=<strong-password> \
ADMIN_ORIGIN=https://admin.example.com \
mvn spring-boot:run          # 或打 jar

# 前端 dev
cd web/admin && npm ci && npm run dev     # http://127.0.0.1:5173, /api → 3200
```

生产：前端 `npm run build` 产物交 nginx 静态服务；nginx 反代 `/api` 到后端（对外只开 443）。`ADMIN_ORIGIN` 填用户实际访问的 https 源。

## 配置维护页（登录后 `/`）

- **模型与密钥**：模型地址必须是 http(s) 根地址，不能带登录信息、查询参数或片段。API Key 输入框只用于替换：留空保持当前密钥，填入新值才会提交新值；页面和后端都不回显已保存密钥。`测试模型连接` 会调用 Java 侧 `/test-model`，使用服务端已保存密钥叠加当前草稿中的地址、模型 ID 和新密钥，不会保存配置。
- **业务指令与执行预算**：保存时只提交差异字段，并带上当前 `expectedRevision`。模型、业务指令、`maxAgentTurns`、`maxToolCalls`、`runTimeoutMs` 等热字段在**下一轮执行边界**生效；正在执行的轮次保持开始时的快照，不会中途切换。
- **并发冲突**：另一个会话先保存时，页面显示冲突并保留本地草稿，保存暂停。`载入最新修订（保留草稿）` 会更新基线修订，之后重新保存；可能覆盖服务端新值，需先核对。`放弃草稿并刷新` 会丢弃本地修改。
- **恢复历史版本**：恢复不会删除审计历史；它基于目标修订内容新建一个修订。操作前会二次确认，成功后本地草稿重置。
- **待应用字段**：页面给出已保存值与下一轮生效快照的差异。非热字段需要部署层应用或重启；此页面**不会重启 Node、Java 或任何宿主进程**，也不能替代受监督部署流程。
- **审计**：只展示修订、时间、操作者与变更字段名，不展示配置值、密钥或供应商凭证。变更值请按字段名在配置页核对。
- **范围**：模型密钥状态只显示“已配置/未配置”，不提供明文查询。

## 常见故障

| 现象 | 原因 | 处置 |
|---|---|---|
| 登录后一切写操作 403 | `ADMIN_ORIGIN` ≠ 浏览器 `Origin`（端口/协议/尾斜杠） | 核对变量值与地址栏完全一致，重启后端 |
| 登录 503 | `ADMIN_PASSWORD` 未设且 `var/admin-password` 不存在 | 设 `ADMIN_PASSWORD` 或写密码文件（首行即密码，权限 600） |
| 登录一直 429 | 同 IP 5 分钟 5 次失败限流 | 等 5 分钟；别反复试错 |
| 刷新掉登录 | cookie 过期（8h）或后端重启 | 正常，重新登录 |
| 改密 409 | `ADMIN_PASSWORD` 环境变量存在 | 部署托管密码，改部署配置不是改页面 |
| 配置接口 503 | `CONFIG_SECRET_KEY` 缺失 | 补密钥重启；密钥丢失 = 密文不可读，需重配 |

## 升级依赖

`package.json` 已锁主版本；升级小版本：

```sh
cd web/admin && npm update && npm run typecheck && npm run build
```

Arco/Vue 跨主版本升级先建 `.scratch/` 分支验证，别直接升——`tokens.css` 的 `--arcoblue-*` 覆盖对 Arco token 结构敏感，破肤先查这里。

## 密码与密钥轮换

- 控制台改密：页面操作，即时清所有会话，写回 `ADMIN_PASSWORD_FILE`。
- 部署改密：换 `ADMIN_PASSWORD` 重启；控制台改密同步失效（预期行为）。
- `CONFIG_SECRET_KEY` 轮换：现版本不支持在线换密钥；换了旧密文读不出，等于重置配置——变更前先在 `var/` 备份 `admin-config.bin`。

## 会话/安全边界（排障时先核这里）

- cookie `runtime_admin`：`HttpOnly` `SameSite=Strict` `Path=/api/admin`，8h。
- `x-admin-csrf`：每会话随机，登录/`GET /session` 下发；非 GET 必须带。
- `Origin` 校验：仅非 GET；GET 不校。
- 限流只对 login：按 IP，5 次/5 分钟。
