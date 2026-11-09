# 任务创建人姓名

## 根因与修复

截图中的「工作日下午26楼机房前空调开机」来自 Company，保存的 `creatorName` 为空。不是微脑 `personalName` 缺失：对应已核验身份和审计中已有姓名。旧 `TaskFacade.creatorName` 对 Company 直接返回空；前端按已有规则显示「成员」。当时本地两个 Company 计划均无姓名，三个 Office 计划均有姓名。

现在 Company／Office 均优先使用已核验 `PrincipalInfo.displayName`，去除首尾空白后保存为提案的创建人姓名快照；不接受模型提供的建议姓名，也不因额外姓名接口不可用丢掉已核验姓名。未核验／无姓名的遗留调用保留原 Office 回退，不修改认证、权限、前端、任务确认、调度和执行行为；不增加成员通讯录、缓存或按手机号／哈希显示姓名的机制。

## 已有记录的受控补齐

用户批准后，只对本地 `iot_agent_business` 中空姓名的两个计划、五条已生成任务补齐 `payload_json.creatorName`。

- 使用已登记凭据实时查询微脑 SSO，验证租户、身份域和真实账号 ID；将该已核验身份重新计算的规范 owner key 与记录精确比较，同时校验记录中已有的 principalId／identityRealm。
- 不反推 owner hash、不按任务名／人员姓名匹配、不把当前查看者当成创建者。旧记录缺失的 principal ID 不补造；仅写经过核验的展示姓名。
- 七条记录先备份到私有 `var/task-creator-name-20261109-155111/creator-metadata-before.json`（0600），再在一个短事务内锁行、条件更新、逐字段核对后提交。
- 对比确认每行除姓名外 JSON 值及所有表字段均相同，版本、状态、排期、归属及审计时间未变。没有创建任务／计划、切换周期状态、下发控制或发送通知。

## 工程与运行

- 包根 `TaskFacade.propose` 行为回归：Company／Office 两项先失败（空名／「成员」），修复后使用虚构的核验身份姓名并覆盖模型建议身份；不使用真实人员或凭据作测试数据。
- 78 项提案、存储、管理、读取权限和身份核验相关回归通过。
- 扩展构建：729 项，700 通过、29 跳过、0失败／错误。显式排除三个既有告警／视频失败，详见 [result.json](evidence/CREATOR-NAME-01/result.json)，不声称无排除的全套通过。
- 固定 Java 3381 为 `var/task-creator-name-20261109-155111/backend.jar`。基于当时运行的 `var/region-timeline-fix-20261109-155030/backend.jar`，逐项确认仅替换 `TaskFacade.class`；其他并行功能、Node 3382、配置和监听方式保持原样，旧包保留可回退。
- 源代码只改取名分支及原注释，不合并并行工作区中的日历投影或执行网关改动。

## 真实微信验收

在现有已登录小程序中走「公司工作台 → 查看所有任务 → 周期任务 → 原计划详情」，没有注入身份、响应或卡片。

- [用户原截图](evidence/CREATOR-NAME-01/before.png)：创建人显示「成员」。
- [空调计划修复后](evidence/CREATOR-NAME-01/after-ma.png)：创建人显示「马云飞」，仍保持当前账号仅可查看。
- [照明计划修复后](evidence/CREATOR-NAME-01/after-liu.png)：创建人显示「刘志龙」。

本次没有为验收而确认创建新的真实任务；新任务姓名契约由公开提案边界的行为回归覆盖。用户手机未单独复验，不把脚本、接口成功或数据更新等同于手机验收。
