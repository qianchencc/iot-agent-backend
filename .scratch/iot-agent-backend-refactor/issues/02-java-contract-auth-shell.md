# 02: Java 外部契约与认证边界

**What to build:** Java 主服务接管基础外部入口，提供与 Runtime 一致的健康检查、身份 Header 解析、统一错误体和受保护的 Agent 请求边界。

**Blocked by:** 01: Agent Engine bridge 探针

**Status:** resolved

- [x] `/healthz` 的字段和状态码符合 Runtime OpenAPI 契约。
- [x] 保留 `Authorization`、`Tenantcode` 和 `source` 的外部语义。
- [x] Spring Security 6 建立过渡 `PrincipalContext`，不依赖当前不兼容的 ehe-core。
- [x] 认证失败、租户缺失、来源非法和未授权请求返回兼容错误体。
- [x] Java → Node bridge 使用独立内部认证，内部端点不对公网开放。
- [x] OpenAPI / NDJSON 契约检查可在 Java 构建中执行。

## Comments

- 2026-10-29：完成本地实现。新增 Spring Security 6、`PrincipalContext`、Agent Header/source filter、独立 bridge token filter。
- `/healthz` 实际返回 `service/status/version`；未认证 Agent 请求返回 401；非法 `source` 返回 400。
- `mvn test`：13 tests，0 failures；package 与实际 3200 HTTP smoke 通过。
- Runtime OpenAPI gate 检查 `/healthz`、消息 NDJSON path 和 `application/x-ndjson`。
- 未部署、未重启、未连接云端生产服务。
