# 0003 — Java 对外服务与 Node Agent Engine sidecar

Status: 采纳 · Date: 2026-10-28

业务职责已由 [ADR 0004](0004-java-business-definition-generic-agent-engine.md) 收紧：工具契约、业务指引、上下文与公开业务事件归 Java，Node 只负责通用机制；本决策的双运行时及 Pi Session 存储选择保留。

2026-11-02 后续方向：[ADR 0005](0005-java-owned-mysql-pi-session.md) 进一步取代 Pi Session 本地 SQLite 的交付选择；双运行时不变，Java 管理会话持久化，Node 保留通用执行。该迁移尚未实施。

物联系统 Agent 必须对齐客户 Java 21 / Spring Boot 3.5 技术栈，同时保留当前 `pi-agent-core` 的 Agent 循环、工具调用、流式模型、上下文压缩和会话能力。由于 `pi-agent-core` 是 Node >=22.19 的 ESM npm 包，Java 不能直接加载当前实现；因此采用 Java 主服务承载对外契约与业务事实，Node sidecar 承载真实 Pi Agent Engine。

## 决定

- Java Spring MVC 主服务保持 Runtime 对外 HTTP 契约、认证上下文、业务模块、MySQL 事实库、Redis 非事实能力和客户物联上游适配。
- Node sidecar 只运行 `pi-agent-core` / `pi-ai`，不直接访问客户物联网关，不自行决定租户、授权范围或设备控制权限。
- Java 通过受控的内部 HTTP/NDJSON bridge 向 Node 发起 Agent run；Node 的工具调用委托回 Java，Java 返回经权限和业务规则校验的工具结果。
- Java 将 Node 事件映射为稳定的 Runtime NDJSON 事件；Pi 内部事件形状不得直接暴露给小程序。
- Java 与 Node 首期以 Docker 独立容器部署于同一内网，只有 Java 经过客户网关对外暴露；Node 不开放公网端口。
- 首期迁移 Agent 主链路、草案、任务和小程序依赖的只读能力；Admin 仍是最终兼容目标，但后置迁移。
- 旧 Runtime 在切换前继续作为生产权威；只读能力可以 shadow parity，控制和任务执行禁止双跑或双写。

## 后果

- 生产部署同时包含 Java 21 和 Node 22.19+ 两个运行时，必须分别进行版本、日志、健康检查、备份和回滚管理。
- 内部 bridge 成为一个必须单独测试的稳定 seam，需覆盖取消、超时、断线、重复请求和 sidecar 重启。
- Java 业务模块可以逐步替换 Runtime 实现，而不改变小程序和客户网关的外部接口。
- Node sidecar 的 Pi session 首期保留 SQLite；MySQL-backed Pi session 是后续独立决策，不阻塞首个业务闭环。
