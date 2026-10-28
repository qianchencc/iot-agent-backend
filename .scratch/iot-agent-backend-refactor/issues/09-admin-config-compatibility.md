# 09: Admin 与模型配置兼容

**What to build:** 在 Agent 主链路稳定后迁移 Runtime Admin API，使管理员可以登录、读取、修改、恢复和应用配置，且密钥和 CSRF 边界不泄露。

**Blocked by:** 02: Java 外部契约与认证边界；03: 业务事实仓储边界；04: Conversation 流式闭环

**Status:** implemented locally (Java boundary; local verification passed)

- [x] Admin login、session、logout、CSRF 和密码策略接口兼容。
- [x] 模型、prompt、Agent 预算、观测配置和版本恢复接口兼容。
- [x] 密钥只返回配置状态，不返回原文；配置持久化加密且 fail closed。
- [x] “立即生效”只在没有危险在途工作时执行，不能中断模型轮次或设备执行。
- [x] Admin 不与小程序 Agent 身份或会话权限混用。
- [x] 生产不暴露 Swagger / Actuator 等框架页面。

## Implementation notes

- Admin uses an independent `runtime_admin` HttpOnly/SameSite cookie session and per-session `X-Admin-CSRF`; it never reads `PrincipalContext` or Agent bearer identity.
- Configuration revisions use AES-GCM encrypted local storage behind `AdminConfigurationStore`; public views remove secret values and expose only configured flags. Missing bootstrap password/key and corrupt storage fail closed.
- `AdminApplyCoordinator` and `AdminWorkState` are explicit deployment seams. The default local backend refuses process apply and never restarts a process; a real supervisor and live-work guard are required before enabling it.
- Local verification: `mvn test` passed with 27 tests. No cloud connection or deployment was used.
