# 09: Admin、模型配置与运维边界

**What to build:** 管理员可以使用兼容的 Admin API 管理模型、提示词、预算、观测和配置版本；Java、Node、MySQL、Redis 和 Pi Session 具备可诊断、可备份、可恢复但不默认远程 apply 的运维边界。

**Blocked by:** 03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环

**Status:** ready-for-agent

- [ ] Admin login、session、logout、CSRF 和密码策略保持 Runtime 兼容。
- [ ] 模型、prompt、Agent 预算、观测配置、版本读取、恢复和应用接口保持兼容。
- [ ] secret 只返回配置状态，不返回原文；配置修订持久化且加密。
- [ ] Admin 身份与小程序 Agent bearer 身份、Conversation 和设备权限完全隔离。
- [ ] apply 不能中断运行中的模型轮次、设备控制或任务阶段；本地默认拒绝进程重启。
- [ ] Java、Node、MySQL、Redis 和 Pi Session 健康检查、日志、备份、恢复和容量检查可执行。
- [ ] 生产环境不暴露 Swagger、Actuator 或内部 bridge。
- [ ] 模型配置使用真实 `MODEL_BASE_URL` / `MODEL_ID` / `MODEL_API_KEY` 接入测试，但密钥不写入仓库或日志。

## Comments

- 当前 Admin 边界、加密文件存储和 fail-closed apply shell 已存在；本票补齐事实存储、模型配置 parity 和运维证据。
