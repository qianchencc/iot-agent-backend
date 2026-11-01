# 41: Office 区域目录读取最小复用修正

**Type:** task
**Status:** resolved
**Blocked by:** 33, 34, 36

## Goal

修正 Office 区域页面的明显重复上游读取。当前 `/api/agent/regions` 冷/热请求实测约 4–5 秒；区域列表、区域设备、区域状态和已读标记都会重新读取区域目录。短时间内应复用区域目录，但不能复用授权范围本身。

## Change

- 在 `CompanyOfficeObservationAdapter` 内对区域目录做 5 秒成功结果复用和在途合并。
- 每次业务路径仍重新加载并校验当前授权设备范围；目录缓存 key 包含已验证 tenant、owner、source 与授权目录指纹，不能跨 scope 命中。
- 失败结果不写入或延长缓存；不引入 Redis，不改变认证/授权协议，不改变区域事实来源。
- 控制、回读和事实写操作不依赖目录缓存。

## Acceptance

- 区域列表、区域设备、区域状态在短时间共享同一目录读取。
- 上游失败仍返回原有错误语义，不返回空成功。
- 现有区域授权过滤和 `region_seen` 事实语义不变。
- 目标是减少明显重复等待，不把约 1 秒作为硬门槛。
- 已验证 `CompanyOfficeObservationAdapterContractTest`、`CompanyEnergyObservationComposerContractTest`、`SafetyCameraObservationContractTest`。
