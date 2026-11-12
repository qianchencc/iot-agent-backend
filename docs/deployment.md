# 物联 Agent 后端

Java 提供业务 HTTP、授权、设备／任务／报告逻辑及 MySQL 持久化；Node `agent-sidecar` 提供通用 Agent 执行引擎；`web/admin` 为管理控制台。Redis 用于协调与可重建缓存，不替代 MySQL。

## 交付范围

客户交付包含源码、测试、SQL、必要资源及许可证、依赖锁文件、非敏感配置模板和本部署文档。不含开发仓库历史、开发笔记、验收数据、运行配置、业务数据或已安装依赖。HTTP接口及字段以交付源码中的Controller、DTO和契约测试为准。

Java根包为 `com.ehe.iotagent`，Maven坐标为 `com.ehe:iot-agent-backend`；保留单一后端工程及现有业务模块边界。命名空间迁移不改变HTTP接口、配置键或数据库结构；构建必须先清理旧产物（`mvn clean package`），避免旧包名class与新包名class并存。升级须替换完整JAR并重启Java，不可向旧包名的JAR增量覆盖新包名class文件。

## 环境要求

- Java 21；源码构建使用 JDK 21 或兼容 JDK、Maven 3.9.x。
- Node 22.19.0及以上的22.x版本、npm；按各自 package-lock.json 安装依赖。
- MySQL 8.4、Redis 7.4；字符集 utf8mb4，业务时区 Asia/Shanghai。
- 部署机器须能访问配置的客户网关与模型服务；视频播放页为单独部署组件，本目录不包含视频 SDK。
- 模板沿用本地端口；客户部署应统一调整为30000～49999，并同步修改 Java、Node 回调和控制台代理地址。Node、MySQL、Redis不应直接暴露到公网。

## 构建

在本目录执行：

```sh
mvn -B -Dmaven.test.skip=true clean package
npm --prefix agent-sidecar ci
npm --prefix agent-sidecar run build
npm --prefix web/admin ci
npm --prefix web/admin run build
```

以上只构建产物，不代表测试通过。Java单元／契约测试执行 `mvn test`；Node测试中的MySQL会话用例还需独立的Java会话测试服务，不应接生产库。

Java运行产物为 `target/iot-agent-backend-0.1.0.jar`。Node交付必须包含 `dist/`、`package.json`及生产依赖：可在目标平台保留两个package文件，执行 `npm ci --omit=dev` 安装，再复制构建好的 `dist/`；不能只复制 `dist/`，也不要跨操作系统复制已有node_modules。管理控制台静态产物为 `web/admin/dist/`。

## 首次配置

将 [Java模板](../application.example.yaml) 和 [Node模板](../agent-engine.example.yaml) 复制到部署私有目录，使用绝对路径、限制为部署用户可读；不要提交真实配置或使用 `.env`。

1. 建立独立MySQL数据库及账号，填写 `spring.datasource.*`；填写 `spring.data.redis.url`，显式启用两个 `agent.storage.*.enabled` 开关。首次启动由程序初始化业务表；已有库升级前须备份并验证升级，不能用空库演练替代旧库迁移验证。
2. 为 `agent.task.credential-key`、`agent.local-data.credential-key`、`admin.config-secret-key` 分别生成独立的32字节密钥，以64位十六进制填写，例如分别运行 `openssl rand -hex 32`。这些根密钥须稳定保存，已有密文时不能随意重新生成。
3. 生成独立桥接Token，保存为共享目录内唯一文件 `agent.bridge.token`，权限0600。Java的 `spring.config.import` 使用 `configtree:/absolute/shared/`；Node的 `bridge.token-file` 指向同一文件。Node不接触数据库、管理员或上述加密密钥。
4. 设置 `admin.password` 和控制台实际浏览器源 `admin.origin`；Java／Node监听与回调地址必须配对。
5. 填写 `iot.gateway.base-url` 及网关路由。启用持久同步时该地址不能为空，否则Java启动失败；不要关闭身份校验绕过配置。生产填写真实网关，不使用测试占位地址。
6. Java启动后，在管理控制台补齐模型地址、模型ID和API Key。空库允许先初始化管理配置，但模型未配置时不能进行有效Agent推理；首次保存须满足模型字段校验。

`compose.yaml` 仅为本地MySQL／Redis前置示例，不是整套生产编排。使用前在 `var/config/infrastructure/local/` 分别创建0600的 `mysql-password`、`mysql-root-password` 文件，再执行：

```sh
docker compose --env-file /dev/null -f compose.yaml up -d mysql redis
```

默认MySQL为127.0.0.1:3307，Redis为127.0.0.1:6380；已有数据卷不得因口令不匹配而删除重建。示例关闭binlog，保留正常事务及redo／undo；若部署另有复制、订阅或恢复要求，应由部署方另行明确日志策略。

## 启动与检查

先启动MySQL／Redis，再Java，最后Node；以下各命令在独立进程中运行：

```sh
java -jar /absolute/backend.jar --spring.config.additional-location=file:/absolute/application.yaml
node /absolute/agent-sidecar/dist/server.js --config /absolute/agent-engine.yaml
```

- Java公开健康入口为 `/healthz`。其中Node桥接的 `configured` 只表示配置存在，不等于引擎已联通。
- Node `/healthz` 需要桥接Bearer Token；管理端的运行状态、观测采用修订及 `/api/admin/pi-session/health` 可进一步核对实际连接。不要将Token写进共享终端记录。
- 普通业务接口需客户网关核验过的身份、租户及设备授权；401不应通过关闭鉴权“修复”。首次本地数据未准备好时由后台采集，普通读取不现场回源。
- 安全台账同步间隔由 `agent.local-data.safety-sync-interval-seconds` 控制，默认20秒；其他领域约60秒，首次及每日完整历史校正仍保留。修改部署参数后重启Java。

## 控制台

可信开发网络可在 `web/admin` 执行 `npm run dev`。默认端口5185，`ADMIN_API_TARGET` 可指定Java地址，`ADMIN_PROXY_ORIGIN`须与Java的 `admin.origin` 一致。这不是公网生产服务。

生产将 `web/admin/dist/` 部署为静态站点，并配置前端路由回退；同源反向代理 `/api/admin` 至Java，启用HTTPS，`admin.origin`填写实际HTTPS源。业务前端需要的 `/api/agent` 由部署方另行配置代理。不要公开 `/internal/**`；仅允许Java／Node内部互通。TLS、代理、进程守护及防火墙需在目标环境验收。

管理员登录后使用HttpOnly Cookie；非GET请求还须匹配Origin和CSRF。可管理业务配置保存在MySQL修订中，已有修订不由启动种子覆盖。模型等配置由后续运行读取，Langfuse观测配置由Node启动时采用，需重启Node并核对采用状态。

## 停机、备份与升级

- 先停止接收新业务，按顺序停止应用，等待Java／Node退出，再停止MySQL／Redis；不要同时强停全部进程。为进行中的业务及数据库连接关闭设置足够的停机宽限期。
- MySQL保存任务、报告、管理配置与Pi会话；备份须配对保存原始加密密钥。Redis不是业务恢复来源。
- 升级前核对发布版本、数据库与配置归属，保留可用回退产物；涉及结构迁移时，仅换回旧JAR不等于数据回滚。
- 上游副本可由后台重建，但这不授权删除任务、配置、报告或整库。未准备的数据应明确返回相应状态。
- 本次源码交付不包含真实客户账号、模型凭据或业务验收结论。目标环境仍需验证网关／模型可达性、首次同步、前端访问与所需业务路径。
