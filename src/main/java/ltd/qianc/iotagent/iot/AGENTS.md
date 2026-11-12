# IoT目录与授权

- `DeviceDirectory.readCatalog`是公司完整展示目录；`read/loadBatch/loadScope`仍是用户可见目录。不得用前者授权候选、状态、控制、任务、能耗、安全或视频。
- `DeviceCatalogSource`只由既有后台同步调用；普通HTTP及投影miss只能读MySQL。显示范围按上游实例＋租户共享，用户可见范围继续按主体隔离。
- 型号转换、整批发布及同步规则集中在仓根`.scratch/device-catalog/README.md`；最近成功同步时间不表示设备采样时间。首读准备中、真实空目录和同步失败旧值必须区分。
- 安全台账通过 `DeviceDirectory.safetyHistory()` 读取专用本地授权与已完整发布事实；`SafetyHistory`保持独立领域接口，不继承通用`ObservationAdapter`，避免替换整个观测路由。日期边界、数量及后台共享规则见`docs/api/java-contract.md`的安全事件台账章节。
- 告警事件与当前设备资料分离（2026-11-11）：事件`deviceName/recordedDeviceName`保留上游记录，`currentDevice.name/location`按专用安全授权、同实例／租户及硬件ID批量关联本地完整目录；不改原绑定或事件事实。监控用原事件名称／地点线索在视频授权内查候选，回放取原事件时间；不得用当前位置覆盖事件依据，也不得因没有预存摄像头快照就封死查找。候选不是已核实的历史关联；顶层`location`是当前资料而非事发位置。决策与验证要求见`docs/adr/0010-safety-event-current-name-historical-playback.md`。
- 修改目录／授权差量发布或安全事件同值跳写前，先读仓根`.scratch/sync-storage-governance/README.md`的已实施边界；该处集中记录保留的批次／时间保护和真实MySQL写入证据。
