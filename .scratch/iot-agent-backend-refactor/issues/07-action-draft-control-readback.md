# 07: Action Draft、控制安全与 Readback

**What to build:** 用户可以通过原有草案卡安全修改并确认照明、门禁和空调操作；Java 在确认前后保持 Runtime 的授权、版本、幂等、受理和 readback 语义。

**Blocked by:** 04: Conversation 与 Pi Agent 生产闭环；05: Office Authorized Device Scope 与只读工具

**Status:** implemented-locally (Runtime-derived fixture control/readback and MySQL facts verified; real customer control remains prohibited and unconfigured production remains fail-closed)

- [x] 生成、读取、选择、参数修改、恢复、重核和取消 Action Draft 的 payload 与事件兼容。
- [x] Draft 保存当前 principal、source、candidate evidence、revision、状态和过期时间。
- [x] 确认通过 CAS/revision 后，重新读取 Authorized Device Scope 和能力，拒绝过期、越权、伪造或已变化的设备引用。
- [x] lighting、access、AC command adapter 按 Runtime 已记录路径、参数、业务码和响应语义实现（local fixture only）。
- [x] accepted、rejected、timeout、interrupted、unknown 和 device-applied/readback 事实分离。
- [x] 终态重复 confirm 只回放原结果，不重复调用 gateway。
- [x] 使用 Runtime 生产协议夹具和本地受控 gateway 验证，不向客户生产上游发送控制请求。
- [x] MySQL 保存 Draft、receipt、audit 和 readback 事实，Redis 只承担锁/幂等协调。

## Comments
- 2026-10-29：Explicit `AGENT_CONTROL_MODE=fixture` smoke created a draft, confirmed a lighting action, persisted an `ACCEPTED` receipt and one confirmed readback in MySQL, and repeated confirm returned 200 without a second dispatch path. Fixture emits Runtime-derived protocol envelopes only; production control remains fail-closed.
- 旧票据中“等待客户 control/readback schema”改为“按 Runtime 已有 adapter 和历史生产记录迁移”；本轮仍禁止生产控制。
