# 05: 三项能力收口验证

**What to build:** 管理员通过真实浏览器完成登录、白名单、参数配置和用户审计主流程，页面在常见失败与窄屏场景仍可使用。

**Blocked by:** None；01–04已完成本轮实现与隔离验证，未验边界列于下方

**Status:** completed-with-recorded-limits（2026-11-05；不是生产发布或真实控制验收）

- [x] 独立管理员登录、手机号添加／重载持久化／取消与确认移除；名单没有业务读者
- [x] 保存／冲突保留草稿／跨页保留／生成新修订恢复；修复Element Plus的validateField合法返回值被误判，实际模型草稿连接检查成功且未自动保存
- [x] 观测保存不报采用；隔离Java／Node重启后采用修订6，本地接收端收到实际span；验证后关闭本地诊断观测并保存修订7、重启确认关闭，业务审计仍保留公司4条／办公室2条记录
- [x] 同一真实账号在微信开发者工具完成办公室照明7台状态查询、公司334台设备分类统计；管理端同一成员展示两来源公开请求与答复
- [x] 成员完整ID查询、来源／类型／结果查询；无结果／真实400错误与恢复；抽屉关闭恢复焦点；320px及200%等效视口
- [x] 六项设计skill检查；修复手机号弹窗、审计抽屉及日期筛选的窄屏溢出，补筛选器可访问名称；成功标签实际对比度由2.96提升至6.40
- [x] Java聚焦10组62项通过；Node类型检查、34项测试、构建通过；前端构建通过（保留>500kB包体提示，不以调高阈值隐藏）
- [x] 完整551项运行已记录：549通过、1跳过、1条动作草案用例异常；未排除用例或改动并行业务来声称全绿
- [x] 不下发真实控制、不创建生产任务、不发送通知，不切主开发3381/3382或生产配置

## 证据

文件位于[`CONSOLE-20261104`](../evidence/CONSOLE-20261104/result.json)，目录日期来自验证启动时间，包含跨日实施结果。

| 路径 | 实际证据 |
|---|---|
| 登录／名单 | `01-config-initial.png`、`02-phone-persisted.png`、`05-phone-320px.png`、`06-phone-dialog-320px.png` |
| 配置 | `03-config-conflict.png`、`08-configuration-adopted.png`、`17-draft-model-probe.png` |
| 真实小程序 | `09-office-query-result.png`、`13-company-query-result.png`、重启后延续对话`18-company-after-restart.png` |
| 按成员审计 | `10-office-audit-detail.png`、`14-member-two-sources.png` |
| 窄屏／错误 | `11-audit-drawer-320px.png`、`12-audit-320px.png`、`15-audit-200percent-equivalent.png`、`16-audit-error-recovery.png` |

## 未验证及保留问题

- 原生浏览器200%缩放、真实屏幕阅读器、RTL／多语言未专项验证；200%项为682×450 CSS像素等效视口，不冒称原生浏览器缩放。
- 真实设备控制、自动任务执行、生产通知及客户Langfuse部署未验收。自动执行主体、事务／审计故障恢复与受理／回读分离已做本地行为回归，不替代真实执行。
- 全套唯一错误：`ActionDraftProposalContractTest.rootAndLightingExclusionsMergeByDeviceIdentityInsteadOfReferenceSpelling`，错误为“排除设备不属于已核验的候选范围”；保留并行业务改动，不混入本轮控制台提交。
- 管理员为共享部署身份，不能识别使用该密码的自然人；缺历史主体不补造姓名。
