# 05: IoT 身份与 Authorized Device Scope

**What to build:** Java 主服务通过客户物联上游建立身份、租户和授权设备范围，并以可取消、有界的 WebClient 调用支撑 Agent 工具。

**Blocked by:** 02: Java 外部契约与认证边界；03: 业务事实仓储边界

**Status:** ready-for-agent

- [ ] Authorization / tenantcode 按原协议透传，身份核验失败不降级为本地假身份。
- [ ] Office / Company 来源隔离，Authorized Device Scope 由服务端当前身份重新核验。
- [ ] 目录、状态、能耗、视频和安全读取保留当前 Runtime 的权限边界。
- [ ] WebClient 调用具备单调用超时、取消传播和 Resilience4j 边界。
- [ ] 只读测试使用生产协议夹具，不联网、不控制真实设备。
- [ ] 客户上游未配置或不可用时返回明确业务错误，不返回空成功或假设备。
