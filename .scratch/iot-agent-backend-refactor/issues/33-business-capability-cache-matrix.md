# 33: 全业务能力缓存分类矩阵

**Type:** grilling
**Status:** resolved
**Blocked by:** 31

## Question

逐项覆盖身份、Company/Office dashboard、设备目录、区域设备、设备状态、环境、能耗历史/统计/报告、安全台账、视频地址、Action Draft、Task/Plan、通知、会话、Pi Session、Admin 和 Langfuse：对每项标注“可缓存/只能合并在途/必须实时/Business Fact”，真实数据的来源、权限敏感度、前后端责任和失效动作。

## Evidence to use

- backend `issues/30-cache-architecture-audit.md`
- `mp/apis/agent/index.js`、`mp/apis/agent/AGENTS.md`
- Runtime `apps/runtime/src/AGENTS.md`
- `mp/docs/acceptance/物联Agent软件操作说明书（V0.2 细节版）.md`

## Answer

矩阵以业务路径、数据责任和当前代码证据为准，不把所有能力统一成一种 cache：

| 业务能力 | 当前业务路径 | Backend 责任 | Frontend 责任 | 事实/实时规则 |
| --- | --- | --- | --- | --- |
| 身份与来源 | `Authorization` + `Tenantcode`，Office/Company 隔离 | 认证边界与来源校验；不缓存成普通读快照 | 仅用身份 scope 隔离本地读快照 | 认证不属于普通缓存模板 |
| 首屏 Company/Office 概览 | dashboard、environment、energy、alerts、automation | 当前数据应提供可直接返回的最新结果；避免页面打开后串行全量上游 | 60 秒读快照、先落位后对齐、失败不伪装成功 | 当前数据策略；部分结果保留明确缺数 |
| Company/Office 目录与区域 | company-devices、regions、region-devices | backend 可复用当前目录/区域结果；工具查询不能每次重拉完整目录 | 当前区域入口按页面需要读取；现有区域 GET 未统一接入读快照 | 目录可短时复用；控制/引用前仍按既有授权规则 |
| 设备状态/环境读数 | region states、dashboard observation、Agent `read_device_states` | 常用当前状态优先复用已有 backend 结果；查询逻辑避免串行重复上游 | 当前状态刷新独立处理，不用普通历史快照覆盖执行回读 | 当前数据；执行后回读沿业务事实/实时读取 |
| 设备候选与能力查询 | Agent `find_device_candidates`、`inspect_device_capabilities` | 需要低延迟可检索的当前目录；先修正重复读取和候选引用逻辑 | 不缓存模型工具结果 | 工具结果是本轮事实，不作为 frontend 普通快照 |
| 能耗当前统计 | Company meter report、daily/current summary | 常用范围可复用 backend 最新结果；低频查询按查询键合并请求 | 现有 Company 读缓存复用完整结果，preparing/truncated/error 不写入 | 当前数据/可重建报告快照；不替代 Report Fact |
| 能耗历史 | energy history、日期窗口统计 | 历史窗口可按查询键复用；补数/迟到采样另定刷新 | 历史窗口允许较长复用，手动刷新重新请求 backend | 历史数据通常稳定，不等于不可修正 |
| 安全台账列表 | safety history list | 可复用完整列表读取；分页键必须完整 | 现有列表显式读快照；详情/摄像头候选实时 | 完整无错误结果才复用；详情保持实时 |
| 视频直播/回放地址 | video stream/playback | 不把短时 URL 放普通 cache；每次按当前请求取短时授权地址 | 不缓存 URL | `no-store`、短时效、来源明确 |
| Action Draft | draft、selection、parameters、confirmation | MySQL Draft/Receipt/Readback 为事实；确认时走业务执行规则 | 不走普通读快照；写后清理相关读快照 | 事务/事实，不是普通 cache |
| Task/Plan | task list、plan list、task detail、execution status | MySQL 事实与 projection；列表可提供当前结果，执行态不能依赖陈旧列表 | 列表/周期安排/通知首屏已有 60 秒快照；详情与执行态实时轮询 | 列表可短时复用，执行/确认/详情按实时规则 |
| Notification | inbox/unread/seen | MySQL notification fact/projection；列表读取可复用 | 首屏通知可读快照；已读操作局部失效 | 已读等写操作不能被旧快照覆盖 |
| Report Fact/下载 | report metadata、PDF/XLSX | MySQL Report Fact；下载 `no-store` | 不缓存下载文件/短时文件路径 | 事实持久化，下载实时授权 |
| 会话与 Pi Session | conversation/events、Node SQLite session | Java 业务事实/MySQL；Pi Session/compaction/branch 由 sidecar SQLite | 会话恢复按接口读取，不放普通读快照 | 事实/恢复数据，不是 Redis cache |
| Admin session/config | 管理登录、配置 revision | 配置 revision 是事实；session 是临时运行态 | 管理端不影响小程序业务读快照 | 不纳入物联业务缓存 |
| Langfuse/模型用量 | trace、usage、cacheRead | 观测写入/发送，不作为业务 cache | 不参与业务数据缓存 | 诊断数据，不改变业务事实 |

## 结论

1. 当前 frontend 已有一层 60 秒同身份读快照，但覆盖范围是显式指定的只读路径，不是所有 GET。
2. backend 当前只有 Company dashboard 的局部进程内 cache；Redis 原语存在但业务未接入。
3. 首批必要 backend 矫正应优先覆盖：首屏当前数据、常用目录/状态、Agent 候选查询和低频查询的在途合并；不先实现全量 Redis cache。
4. Action/Task/Report/Pi Session 等事实仍按 MySQL/SQLite 事实语义处理，不能为了缓存统一而降级。
5. 视频 URL、确认/执行/readback、任务执行态和详情不进入普通读快照。
