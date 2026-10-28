# 物联 Agent Java 重构规格

Status: ready-for-agent
Date: 2026-10-28

## Problem Statement

现有 `iot-agent-runtime` 已形成可运行的物联 Agent 业务闭环，但运行时采用 Fastify/TypeScript、SQLite 和 Node 生态，未对齐客户要求的 Java 21 / Spring Boot 3.5 / MyBatis-Plus / MySQL / Redis / Spring Security / WebClient / Resilience4j 技术方案。客户同时要求保留 `pi-agent-core` 能力，并要求小程序页面无需额外改动。

当前 `../iot-agent-backend` 只有 Spring Boot 初始化骨架，尚未承载 Runtime 的外部 HTTP 契约、业务事实、物联适配、Agent 流式协议或任务执行。

## Solution

建设 Java Spring MVC 主服务与 Node Agent Engine sidecar 的组合后端：

- Java 主服务保持 Runtime 对外 HTTP 契约和 NDJSON 协议，承载认证、授权、业务模块、任务与报告、MySQL 事实库、Redis 非事实能力和客户物联上游适配。
- Node sidecar 使用真实 `pi-agent-core` / `pi-ai` 承载 Agent loop、工具调用、流式模型、上下文压缩和 Pi Session。
- Node 工具调用通过受控内部 Tool Bridge 委托给 Java；Node 不直接接触客户物联网关或外部身份。
- 小程序继续使用原有 API、Header、`source`、错误码和事件语义。
- 旧 Runtime 在切换前继续作为生产权威；只读能力允许 shadow parity，控制和任务执行禁止双跑。

## User Stories

1. 作为小程序用户，我希望继续用原有页面创建和恢复 Office/Company Agent 会话，以便无需升级页面代码。
2. 作为小程序用户，我希望普通回答仍以原有 NDJSON 分块流式返回，以便现有输入区和消息卡片继续工作。
3. 作为小程序用户，我希望断开网络后重新进入仍能按事件顺序恢复当前轮次，以便不丢失已展示的处理进度。
4. 作为小程序用户，我希望明确调用停止后运行真正进入 `run.aborted`，以便“断线”和“停止”保持不同语义。
5. 作为小程序用户，我希望设备控制仍先生成 Action Draft，再由我确认，以便不会因 Agent 重构而绕过安全边界。
6. 作为小程序用户，我希望草案确认、重核、参数修改和恢复继续遵守 revision / 幂等语义，以便重复点击不会重复下发设备指令。
7. 作为小程序用户，我希望任务提案、任务确认、周期计划、通知和任务详情继续使用原有接口，以便页面无需改造。
8. 作为小程序用户，我希望 Office 和 Company 的设备范围、历史上下文和任务来源继续隔离，以便不同来源不会互相污染。
9. 作为小程序用户，我希望只读环境、能耗、安全、视频和报告能力继续从原有接口取得事实，以便既有观察页面不需要改写。
10. 作为小程序用户，我希望 401、403、404、409、503 和业务错误体保持原有含义，以便页面现有恢复和提示逻辑继续有效。
11. 作为客户运维人员，我希望主后端使用客户批准的 Java 21 / Spring Boot 3.5 技术栈，以便纳入现有交付和运维体系。
12. 作为客户运维人员，我希望 Node Agent Engine 只在内网运行且不暴露公网端口，以便保留 Pi 能力而不扩大攻击面。
13. 作为客户运维人员，我希望 Java 和 Node sidecar 可以独立检查、备份、重启和回滚，以便单个运行时故障不会导致不可控切换。
14. 作为系统管理员，我希望长期业务事实落入 MySQL，以便重启、扩容和切换后仍有稳定的权威数据。
15. 作为系统管理员，我希望 Redis 只承担可重建的缓存、锁、幂等和限流，以便 Redis 故障不会造成业务事实丢失。
16. 作为系统管理员，我希望 Pi Session 首期保留在 Node sidecar 的 SQLite 中，以便先完成 Agent 行为 parity，再单独评估 MySQL-backed session。
17. 作为安全审计人员，我希望认证上下文、租户、授权设备范围和审计记录由 Java 主服务统一决定，以便模型和客户端不能扩大权限。
18. 作为开发人员，我希望 `pi-agent-core` 的工具调用通过 Tool Bridge 进入 Java 业务服务，以便 Node sidecar 不复制设备权限和控制逻辑。
19. 作为开发人员，我希望 Java 模块只依赖包根入口，内部 Controller、Service、Mapper 和 DTO 隐藏在模块内部，以便模块边界长期可维护。
20. 作为开发人员，我希望在迁移每个业务模块前先有外部行为测试，以便不以内部实现或数据库表复制冒充兼容。
21. 作为发布人员，我希望旧 Runtime 在 Java parity 验证完成前继续作为生产权威，以便新后端失败时可以回退。
22. 作为发布人员，我希望只读能力可以 shadow parity，但控制和任务不双跑，以便验证结果而不重复执行真实设备动作。
23. 作为管理端用户，我希望 Admin API 最终保持兼容，但不阻塞小程序 Agent 主链路的首期迁移。
24. 作为客户集成人员，我希望 OpenAPI、NDJSON schema 和路由漂移检查继续作为契约门禁，以便后端重构不会静默改变接口。

## Implementation Decisions

- 外部兼容目标为现 Runtime 的 66 个路径、69 个 operation，包括 Agent、Admin 和 `/healthz`；首期实现顺序先 Agent，再 Admin。
- Java 使用 Spring MVC 对外提供 REST/NDJSON；不把 Pi 内部事件形状直接暴露给客户端。
- Java 使用 Spring Security 6 建立过渡认证上下文，保留客户 `LocalVariable.getPrincipalInfo()` 的 API 形状；当前不直接依赖不兼容的 ehe-core 5.0.0.0-SNAPSHOT。
- Java 使用模块根入口 + `internal` 实现的 deep-module 结构；Controller、Service、Mapper、Domain 和 DTO 归入模块内部。
- Agent Engine 使用 Node >=22.19 的 `@earendil-works/pi-agent-core` 与 `@earendil-works/pi-ai`。
- Tool Bridge 使用同一部署内网的受控 HTTP/NDJSON；必须支持 runId、conversationId、turnId、取消、超时、重复调用和 sidecar 重启。
- Java 主服务负责工具授权、设备范围、物联 WebClient、业务事务、草案/任务状态、审计和对外事件。
- MySQL 保存长期业务事实；Redis 保存可重建缓存、分布式锁、幂等、限流和运行期协调；Pi Session 首期保存于 Node sidecar SQLite。
- 数据库设计遵循客户技术规范：字段和表名使用小写下划线；表有 `id/create_time/update_time`；小数使用 decimal；不使用外键和级联；业务唯一性由唯一索引保证；超过 JavaScript 安全整数范围的 ID 以 String 对外返回。
- Java 与 Node 以 Docker 独立容器部署于同一私网，只有 Java 接入客户网关；配置和日志映射到宿主机。
- 不做控制或任务执行双跑、双写；切换前旧 Runtime 为生产权威，Java 只读能力可以 shadow parity。
- 首个 runnable probe 只验证 Java MVC → Node Pi → Java fake tool bridge → NDJSON 的普通回答、工具调用、取消、断线恢复和 session 边界，不接真实设备。

## Testing Decisions

- 最高测试 seam 是外部 HTTP contract：请求、Header、状态码、错误体、NDJSON 行、sequence、终态和恢复行为。
- Java API 测试应使用进程内 fake Tool Bridge 和生产协议夹具，不联网、不监听真实公网端口、不控制真实设备。
- Node sidecar 测试验证 Pi Agent 的工具委托、流式事件映射、取消和重启边界，不测试 Java Controller 的内部转发细节。
- MySQL / Redis 接入前先用仓储 contract tests 锁定事实语义；不测试 SQL 文本或框架 wiring。
- 每个迁移模块必须保留 Runtime 现有外部回归的等价场景：正常、缺数、越权、未确认、未核验、超时、重复请求和中断。
- 控制与任务测试必须证明不会重复下发；只验证“返回 200”或 mock 回声不算验收。
- 运行探针必须真实启动 Java 和 Node 组件，观察 HTTP/NDJSON 输出；单元测试不能替代该验证。

## Out of Scope

- 首期不重写 `pi-agent-core` 为 Java。
- 首期不实现 MySQL-backed Pi Session。
- 首期不把当前不兼容的 ehe-core 5.0.0.0-SNAPSHOT 强行接入 Boot 3.5。
- 首期不迁移 EM2 业务系统、集团 AI 门户、OCR、Activiti、MinIO 或 OpenSearch 业务。
- 首期不修改小程序页面、请求路径、Header 或展示协议。
- 不进行控制请求的双跑、双写或影子执行。
- 不把 Redis 作为唯一会话或业务事实来源。
- 不在本规格内解决客户 MySQL、Redis 的具体版本、网络和账号申请；这些是部署前置条件。

## Further Notes

- Runtime 的 OpenAPI 和 NDJSON 文档是外部契约基准；Runtime 各模块 `AGENTS.md` 是业务语义基准。
- `apps/runtime/src/agent` 的 Pi runner、`app.ts` 的会话/事件/确认执行和 `iot/adapter.ts` 的授权上游边界是迁移时的主要行为 oracle。
- 迁移顺序应先完成架构探针，再按 conversation → iot/authorization → draft/task → read-only domains → reports/admin 逐步推进。
- 任何存储或内部协议变更都不能改变小程序可观察的外部契约。
- 云端现有生产 Runtime、进程、数据库、密钥和网关配置不属于本轮实施范围；所有票据只修改本地 `iot-agent-backend` 工作树，未经另行授权不得部署、重启、切换或写入生产环境。
