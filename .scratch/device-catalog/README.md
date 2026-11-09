# 完整设备目录与用户可见目录分离

## 用户认可的范围（2026-11-09）

- 设备目录：客户 `/micro-api/api/iot-center/device/list` 的完整基础设备记录，按上游实例及租户共享；不是个人设备授权。
- 用户可见目录：原 `/api/agent/company-devices?source=company` 与 DeviceDirectory.loadBatch 的主体授权记录；Agent候选、状态、控制、任务、报告继续使用，不切换来源。
- 完整目录在Java后端MySQL持久化，复用已加密托管的独立同步凭据与既有调度。新增 `/api/agent/device-catalog?source=company` 供首页总数、分类数、单类及全部明细读取；普通HTTP不采集上游。
- 型号映射11值到7类：SL-SW-NS3→lighting；YQ-WFS/YQ-PSD/YQ-GAS-INDUCTOR→safety；ENERGY-376.1→energy；HIK-ISC-VIDEO→video；YQ_AIR_CONDITION/MG-AC-CONTROLLER→ac；YQ-ENV-SENSOR→environment；EHE-ACCESS-V2/DS-K1T673M→access。未归类服务记录及已删除记录不进入展示目录。
- 展示记录仅id/name/kind/location；id是底层目录ID，不替代各授权业务ID；无位置使用空字符串。数量来自同步结果，不固定332。

## 持久化与同步

- 新表local_device_catalog_scopes/local_device_catalog，通过SchemaMigrations既有本地目录入口登记local-device-catalog-v1。唯一键分别catalog_key与(catalog_key,device_kind,source_device_id)，无外键，保留代理主键和审计时间。
- 首次读取只登记现有同步范围和返回准备中；完整批次后台同步成功才可读。复用既有约60秒当前数据同步规则，页面关闭、后端重启不取消登记。
- 同公司不同用户读同一份展示目录；同一公司每轮只采集一次完整列表；其他租户隔离。已核验凭据仍按既有规则托管、核验与失效处理，不新建服务账号、不借任务凭据。
- 网络在事务外；整批原子替换，新增/更名/位置/移除同步对齐。失败保留上一完整批次及原成功时间，标stale；真实成功空目录与准备中区分。MySQL不可用不回源。
- 不新建同步平台、队列、管理页面或前端直连。首页旧读快照使用独立新键，避免把用户可见目录缓存当完整目录。
- 抽屉不再以类型宣称可控制；按位置/类型展示，位置已有值原样保留，复合kind+id作行key。

## 验证与状态

- 公开seam：DeviceCatalogSource.http、DeviceDirectory.readCatalog/read/loadScope、GET设备目录与真实微信首页/抽屉。
- 已在固定Java3381生效，Node及配置不变；真实微信首页显示332台、七类数量正确，实际点击设备目录后显示332条完整目录，基础记录、位置及中立类型可见。没有下发控制、创建任务/计划或发送通知。证据：[结果及发布记录](evidence/CATALOG-01/result.json)、[首页](evidence/CATALOG-01/home.png)、[全部目录](evidence/CATALOG-01/all-directory.png)。未逐类点击截图，未做真机验收。
- 目录行为25项、架构检查、前端368项、186文件边界及微信开发编译通过。真实独立MySQL验证重复迁移、同租户共享、授权范围不变、失败整批回滚、更换目录及重启无回源读取，临时库已删除。后端全套736项中1项既有安全录像契约失败、29跳过，不宣称全套通过；最终打包跳过重跑测试。
- 首次同步失败误当准备中、坏行误发布为空目录已先以2项失败复现后修复并通过；实际页面发现的同名位置被隐藏及虚假绿色状态点也已修正。只读双轴核对未发现授权链扩张；对分页/大小写的假设不作额外机制，真实来源已证明扁平数组及`TRUE/FALSE`契约。
- 334→332的数量变化已按底层绑定核对：原照明156条业务记录对应136个硬件、门禁47条对应45个硬件；这些硬件及原49台空调全部存在于新目录。新目录另外包含17台空调和3台门禁，去掉22条额外关联、加入20个新硬件，净减2；不是漏掉两台。电表业务ID与底层ID不同，不按ID相等推断其关联。
