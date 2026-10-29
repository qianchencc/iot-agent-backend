# 05: Office Authorized Device Scope 与只读工具

**What to build:** Agent 在 Office 来源下依据当前已核验 principal 读取完整授权设备范围，并通过 Java Tool Bridge 提供 Runtime 兼容的设备发现、状态和能力读取。

**Blocked by:** 02: Runtime 身份核验与稳定 Principal；03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环

**Status:** ready-for-agent

- [ ] 读取 Office membership，并按 Runtime 规则遍历 lighting、access、AC、video 多源目录。
- [ ] 迁移多源分页、裸数组/Spring page、`code`/`content` envelope 和 204/401/403/5xx 语义。
- [ ] 按当前 principal、Tenantcode 和 Office 来源隔离设备范围，不能使用客户端或模型提供的设备权限。
- [ ] 保留 Runtime 的设备去重、物理绑定、控制引用、观察设备和目录完整性语义。
- [ ] `find_device_candidates`、设备状态读取和能力核验只返回当前 Authorized Device Scope 内的事实。
- [ ] Java Tool Bridge 可被真实 Agent loop 调用，工具结果不泄露 token、provider ID 或内部授权键。
- [ ] 使用 ego-lite 当前身份对客户唯一生产上游执行脱敏只读验证；不执行控制。
- [ ] 未配置或上游失败时返回明确业务错误，不返回空成功或假设备。

## Comments

- `/sso-api/account/me`、Office membership、lighting、access 和 AC 只读行为已从 Runtime 和生产探针确认；当前 Java 仍只有 scope shell。
