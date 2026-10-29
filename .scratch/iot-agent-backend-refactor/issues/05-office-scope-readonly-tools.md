# 05: Office Authorized Device Scope 与只读工具

**What to build:** Agent 在 Office 来源下依据当前已核验 principal 读取完整授权设备范围，并通过 Java Tool Bridge 提供 Runtime 兼容的设备发现、状态和能力读取。

**Blocked by:** 02: Runtime 身份核验与稳定 Principal；03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环

**Status:** implemented-locally (Office scope/candidate/state/capability reads and real Pi→Java candidate-tool smoke pass)

- [x] 读取 Office membership，并按 Runtime 规则遍历 lighting、access、AC、video 多源目录。
- [x] 迁移多源分页、裸数组/Spring page、`code`/`content` envelope 和 204/401/403/5xx 语义。
- [x] 按当前 principal、Tenantcode 和 Office 来源隔离设备范围，不能使用客户端或模型提供的设备权限。
- [x] 保留 Runtime 的设备去重、物理绑定、控制引用、观察设备和目录完整性语义。
- [x] `find_device_candidates`、设备状态读取和能力核验只返回当前 Authorized Device Scope 内的事实。
- [x] Java Tool Bridge 可被真实 Agent loop 调用，工具结果不泄露 token、provider ID 或内部授权键。
- [x] 使用 ego-lite 当前身份对客户唯一生产上游执行脱敏只读验证；不执行控制。
- [x] 未配置或上游失败时返回明确业务错误，不返回空成功或假设备。

## Comments

- 2026-10-29：Java Office adapter traverses the configured Runtime-derived membership and seven read-only source paths. Ego-lite smoke returned 3 lighting candidates and 2 state records with no authorization field; a real Pi loop then invoked `find_device_candidates` through the authenticated Java Tool Bridge and received a non-error result. No control request was sent upstream.
