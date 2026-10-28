# 02: Java 外部契约与认证边界

**What to build:** Java 主服务接管基础外部入口，提供与 Runtime 一致的健康检查、身份 Header 解析、统一错误体和受保护的 Agent 请求边界。

**Blocked by:** 01: Agent Engine bridge 探针

**Status:** ready-for-agent

- [ ] `/healthz` 的字段和状态码符合 Runtime OpenAPI 契约。
- [ ] 保留 `Authorization`、`Tenantcode` 和 `source` 的外部语义。
- [ ] Spring Security 6 建立过渡 `PrincipalContext`，不依赖当前不兼容的 ehe-core。
- [ ] 认证失败、租户缺失、来源非法和未授权请求返回兼容错误体。
- [ ] Java → Node bridge 使用独立内部认证，内部端点不对公网开放。
- [ ] OpenAPI / NDJSON 契约检查可在 Java 构建中执行。
