# 05: IoT 身份与 Authorized Device Scope

**What to build:** Java 主服务通过客户物联上游建立身份、租户和授权设备范围，并以可取消、有界的 WebClient 调用支撑 Agent 工具。

**Blocked by:** 02: Java 外部契约与认证边界；03: 业务事实仓储边界

**Status:** implemented locally (fail-closed scope adapter; identity/family upstream contracts pending)

- [ ] Authorization / tenantcode 按原协议透传并完成客户身份核验；当前仅实现透传与未配置时 fail closed。
- [x] Office / Company 来源隔离，Authorized Device Scope 由服务端当前身份重新核验。
- [ ] 目录、状态、能耗、视频和安全读取保留当前 Runtime 的权限边界。
- [x] WebClient 调用具备单调用超时、取消传播和可接入 Resilience4j 的策略边界。
- [x] 只读测试使用生产协议夹具，不联网、不控制真实设备。
- [x] 客户上游未配置或不可用时返回明确业务错误，不返回空成功或假设备。

## Implementation notes

- Java 包根 `ltd.qianc.iotagent.iot` now exposes `RequestIdentity`, `IotSource`,
  `AuthorizedDeviceScope`, `IotAdapter`, `IotCallPolicy`, and typed upstream errors.
- `WebClient` forwards only `Authorization` and `Tenantcode`; the source selects an
  isolated route and is retained on the returned scope. Timeout and subscriber
  cancellation are per call. `IotCallPolicy` is the Resilience4j-compatible reactive seam.
- Missing gateway configuration, malformed responses, transport failures, and non-401/403
  HTTP failures fail closed with a 503 business error. No local identity or fake device
  list is returned.
- `IotAdapterContractTest` uses an in-process `ExchangeFunction` fixture with the
  production `{code,content}` response envelope.

## Unresolved upstream contract gap

The Runtime family reads use several source-specific paths and payloads (Office
membership/device pages; Company environment, safety, energy, smart-device and video
routes). The customer has not supplied one stable Java scope/read endpoint or complete
payload schemas. This ticket therefore implements the source-aware, fail-closed
scope seam only; status/energy/video/safety family operations remain intentionally
unimplemented until those upstream routes and response schemas are confirmed.
