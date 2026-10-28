# 物联 Agent Backend

本上下文定义 Java 重构后的物联 Agent 后端、对外契约和 Agent Engine 协作语言。

## Agent 运行

**Agent Engine**：负责根据会话上下文调用模型、执行工具循环、处理中断和压缩上下文的运行时。本项目首期由 Node sidecar 中的 `pi-agent-core` 承载。
_Avoid_: Agent 后端、模型服务

**Tool Bridge**：Java 主服务与 Agent Engine 之间传递工具调用、工具结果、运行状态和取消信号的内部协作边界。
_Avoid_: 内部 API、模型接口

**Pi Session**：由 `pi-agent-core` 管理的模型消息、分支、压缩摘要和丢弃记录，用于恢复 Agent 上下文；它不是 Java 业务事实本身。
_Avoid_: 会话缓存、聊天记录

## 业务与接口

**Authorized Device Scope**：当前身份在当前来源下可读取、可观察或可控制的设备范围；它由服务端重新核验，不由客户端或模型声明。
_Avoid_: 设备列表、用户选择

**Action Draft**：设备控制在真正执行前形成的、带设备事实和版本的待确认操作对象。
_Avoid_: 指令、执行记录

**Business Fact**：需要在服务重启、断线恢复或重试后仍可作为权威依据的数据，包括事件、任务、草案、报告和审计。
_Avoid_: 缓存、运行态

**External Contract**：小程序和客户网关可观察的 HTTP 路径、请求、响应、错误、认证 Header、NDJSON 事件和终态语义集合。
_Avoid_: Controller 接口、内部 DTO
