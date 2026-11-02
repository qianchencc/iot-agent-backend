# 52：草案当前设备状态与执行回读分离

状态：实现、完整回归和后端只读验证完成，ready-for-human；真实页面由用户验收。未下发设备控制、未创建任务、未发送通知；未启动新子代理。

## 已确认根因

MP 草案卡片及明细调用 GET `/api/agent/action-drafts/:id/device-states`。Runtime同路径读取当前平台状态；Java此前仅扫描 ActionReadbackStore 的执行后证据。待确认草案尚无回读记录，故返回 `states=[]`、空设备映射，界面所有设备均未知。这是后端接错业务语义，不是用户或前端需要认识上游。

公共红测 `pendingDraftDisplaysCurrentDeviceStateWithoutAnyDispatchReadback` 已实际运行：期望 `device-1.power=on`，实际 `[]`。修复后公共回归包含未执行草案的灯/空调当前状态、明确preparing、持久summary与当前状态分离、归属隔离。

## 实现边界

另一个公共红测 `heldDraftRemainsReadableDuringScopeOutageButConfirmFailsClosed` 实际证明：原GET草案仍会实时读取完整范围，模拟范围暂不可用时普通GET失败。已移除普通读取中的范围扫描，仅保留本地归属/来源/过期检查；完整回归确认普通读取可用，而确认控制仍故障关闭，不放松执行授权。

- 草案状态读取只在本地检查租户/用户/来源/草案归属，不再让当前状态显示依赖一次完整授权目录扫描。确认控制仍独立执行权威范围及设备现读，不使用显示缓存作为执行证据。
- 使用既有 ReadSnapshots CURRENT，按身份、来源及排序后的设备集合与普通状态接口共享键；只返回backend已持有结果，后台同步不阻塞HTTP。后台沿用适配器既有组合读取预算，不能被确认范围的短预算提前截断。
- summary只读持久回执/回读，不再借用当前状态接口，避免把今天的状态改写成历史执行结果。
- MP `readDeviceStates` 接入既有ordinaryRead准备协议，并在关闭/切换草案时取消等待；不把preparing当成空状态交付。

## 真实后端只读样本

身份来自本机既有真实网关配置，凭据未输出。POST `/api/agent/devices/states` 是只读状态查询，不是控制下发。

| 样本 | backend返回 | 首次后台准备 |
|---|---|---|
| Office照明3台 | true、false、true（开、关、开），带observedAt | 6.573秒；首次HTTP12ms |
| Office空调2台 | 均off；温度25/26℃，设定26/26℃ | 7.091秒 |
| 相同2台空调反转请求顺序 | 相同状态，命中fresh快照 | 单次HTTP45ms |
| 正常重启后相同2台空调 | 仍返回已有状态，cache=stale/refreshing=true | 520ms即可读取已有状态，不等待同步 |

这些结果证明backend能持有并返回当前平台设备事实，且后台刷新时不删除已有值；不代表对现场物理状态做了独立实测。当前探测身份没有可用的现存会话草案，未为了凑证据直接写库、伪造工具上下文或触发模型自动控制；草案公共行为回归与真实设备状态样本分别证明消费和生产边界，最终草案页面由用户确认。

## 已运行检查

- 完整后端 `mvn -q test` 通过；后续缓存身份键修正的 ActionDraftReadbackContractTest 定点回归通过。
- 前端259项回归通过，`npm run lint:boundaries`：157源文件通过；HBuilderX开发编译成功。曾误调用不存在的check:agent-boundaries脚本，已按package.json改用真实脚本；不将该失败算作通过。
- 本机3207运行修复包；未更换生产Runtime。其余用户实例、身份修改及工作区改动保留。
