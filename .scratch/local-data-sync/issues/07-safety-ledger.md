# 07: 安全范围/事件列表详情/关联关系同步本地化

**What to build:** 安全授权范围、安全事件列表/最新/详情、事件与设备/摄像头的关联关系同步入本地 MySQL；safety-events、safety-events/latest、safety-events/{id}、safety-events/{id}/cameras 的 HTTP 与 Agent 只读走本地。摄像头实际开流仍是显式例外走真实上游；视频目录与回放窗口准备归10，本票只落关联关系数据与读取。

**Blocked by:** [02-company-catalog.md](02-company-catalog.md)（复用同步骨架；未完成不得开工）

**Status:** ready-for-agent
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- schema：安全事件表与事件关联关系表（事件/对象用真实来源ID+隔离域唯一；关联行记录对象类型与真实对象ID；事件正文按敏感业务内容保护，不做任意表浏览）；经 SchemaMigrations 登记。
- 同步：安全范围随目录批次，事件/处置修订及关联幂等更新，进度/覆盖同02；上游分页口径按G2。同步关联摄像头所需的最小独立视频授权元数据，避免依赖10未完成时偷偷回源；完整视频目录/回放准备扩展归10。
- 读取：四个 safety 端点与 Agent 安全只读走本地入口（包根公开）；latest 语义与既有 LatestSafetyAlert 契约一致；SafetyLedgerComposer 口径保持；cameras 返回本地关联关系，实际开流动作不在此票。
- 同源一致：latest 与列表读同一本地入口；Agent 安全只读与 HTTP 共用实现。
- 事实稳定性：已出具报告引用的事件事实不被补数静默改写（报告侧复核归08）；状态旧可展示但不作控制证据。
- UI：事件列表、最新提醒、详情及关联摄像头展示本地数据；缺覆盖或详情未同步按既有准备/不可用协议，完整范围已证明不存在才采用原NOT_FOUND/空态，不回源。
- UI错误：事件不存在（NOT_FOUND）与数据不可用（UNAVAILABLE）区分展示，不互相冒充。

## 非目标
- 不做视频目录/安全关联候选发现/回放窗口准备（10）；不做报告取证与下载复核（08）；不做 dashboard alerts 统一（11）。

## 行为验收
- [ ] safety 四端点读取不再触发上游（契约测试断言）；Redis 清空后仍可读。
- [ ] 跨租户/主体读取他主体事件或关联被拒；安全范围按主体隔离。
- [ ] 事件重复同步幂等：不产生重复事件行、关联不重复累计。
- [ ] 部分同步时列表/详情按覆盖如实；缺详情字段不伪造、不静默省略。
- [ ] MySQL 不可用→UNAVAILABLE 明确错误，无回源兜底。
- [ ] 安全事件列表/详情开发者工具 UI 截图验证（含错误态）。
- [ ] latest 与列表同源一致（同一契约测试覆盖）。
- [ ] 先校验当前事件安全范围，再按独立视频授权范围裁剪关联摄像头；安全授权不等于视频授权，缺视频范围明确准备/不可用，不扩大也不伪造空列表。
- [ ] 事件修订按上游真实语义更新本地当前事实；不以本地同步时间覆盖发生时间，不丢修订字段；已发布报告的独立证据快照保持不变。
- [ ] 服务重启后按持久范围/进度恢复补跑，不重复已成功批次。
- [ ] 事件表迁移无外键、重复执行安全。
- [ ] 隔离程序实际演示同步后列表/详情/摄像头关联本地读取，客户数据出口计数0；真实微信按总验收图抽样，禁止生产控制、任务、通知和配置写入。

## 证据与既有公开接口
- 公开符号：IotAdapter.loadAuthorizedSafetyScope、SafetyLedgerComposer、ObservationOperationEnum（SAFETY_EVENTS/SAFETY_EVENT/SAFETY_EVENT_CAMERAS）、ObservationStatusEnum。
- 既有测试接口：SafetyLedgerObservationContractTest、LatestSafetyAlertContractTest、SafetyCameraObservationContractTest；测试经包根入口，不从 internal 导入，不开新测试框架。
