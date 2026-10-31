# 29: Company 控制、视频与权限闭环

**What to build:** 按原 Runtime 与《物联 Agent 软件操作说明书》补齐 Company 照明、门禁、空调控制协议，实时/录像视频取流，以及按当前登录身份隔离设备范围；控制必须人工确认，确认前不得下发，执行后记录受理结果与 readback，不得把权限范围扩大为全公司设备。

**Blocked by:** 11: Company 多源设备目录与 Control Reference；18: Independent Node Pi Agent Sidecar；27: Control Reference Reliability and Langfuse Diagnostics

**Status:** implemented-locally-with-upstream-catalog-boundary

- [x] Company lighting/access 使用 Runtime 的 `office/app/device/{family}/down` 协议，按 scope 中的 `controlRef` 下发，并记录逐设备 receipt。
- [x] Company AC 使用 Runtime 已核验的 `iot-center/interactive/{hub}/{airCondition}/down` 协议，保留 `idx/type/val/v` 映射，电源开使用 type 129，关使用 type 128。
- [x] Company lighting/AC 控制确认前只生成 draft；确认时 fresh 重核当前身份授权范围、设备类型、绑定和版本。
- [x] Company lighting 与 video 读取沿 Runtime 的叶子区域 `devicePage`；Company video 使用独立 monitor scope，直播/录像地址短时获取、不缓存。
- [x] Company 录像窗口按上海时区规范化、最多三天；上游 207、401/403、坏绑定、撤权和过期授权分别返回确定性错误。
- [x] 不同身份的 Company scope、候选集、draft、task、video URL 互不可见；越权设备不能检索、控制或取流。权限边界由 fresh scope + binding 校验锁定。
- [x] 真实网关只使用本地开发身份与本地 backend；生产云 Runtime 不修改、不接收控制请求。
- [x] 以软件说明书案例完成后端协议与只读业务验收，并保留脱敏事件、测试结果和截图证据。

## Comments

- Runtime 协议已迁移到 backend：照明/门禁使用 `lightingAreaDeviceId`/`accessAreaDeviceId`，Company 空调使用 `idx/type/val/v`，电源开启固定 `type=129`，关闭固定 `type=128`。
- Company 视频准备工具已经接入 Node sidecar → Java bridge；准备卡不取流，点击播放仍走 `/api/agent/devices/{id}/video-stream`，Company 只读取 monitor 专用授权目录。
- 本机真实身份请求返回 HTTP 200、47 个 Company 设备：energy 43、environment 1、safety 3；当前上游没有返回 lighting/access/monitor 记录，因此不能伪造 Company 控制设备或视频地址。不存在摄像头取流返回确定性 `VIDEO_STREAM_UNAVAILABLE`。
- 控制协议、越权阻断、Company lighting readback、视频窗口与视频上游错误均有回归测试；本次截图保存在 `docs/evidence/company-backend-acceptance.png`。

- 最终修复：控制请求逐次转发当前身份的 `Authorization`/`Tenantcode`；Company AC 的多个字段逐字段下发；只把命令满足的 AC 状态标为 confirmed，照明/门禁保持 unconfirmed；单设备失败保留 receipt 并继续其他设备。Company `read_device_states` 统一覆盖观察设备与控制叶子设备，视频准备事件输出可渲染的 `video_playback` block；恢复 `get_device_details` bridge。
