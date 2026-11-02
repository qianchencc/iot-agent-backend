# 0004 — Java 拥有业务定义，Node 提供通用 Agent Engine

Status: accepted · Date: 2026-11-02

客户需要Java团队独立维护智能体业务。采用Java维护Business Agent Definition、工具执行、经核验的业务上下文及公开业务事件投影，Node仅提供Pi模型循环、流、取消、超时和会话等通用机制；新增实际物联业务应可保持Node源码与构建产物不变，并从真实小程序完成调用。这收紧[ADR 0003](0003-java-api-node-agent-engine.md)的分工，不撤销其双运行时和Pi Session存储选择。

后续存储方向由 [ADR 0005](0005-java-owned-mysql-pi-session.md) 更新：Pi Session 持久化归 Java/MySQL，Node 保留 Pi 会话语义与薄存储适配。本决定的业务归属不变；新迁移尚未实施。

仅把工具执行回调Java、但把名称/schema/业务提示词/卡片转换留在Node，会让客户持续跨语言维护；因此拒绝该方案。纯Java重写Pi会引入不必要的底层替代风险，本轮也不采用。代价是明确并版本化通用Tool Bridge数据契约，Java负责完整的业务定义与事件转换，Node需在初次切换后移除现有业务分支，而不是保留双份规则或兼容别名。

Java按身份、来源和当前业务上下文提供数据定义，Node不直接接触客户网关、Java业务库或业务权限；模型不能获得人工确认执行的权限。用户可观察的完成判据为[本轮规格](../../.scratch/agent-business-recovery/spec.md)及说明书微信截图验收，工程测试和协议门禁不替代业务结果。
