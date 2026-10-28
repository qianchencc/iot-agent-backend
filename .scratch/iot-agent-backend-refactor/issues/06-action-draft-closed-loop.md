# 06: Action Draft 确认闭环

**What to build:** Agent 对设备控制请求继续生成待确认 Action Draft，用户修改或确认后才执行，并保留原 Runtime 的权限、版本、幂等和未知结果语义。

**Blocked by:** 04: Conversation 流式闭环；05: IoT 身份与 Authorized Device Scope

**Status:** implemented-locally (control/readback adapters pending)

- [~] 候选与能力范围由 Java 服务核验；设备状态读取等待明确的上游 readback adapter，模型不能提供可信设备名称或权限。
- [x] 生成、选择、参数修改、恢复、重核和取消接口与事件兼容。
- [x] 确认前执行 CAS / revision 校验，确认后重新核验当前授权范围。
- [~] 中断、超时、受理但未核验、未知和未下发保持不同结果状态；已保留 `TIMEOUT` 类型，但真实控制/超时映射待客户 gateway/readback 协议确认。
- [x] 测试 seam 不接真实设备；生产控制协议未确认前保持显式 fail-closed adapter。

Implementation notes:

- Runtime/OpenAPI routes are implemented under `action/internal/controller`: read, selection, parameters, restore, revalidate, confirm, and cancel.
- `ActionDraftFacade` persists revision/status/payload as a business fact, CAS-gates `EXECUTING`, and emits `action.proposed` / `action.resolved` through the conversation fact seam when configured.
- Confirmation performs a fresh `AuthorizedDeviceScope` load after the CAS transition and before the injected `ActionCommandGateway`; terminal results are replayed, with typed `ActionReceiptOutcome` separating gateway acceptance from device-applied and unknown evidence.
- Local repository verification: `mvn test` passed with 27 tests and `mvn package -DskipTests` passed. No cloud, deployment, restart, or real-device call was performed.

Unresolved prerequisites: a MySQL-backed `ActionDraftStore`, confirmed customer control-protocol adapter, and a device-state/readback adapter must be supplied before production control can be enabled.
