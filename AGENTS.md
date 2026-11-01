# iot-agent-backend

`../iot-agent-runtime`（Fastify/TypeScript）的 Java 版实现，逐功能对齐，全本地开发。HTTP 契约以 runtime 的 `docs/api/`（openapi + NDJSON 事件协议）为对齐基准；业务语义以 runtime `apps/runtime/src` 各模块 `AGENTS.md` 为准。

当前业务恢复入口：[规格与十六组闭环](.scratch/agent-business-recovery/README.md)；涉及 Java/Node 职责、智能体工具/指引、Runtime 对照或验收时先读。业务定义归 Java、Node 只提供通用 Agent Engine，见 [ADR 0004](docs/adr/0004-java-business-definition-generic-agent-engine.md)。

本 worktree 与缓存主线隔离；普通读取/同步的未提交缓存实现不在此分支。不得直接启用复制出的 `.env`、`var/` 或 Compose 数据目录；联调先配置独立端口、MySQL schema、Redis命名空间、Pi Session和Admin状态，不重启/切换原工作区服务。

前端控制台在 `web/admin/`（Vite + Vue3 + Arco，按客户选型清单）；开发与组件规范 `web/admin/AGENTS.md`，部署与维护 `web/admin/docs/maintenance.md`。

## 技术栈

Java 21（`--release 21` 编译，本机 JDK 25 运行）、Spring Boot 3.5.16、Maven、ArchUnit 1.5.1（模块边界强制）。

## 公司选型对齐（2026-10-28，原件见 `docs/reference/`）

选型清单与技术规范来自客户（微信临时目录易失，已拷贝入 `docs/reference/`）。

- 版本权威：Java 21 LTS、Spring Boot 3.5.x、Maven 3.9.x、springdoc-openapi 2.x、MyBatis-Plus 3.5.17、Spring Security 6.x、Spring WebClient、Resilience4j 2.x——均与 Boot 3.5 兼容，按模块落地时引入。
- ehe-core 5.0.0.0-SNAPSHOT 与 Boot 3.5 不兼容（Java 11 字节码 / javax.servlet / springfox）：等客户 jakarta 版再接；过渡期认证上下文自实现，保留 `LocalVariable.getPrincipalInfo()` 同名 API 形状。见 ADR-0002。
- 存储方向已采纳：MySQL 保存业务事实、Redis 承载非事实能力、Pi Session 首期使用 Node sidecar SQLite；环境版本、连接和账号证据仍待补齐。见 ADR-0001，考察材料在 `.scratch/persistence-route/`。

技术规范硬性条目（编码/建表/部署时强制）：

- 服务端口取 30000~49999（部署期；本地开发 3200 不变）；生产隐藏 swagger/actuator
- 超过 2^53 的整数（雪花 ID 等）一律以 String 下发前端
- MySQL：小数必须 decimal；禁外键与级联；逻辑删除用 null 方案（`logic-delete-value: "null"`）配合联合唯一索引；表必备 `id`(bigint unsigned)/`create_time`/`update_time`
- 线程池禁止 `Executors` 工厂，用 `ThreadPoolExecutor` 显式构造
- 依赖版本集中在父 pom `<dependencyManagement>` 管理

## 命令

```sh
mvn test                 # 单元测试 + ArchUnit 边界规则
mvn spring-boot:run      # 本地启动，127.0.0.1:3200
curl -s http://127.0.0.1:3200/healthz
```

## 模块边界（deep module 纪律）

- 每个业务模块是一个顶层包 `ltd.qianc.iotagent.<module>`；**包根下的类是唯一入口（entry points）**，外部只准 import 这些。
- 实现一律放 `<module>.internal` 及其他子包；外部（其他模块、测试）禁止触碰子包里的类。internal 类需标 `public`（Java 无包级友元，编译需跨包可达），实际可访问性由 ArchUnit 强制。
- 宁可暴露多个小入口类，不要做一个 re-export 全子树的大 barrel。
- 增加入口 = 在模块包根加类；隐藏实现 = 挪进子包，规则永不改配置。
- 技术规范的 MVC 分层（controller/service/mapper/domain）放模块 `internal/` 下；controller 按端分子包（`controller/agent/`、`controller/admin/`）。HTTP 路由是运行时入口，包根类是代码级入口——外部模块只 import 包根，永不 import controller。
- 强制：`src/test/java/ltd/qianc/iotagent/ArchitectureTest.java`（入口边界 / 测试走入口 / 模块间禁循环）。
- 分层（哪个模块准依赖哪个）暂不约束，模块数量稳定后再补——对应依赖规约里的预留位。

## 目标模块映射（runtime → Java）

| runtime `apps/runtime/src` | Java 包 | 职责 |
| --- | --- | --- |
| `business-error.ts` | `error` | 统一错误体 `{code,message,recovery?}` 与脱敏诊断（已建） |
| `runtime-config.ts` | `config` | 进程环境解析，缺配置 fail closed |
| `app.ts`/`domain.ts`/`server.ts`（会话、草案、事件存储、确认执行） | `conversation` | 会话与 NDJSON 流式事件核心 |
| `iot/` | `iot` | 网关适配器：双目录、状态/能耗读、控制下发、视频取流 |
| `agent/` | `agent` | Business Agent Definition、业务工具与上下文；通用 Pi 执行由 Node Agent Engine 提供 |
| `tasks/` | `task` | 任务/周期/通知/提案/扫描执行 |
| `energy/` | `energy` | 电表统计/对比/日报月报/报告快照 |
| `reports/` | `report` | 报告事实落库 + PDF/XLSX 渲染 |
| `safety/` | `safety` | 安全台账 |
| `dashboard/` | `dashboard` | 公司仪表盘分区并发读 |
| `regions/` | `region` | Office 区域浏览 |
| `environment/` | `environment` | 环境历史窗口 |
| `video/` | `video` | 回放窗口校验与取流 |
| `admin/` | `admin` | 加密配置控制台 |
| `observability/` | `observability` | Langfuse（三变量齐备才启用，否则 no-op） |

`example/` 是拷贝模板（入口类 + internal 实现），新模块照它起形，可随时删除。

## 测试基线

进程内生产协议夹具（对齐 runtime `apps/runtime/test/support/production-gateway-fixture.ts`）：不联网、不监听真实端口、不控制真实设备。

## 业务验收

- 逐项覆盖冻结的[软件说明书](docs/reference/软件说明书-2026-11-02.md)，以真实微信开发者工具业务结果和截图为唯一业务通过依据；接口、NDJSON、测试、旧截图和计划均不替代。
- 原 Runtime 是用户确认可靠的单向业务基线；新后端失败时对照源码修复，不修改、切换或测试生产 Runtime，不为确认用户已报告故障而重跑。
- 主验收使用真实账号/模型/授权只读数据；草案可不执行、任务可不创建。执行后展示/管理仍有独立证据，隔离事实副本注明来源及范围，不冒充设备真实执行。
- 生产控制、任务、通知和配置出口禁写；验收不得注入消息/卡片或模拟成功。证据及未验项按[验收矩阵](.scratch/agent-business-recovery/acceptance.md)记录。

## Agent skills

### Issue tracker

Issues live as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

默认五个 triage 角色标签，标签字符串与角色同名。 See `docs/agents/triage-labels.md`.

### Domain docs

Single-context：根 `GLOSSARY.md` + `docs/adr/`（由 `/domain-modeling` 惰性创建）。 See `docs/agents/domain.md`.
