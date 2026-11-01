# 42: 首屏分区与能耗业务路径修正

**Type:** task
**Status:** resolved
**Blocked by:** 33, 36, 38, 40

## Goal

保证首屏/能耗读取不因一次全量 dashboard 组合而把所有上游等待串在一起，同时保留能耗统计的真实缺数、截断和比较语义。

## Change / Evidence

- Dashboard 按请求 section 只读取需要的观察区，环境、能耗、安全和任务摘要保持明确的部分失败语义；不以空数据伪装成功。
- 能耗比较/报告保留授权电表边界、历史窗口、零基期不可计算、迟到/缺失/截断状态；当前周单独表达，不将未完成数据填充为虚假统计。
- 安全事件摄像头候选按当前 Company 安全设备位置/楼层和当前视频授权过滤，时间窗口不超过当前时刻。
- 不引入全局 Redis cache、预热平台或复杂索引。

## Acceptance

- `CompanyOfficeObservationAdapterContractTest`
- `CompanyEnergyObservationComposerContractTest`
- `SafetyCameraObservationContractTest`
- 后端全量 `mvn test`
- 本地真实接口 smoke 对 dashboard environment/energy、regions、company-devices、safety-events 记录冷/热耗时和响应状态。
