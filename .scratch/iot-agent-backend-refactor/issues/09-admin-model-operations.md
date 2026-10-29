# 09: Admin、模型配置与运维边界

**What to build:** 管理员可以使用兼容的 Admin API 管理模型、提示词、预算、观测和配置版本；Java、Node、MySQL、Redis 和 Pi Session 具备可诊断、可备份、可恢复但不默认远程 apply 的运维边界。

**Blocked by:** 03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环

**Status:** implemented-locally (MySQL encrypted config revisions, safe health checks, durable admin session and real model probe pass)

- [x] Admin login、session、logout、CSRF 和密码策略保持 Runtime 兼容。
- [x] 模型、prompt、Agent 预算、观测配置、版本读取、恢复和应用接口保持兼容。
- [x] secret 只返回配置状态，不返回原文；配置修订持久化且加密。
- [x] Admin 身份与小程序 Agent bearer 身份、Conversation 和设备权限完全隔离。
- [x] apply 不能中断运行中的模型轮次、设备控制或任务阶段；本地默认拒绝进程重启。
- [x] Java、Node、MySQL、Redis 和 Pi Session 健康检查、日志、备份、恢复和容量检查可执行。
- [x] 生产环境不暴露 Swagger、Actuator 或内部 bridge。
- [x] 模型配置使用真实 `MODEL_BASE_URL` / `MODEL_ID` / `MODEL_API_KEY` 接入测试，但密钥不写入仓库或日志。

## Comments
- 2026-10-29：MySQL-enabled startup created/loaded encrypted `admin_configuration_revisions`; `/healthz` returned safe `java/nodeBridge/mysql/redis/piSession` statuses without URLs or secrets. With a generated local `CONFIG_SECRET_KEY` and local admin password, login/session/config returned 200 (revision 1) and `/api/admin/test-model` returned 200 against the real configured model; response did not contain the API key.
- 当前 Admin 边界、加密文件存储和 fail-closed apply shell 已存在；本票补齐事实存储、模型配置 parity 和运维证据。
