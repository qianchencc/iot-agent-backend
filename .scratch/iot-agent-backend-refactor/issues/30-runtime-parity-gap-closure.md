# 30: Runtime 全量行为对齐与案例闭环

**What to build:** 以 `iot-agent-runtime` 的生产行为、测试案例和 NDJSON/API 契约为对照，逐项修复 backend + Node sidecar + mp 本地链路差异。任何输入、状态、错误、事件、权限、确认、readback 或前端可见结果不一致，都必须继续修复，不能以真实上游当前无设备作为完成条件。

**Blocked by:** 29: Company 控制、视频与权限闭环

**Status:** in-progress

## Case matrix

- [x] 固定 backend、Runtime、mp 三方基线；生产 Runtime 只读。
- [x] 复现并修复 Company 候选检索拒绝 Company source 的问题。
- [x] 复现并修复 `observation.reported` 视频 block 被放入 `details.blocks` 而非顶层 `blocks` 的问题。
- [x] Company `read_device_states` 为视频设备输出 Runtime-compatible `video` observation block。
- [ ] Company AC：已满足跳过、readback 等待/freshness、旧状态不得误报 verified、逐动作 outcome、INTERRUPTED 语义。
- [ ] Company lighting/access：leaf 目录、合法命令、确认与逐设备 receipt 与 Runtime 完全一致。
- [ ] Company video：live/playback scope、窗口、错误、no-store、card、恢复和前端播放链路完全一致。
- [ ] observation/report：环境历史、能耗历史/比较/报告、Company meter report、alerts、safety camera review 与 Runtime 输出完全一致。
- [ ] task/scheduler：任务确认、变更、取消、列表/详情、readback/report blocks 与 Runtime 状态机完全一致。
- [ ] NDJSON：tool/run/message/action/task/observation 事件 envelope、sequence、恢复和持久化完全一致。
- [ ] mp：本机 backend profile、事件渲染、确认进度、视频入口与开发者工具行为完全一致。
- [ ] 软件说明书案例逐项完成 backend 真实请求和前端验收证据。

## Acceptance

1. Runtime 相关测试案例在 backend 对应契约测试中有等价覆盖，或明确记录协议不可得的外部前置条件；不可用能力不得伪造成功。
2. 本机 backend + sidecar + mp 开发链路使用同一 Company source/identity 通过抽样案例。
3. 生产云 Runtime 不修改。
4. 所有票据完成后只执行一次 Standards + Spec 双轴审查，并报告剩余外部边界。