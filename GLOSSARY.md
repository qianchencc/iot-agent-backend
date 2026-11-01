# 物联 Agent Backend

本上下文定义 Java 重构后的物联 Agent 后端、对外契约和 Agent Engine 协作语言。

## Agent 运行

**Agent Engine**：根据业务定义和会话上下文调用模型、执行工具循环、处理中断和压缩上下文的通用运行时；它不拥有物联业务规则。
_Avoid_: Agent 后端、模型服务

**Tool Bridge**：Java 主服务与 Agent Engine 之间传递工具调用、工具结果、运行状态和取消信号的内部协作边界。
_Avoid_: 内部 API、模型接口

**Pi Session**：由 `pi-agent-core` 管理的模型消息、分支、压缩摘要和丢弃记录，用于恢复 Agent 上下文；它不是 Java 业务事实本身。
_Avoid_: 会话缓存、聊天记录

**Business Agent Definition**：面向一次业务对话的能力定义，包括可用工具及其参数、业务指引和经核验的业务上下文。
_Avoid_: Node 业务配置、提示词集合

## 业务与接口

**Authorized Device Scope**：当前身份在当前来源下可读取、可观察或可控制的设备范围；它由服务端重新核验，不由客户端或模型声明。
_Avoid_: 设备列表、用户选择

**Action Draft**：设备控制在真正执行前形成的、带设备事实和版本的待确认操作对象。
_Avoid_: 指令、执行记录

**Business Fact**：需要在服务重启、断线恢复或重试后仍可作为权威依据的数据，包括事件、任务、草案、报告和审计。
_Avoid_: 缓存、运行态

**External Contract**：小程序和客户网关可观察的 HTTP 路径、请求、响应、错误、认证 Header、NDJSON 事件和终态语义集合。
_Avoid_: Controller 接口、内部 DTO

**Business Acceptance**：说明书某项业务在真实使用界面中完成并留下可追溯操作结果和截图的验收结论。
_Avoid_: 接口验收通过、测试全绿、local readiness
