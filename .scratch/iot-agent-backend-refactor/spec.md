# 物联 Agent Java 重构规格

Status: ready-for-agent
Date: 2026-10-29

## Problem Statement

现有 `iot-agent-runtime` 已形成可运行的物联 Agent 业务闭环，但运行时采用 Fastify/TypeScript、SQLite 和 Node 生态，未对齐客户要求的 Java 21 / Spring Boot 3.5 / MyBatis-Plus / MySQL / Redis / Spring Security / WebClient / Resilience4j 技术方案。客户同时要求保留 `pi-agent-core` 能力，并要求小程序页面无需额外改动。

原 Java 后端已完成外部边界、业务事实接口、NDJSON/sidecar 探针、fail-closed 适配器和部分本地准备，但尚未完成完整 Java 业务闭环。此前把身份、IoT 和 Observation 描述为“等待客户提供协议”不准确：原 Runtime 已包含这些行为源，且本机已经使用 ego-lite 当前登录态对客户唯一生产上游完成身份和 Office 目录只读验证。

## Solution

建设 Java Spring MVC 主服务与 Node Agent Engine sidecar 的组合后端：

- Java 主服务保持 Runtime 对外 HTTP、Header、错误体、source、NDJSON、状态和恢复语义。
- Java 复用小程序已有 `Authorization` token 与 `Tenantcode`，向客户现有 `/sso-api/account/me` 透传同一 Header，读取 `content.id`，形成稳定 principal owner；不建立第二套登录系统。
- Java 承载认证上下文、授权设备范围、业务工具、Conversation、Action Draft、Task、Plan、Notification、Observation、Report、Audit 和 Admin。
- MySQL 保存业务事实；Redis 只承载缓存、锁、幂等、限流和运行期协调。
- Node sidecar 使用真实 `pi-agent-core` / `pi-ai` 承载 Agent loop、工具调用、流式模型、上下文压缩和 Pi Session。
- Node 工具调用通过内网 Tool Bridge 委托给 Java；Node 不直接接触客户物联网关、客户身份或设备权限。
- 小程序页面、请求路径、Header、source 和展示协议不变。
- 旧 Runtime 在 Java parity 完成前继续作为生产权威；本轮不部署、重启、修改或切换云端生产后端。
- 客户唯一生产上游只允许本地受控只读验证；控制和任务使用 Runtime 记录的协议夹具、contract tests 和本地受控 gateway 验证，不执行生产写操作。

## User Stories

1. 作为小程序用户，我希望继续使用原有页面创建、恢复、清理和发送 Agent 会话，以便无需修改小程序。
2. 作为小程序用户，我希望普通回答继续通过原有 NDJSON 事件流返回，以便现有消息组件继续工作。
3. 作为小程序用户，我希望断线后重新进入仍能按 sequence 恢复当前轮次，以便不丢失已经发生的事件。
4. 作为小程序用户，我希望显式停止产生 `run.aborted`，而普通断线不取消运行，以便两种行为保持原 Runtime 语义。
5. 作为小程序用户，我希望 Token 刷新后仍能看到自己的会话、任务和草案，以便登录态变化不会造成业务归属漂移。
6. 作为小程序用户，我希望不同用户在同一 Tenantcode 下仍只能看到各自的业务事实和设备范围，以便权限不会串户。
7. 作为小程序用户，我希望 Office 和 Company 来源继续隔离，以便设备、历史和任务不会跨来源污染。
8. 作为小程序用户，我希望设备范围由当前服务端身份重新核验，以便客户端或模型不能伪造设备权限。
9. 作为小程序用户，我希望自然语言设备请求仍能从真实授权目录中找到照明、空调、门禁和视频设备，以便不依赖硬编码设备 ID。
10. 作为小程序用户，我希望只读环境、能耗、安全、区域、视频和报告能力继续返回真实事实、覆盖范围和缺数语义。
11. 作为小程序用户，我希望设备控制先生成 Action Draft，再由我确认，以便模型不能直接下发设备操作。
12. 作为小程序用户，我希望修改、恢复、重核和确认草案继续遵守 revision、CAS 和幂等规则，以便重复点击不会重复执行。
13. 作为小程序用户，我希望控制受理、拒绝、超时、未知和 readback 结果被区分，以便系统不把平台受理误报为物理完成。
14. 作为小程序用户，我希望一次性任务和周期计划能够创建、确认、修改、取消和恢复，以便 Agent 保持完整业务能力。
15. 作为小程序用户，我希望任务在服务重启、扫描器中断和重复请求后不会重复控制，以便后台自动化可恢复。
16. 作为小程序用户，我希望任务通知只产生一次并且不泄露凭据，以便通知可靠且安全。
17. 作为管理员，我希望继续使用 Runtime Admin API 管理模型、提示词、预算、观测和版本，以便运维界面无需重写。
18. 作为管理员，我希望配置密钥只显示配置状态、不返回原文，以便管理接口不会泄露密钥。
19. 作为运维人员，我希望 Java、Node、MySQL、Redis 和 Pi Session 可以独立健康检查、备份和恢复，以便局部故障可诊断。
20. 作为运维人员，我希望 Java 与 Node sidecar 只在私网通信，只有 Java 对外暴露，以便缩小攻击面。
21. 作为系统管理员，我希望业务事实重启后仍在 MySQL 中可恢复，以便 Redis 故障不会丢失会话、任务、草案和报告。
22. 作为发布人员，我希望 Java 可以先对只读能力进行 shadow parity，而不重复执行控制、任务和通知，以便切换前验证结果不会产生副作用。
23. 作为发布人员，我希望 parity、事实迁移、备份、回滚和切换 runbook 完成后再考虑生产切换，以便旧 Runtime 始终可回退。
24. 作为客户集成人员，我希望 66 个路径、69 个 operation、错误码、Header 和 NDJSON 通过自动契约门禁，以便后端重构不会静默改变外部行为。

## Implementation Decisions

- 外部兼容目标为 Runtime 的 66 个路径、69 个 operation，包括 Agent、Admin 和 `/healthz`。
- Java 使用 Spring MVC 对外提供 REST/NDJSON；Pi 内部事件只在 Java/Node bridge 内部使用，不能直接暴露给小程序。
- 认证上下文使用 Spring Security 6 与 `PrincipalContext` seam；客户已有登录系统负责登录，Java 只核验并绑定 stable principal。
- 身份核验以现 Runtime 的 `/sso-api/account/me`、`content.id`、401/403/503 语义为行为 oracle。
- Authorized Device Scope 由 Java 依据当前 principal、Tenantcode、source 和 Runtime 已记录的多源目录规则重新生成。
- Java 业务事实通过包根接口隔离具体 MySQL/Redis 实现；不保留生产级内存事实 fallback。
- MySQL 持久化会话事实、逐事件 NDJSON、Action Draft、Task、Plan、Phase、Notification、Report、Audit 和配置修订。
- Redis 只用于可重建缓存、分布式锁、幂等键、限流和运行期协调。
- Pi Session 首期继续使用 Node sidecar SQLite，不迁移到 MySQL。
- Agent Engine 使用 Node >=22.19 的真实 `@earendil-works/pi-agent-core` 与 `@earendil-works/pi-ai`。
- Tool Bridge 支持 runId、conversationId、turnId、取消、超时、重复调用、断线恢复和 sidecar 重启；工具权限、设备引用和业务校验全部由 Java 决定。
- Office / Company 业务工具必须从 Runtime 行为源迁移，不以客户补交新接口作为前置条件。
- 客户生产上游只做身份、目录、状态和 Observation 只读探针；控制、任务和通知不在生产上游双跑。
- 生产切换不属于本轮执行范围；本轮只完成本地可运行后端、parity 证据、事实迁移准备和回滚材料。

## Testing Decisions

- 最高测试 seam 是小程序可观察的外部 HTTP/NDJSON 边界：请求、Header、状态码、错误体、事件、sequence、终态和恢复。
- Runtime OpenAPI、NDJSON schema、源码和历史生产验收记录是 Java 行为 oracle。
- 身份与目录只读测试可以使用 ego-lite 当前登录态连接客户唯一生产上游，但输出必须脱敏且不能写入凭据或原始响应。
- 控制、任务和通知使用 Runtime 生产协议夹具、本地受控 gateway 和不联网 contract tests；测试必须证明不会重复下发。
- Node sidecar 测试真实启动 `pi-agent-core`，验证模型流、工具委托、取消、超时、断线、恢复和 Pi Session 隔离。
- MySQL / Redis 测试先锁定业务仓储行为，再验证真实本地 Compose 连接、事务、唯一性、CAS、锁和恢复；不测试 SQL 文本或框架 wiring。
- 每个业务域必须覆盖正常、缺数、越权、未确认、未核验、超时、重复请求、中断和恢复。
- 最终 gate 必须报告 Java 与 Runtime 的真实差异；不得以计数相等、mock 回声或空成功冒充 parity。

## Out of Scope

- 不重写 `pi-agent-core` 为 Java。
- 不实现第二套微信登录、用户表或客户身份系统。
- 不强行接入当前与 Boot 3.5 不兼容的 ehe-core 版本。
- 不迁移 EM2、集团 AI 门户、OCR、Activiti、MinIO 或 OpenSearch 业务。
- 不修改小程序页面、请求路径、Header 或展示协议。
- 不在客户生产上游执行控制、任务 scanner、通知或配置写入。
- 不进行控制、任务或通知双跑、双写或影子执行。
- 不把 Redis 作为唯一事实来源。
- 不部署、重启、修改或切换云端生产后端。
- 不把客户生产环境缺少 staging 当作实现阻塞；实现优先使用 Runtime 行为源、本地夹具和受控只读验证。

## Further Notes

- 当前已经完成本地 MySQL/Redis Compose、真实模型 + `pi-agent-core` sidecar 探针、Java Tool Bridge 探针、ego-lite Token 获取和客户生产上游身份/Office 目录只读验证。
- 现有旧票据中“客户尚未提供稳定 Java endpoint/schema”的描述已过时，后续票据统一改为“Runtime 行为迁移待完成”。
- 第一批可并行工作是身份票据和存储基础设施；Conversation 依赖二者完成后再接真实持久化。
- Office 只读和 Company 只读可以在 Conversation 主链路后并行推进；Action Draft、Task 和最终 parity 按依赖顺序推进。
- 任何票据只允许修改本地 `iot-agent-backend` 工作树；云端生产后端保持不动。
