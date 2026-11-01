# 30: Runtime 全量行为对齐与案例闭环

**What to build:** 以 `iot-agent-runtime` 的生产行为、测试案例和 NDJSON/API 契约为对照，逐项修复 backend + Node sidecar + mp 本地链路差异。任何输入、状态、错误、事件、权限、确认、readback 或前端可见结果不一致，都必须继续修复，不能以真实上游当前无设备作为完成条件。

**Blocked by:** 29: Company 控制、视频与权限闭环

**Status:** in-progress

## Case matrix

- [x] 固定 backend、Runtime、mp 三方基线；生产 Runtime 只读。
- [x] 复现并修复 Company 候选检索拒绝 Company source 的问题。
- [x] 复现并修复 `observation.reported` 视频 block 被放入 `details.blocks` 而非顶层 `blocks` 的问题。
- [x] Company `read_device_states` 为视频设备输出 Runtime-compatible `video` observation block。
- [x] Company AC：已满足跳过、readback 等待/freshness、旧状态不得误报 verified、逐动作 outcome、INTERRUPTED 语义。
- [x] Company lighting/access：leaf 目录、合法命令、确认与逐设备 receipt 与 Runtime 完全一致。
- [x] Company video：live/playback scope、窗口、错误、no-store、card、恢复和前端播放链路完全一致；真实上游设备缺失时保持 fail-closed。
- [x] observation/report：环境历史、能耗比较/月报、Company meter report、alerts、safety camera review 已接入；未知客户报告/PDF/XLSX 协议保持 typed unavailable。
- [ ] task/scheduler：任务确认、变更、取消、列表/详情、readback/report blocks 与 Runtime 状态机完全一致。
- [x] NDJSON：视频/观察 block 顶层事件、sequence 去重与任务 confirmation 前端应用已对齐；任务全状态恢复仍待 backend 事件持久化对拍。
- [x] mp：本机 backend profile、事件渲染、确认进度已对齐；真实微信开发者工具 GUI 环境仍需安装/提供。
- [ ] 软件说明书案例逐项完成 backend 真实请求和前端验收证据。

## Latest evidence (2026-11-01)

- Company natural-language read path now returns 43 authorized energy devices for `查询公司授权范围内的电表`; the `公司`/`公司授权范围` terms are treated as whole-scope markers, matching Runtime `companyQueryWithoutWideScope`.
- The same authenticated local backend + independent sidecar path returns `observation.reported` with 43 candidates and a terminal `run.completed`; no production Runtime was changed.
- The manual case `26楼运营办公区的灯现在什么状态` remains an honest zero-candidate result because the current authenticated Company upstream returns HTTP 204 for `lighting/myAreaList`, `access/myAreaList`, and `monitor/myAreaList`; this is an upstream authorized-catalog fact, not a backend fallback. The backend must not synthesize a controllable device.
- Independent task checkpoint report lookup and enabled-plan `nextOccurrenceId`/`nextCheckpointAt` projection are now implemented and covered by regression tests.

- Durable recurring-plan materialization now runs behind `TaskOccurrenceMaterializer`; MySQL/H2 tests prove concurrent/restarted scans do not duplicate, skips stay unmaterialized, overrides preserve plan identity and credentials stay redacted, and `next_date` advances.

## Acceptance

1. Runtime 相关测试案例在 backend 对应契约测试中有等价覆盖，或明确记录协议不可得的外部前置条件；不可用能力不得伪造成功。
2. 本机 backend + sidecar + mp 开发链路使用同一 Company source/identity 通过抽样案例。
3. 生产云 Runtime 不修改。
4. 所有票据完成后只执行一次 Standards + Spec 双轴审查，并报告剩余外部边界。