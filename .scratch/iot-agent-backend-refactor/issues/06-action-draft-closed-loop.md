# 06: Action Draft 确认闭环

**What to build:** Agent 对设备控制请求继续生成待确认 Action Draft，用户修改或确认后才执行，并保留原 Runtime 的权限、版本、幂等和未知结果语义。

**Blocked by:** 04: Conversation 流式闭环；05: IoT 身份与 Authorized Device Scope

**Status:** ready-for-agent

- [ ] 候选、能力和状态事实由 Java 服务核验，模型不能提供可信设备名称或权限。
- [ ] 生成、选择、参数修改、恢复、重核和取消接口与事件兼容。
- [ ] 确认前执行 CAS / revision 校验，确认后重新核验当前授权范围。
- [ ] 重复确认只回放既有结果，不重复下发设备指令。
- [ ] 中断、超时、受理但未核验、未知和未下发保持不同结果状态。
- [ ] 测试证明不存在绕过确认的控制路径，且不使用真实设备副作用作为测试手段。
