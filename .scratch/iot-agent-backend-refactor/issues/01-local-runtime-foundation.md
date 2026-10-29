# 01: 本地运行基线与安全只读探针

**What to build:** 本机可以安全启动 Java、Node Agent Engine、真实模型、MySQL 和 Redis，并使用 ego-lite 当前登录态对客户唯一生产上游执行脱敏只读验证。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] MySQL 8.4 与 Redis 7.4 通过本地 Docker Compose 启动并健康。
- [x] Node sidecar 使用真实 `pi-agent-core` / `pi-ai` 和本地模型配置，不使用 faux model 作为运行配置。
- [x] Java 与 Node 通过内网 HTTP/NDJSON 探针完成普通回答、工具调用、取消、断线、重放和 sidecar 重启验证。
- [x] ego-lite 当前登录态可安全捕获 `Authorization` 与 `Tenantcode`，只写本机 0600 配置，不打印凭据。
- [x] `/sso-api/account/me`、Office membership 和 Office 目录只读探针通过。
- [x] 本地探针不控制真实设备，不访问云端生产后端，不修改生产配置。

## Comments

- 2026-10-29：本地基础设施、真实模型 Pi bridge、ego-lite Token 获取和生产上游只读探针已完成。后续票据只承接业务迁移，不再重复建立运行基线。
