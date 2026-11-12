# IoT目录与授权

- `DeviceDirectory.readCatalog`是公司完整展示目录；`read/loadBatch/loadScope`仍是用户可见目录。不得用前者授权候选、状态、控制、任务、能耗、安全或视频。
- `DeviceCatalogSource`只由既有后台同步调用；普通HTTP及投影miss只能读MySQL。显示范围按上游实例＋租户共享，用户可见范围继续按主体隔离。
- 型号转换、整批发布及同步规则集中在仓根`.scratch/device-catalog/README.md`；最近成功同步时间不表示设备采样时间。首读准备中、真实空目录和同步失败旧值必须区分。
- 安全台账通过 `DeviceDirectory.safetyHistory()` 读取专用本地授权与已完整发布事实；`SafetyHistory`保持独立领域接口，不继承通用`ObservationAdapter`，避免替换整个观测路由。日期边界、数量及后台共享规则见`docs/api/java-contract.md`的安全事件台账章节。
- 告警资料统一智慧消防（2026-11-12）：事件`deviceName/recordedDeviceName`取消防告警接口同步返回值，历史名称会随上游改名变化，非固定事发快照；`currentDevice.name/location`只取本地专用消防授权目录，不混用物联网引擎名称／地址。当前位置不等于事发位置。监控仍按事件接口名称查视频候选、回放取原时间；不宣称候选可靠还原历史地点，不新增推断／快照，不回源。见`docs/adr/0010-safety-event-current-name-historical-playback.md`。
- 修改目录／授权差量发布或安全事件同值跳写前，先读仓根`.scratch/sync-storage-governance/README.md`的已实施边界；该处集中记录保留的批次／时间保护和真实MySQL写入证据。
