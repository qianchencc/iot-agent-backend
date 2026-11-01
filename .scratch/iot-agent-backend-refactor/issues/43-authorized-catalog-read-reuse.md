# 43: 已验证授权目录读取短时复用

**Type:** task
**Status:** implemented-locally-pending-real-smoke
**Blocked by:** 41, 42

## Goal

区域列表的真实请求仍把大部分时间花在重复加载同一份已验证 Office 授权目录。目录数据与权限事实分开：请求先由现有认证边界校验，再在同一 `tenant + ownerKey + source` 身份下短时复用成功 Office 目录结果。

## Scope

- 仅对 Office `loadAuthorizedDeviceScope` 的只读目录路径做 5 秒成功结果复用和在途合并；Company 目录保持每次读取，以保证控制绑定和 Company scope 变化及时可见。
- 控制、执行、视频授权、事实写入和实时状态回读不依赖该复用；控制仍走单独 scope 重校验。
- 失败不缓存，不引入 Redis，不新增撤权缓存机制；目录对象保留 scope fingerprint，任何业务结果仍按已验证 scope 隔离。

## Acceptance

- Office `/regions` 和 Office 区域设备/状态连续读取共享成功目录；Company 控制/候选目录保持 fresh 读取。
- 不同 tenant、ownerKey、source 不互相命中。
- 上游失败不产生成功缓存；过期后重新读取。
- 目标测试已通过；待本地真实接口补充冷热请求证据。
