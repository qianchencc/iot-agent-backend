# 02: Runtime 身份核验与稳定 Principal

**What to build:** Java 主服务复用小程序已有登录态，按 Runtime 的账户核验行为建立稳定 principal owner，并保持外部认证边界兼容。

**Blocked by:** 01: 本地运行基线与安全只读探针

**Status:** resolved

- [x] `Authorization`、`Tenantcode` 和 `source` 的外部语义保持兼容。
- [x] Java 使用相同 Header 请求客户现有 `/sso-api/account/me`。
- [x] 成功响应读取 `content.id`，按 tenant、identity realm 和 principal 生成稳定 owner。
- [x] Token 刷新不改变同一 principal 的会话、草案、任务和事实归属。
- [x] 上游 401/403 返回 401；身份上游不可用或响应无效返回 503。
- [x] 缺失 Header、非法 source 和未授权请求返回兼容错误体。
- [x] 不建立第二套微信登录、用户表或 Agent 专属登录接口。

## Comments

- 2026-10-29：Java `AccountPrincipalVerifier` 已按 Runtime 迁移；ego-lite 当前会话对生产 `/sso-api/account/me` 只读验证通过。
