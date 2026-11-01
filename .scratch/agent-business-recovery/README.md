# Java 智能体业务完整性恢复：执行入口

状态：规格与拆票已获用户确认；实现未开始，业务未验收。日期：2026-11-02。

先读[规格](spec.md)、[说明书验收矩阵](acceptance.md)和[ADR0004](../../docs/adr/0004-java-business-definition-generic-agent-engine.md)。业务用语见根GLOSSARY。唯一业务终点是新后端在真实微信开发者工具完成指定说明书全部可执行场景并有截图，不是票据数量、请求结果或测试通过。

[双轴审查记录](review.md)列出Standards/Spec发现和修正；文档检查与业务验收的证据范围分开记录。

## 工作区与事实边界

- 业务worktree：`iot-agent-backend-business`；分支 `refactor/java-agent-business`；基线 `1f101d5144de08e956614dcf6216a4de9dd67b1c`。
- 原 `iot-agent-backend` 是缓存进行中的工作区。它的未提交业务/缓存修复和47–50票没有自动进入本分支；原工作区及现有服务不修改、不切换。
- 创建worktree的工具复制了ignored本地文件；本分支的 `.env` 已替换为空配置说明。运行前必须独立配置Java/Node端口、MySQL schema、Redis命名空间、Pi Session和Admin状态；不启用复制的业务数据目录、不沿用原Compose持久卷、不运行复制的旧target/dist产物。
- Runtime只读对照，不反改，不部署/切换/测试生产Runtime。需要原未提交修复时先核对实际差异，按本规格单向实现或待其形成明确提交后精准集成，不能复制整个脏工作树。
- 说明书原文已冻结为[软件说明书-2026-11-02](../../docs/reference/软件说明书-2026-11-02.md)，SHA-256 `bc7d7601bcb5a8bb385a90623d09977e5c3f89365c78212b3bf5a8f8cade4e22`。

## 十六组闭环与依赖

所有票初始 `ready-for-agent`，每张票内Blocked by链接才是执行依赖；标签不代表无阻塞或业务已通过。只抓所有阻塞已完成的票。先做[Java定义业务、Node通用执行](issues/01-java-business-generic-engine.md)，不要从末尾总验收或旧resolved票推断实现完成。

|票据|阻塞票|可观察交付|
|---|---|---|
|[01 Java定义业务、Node通用执行](issues/01-java-business-generic-engine.md)|无|业务定义与事件移到Java，真实Office查询及Java-only扩展，Node产物不变|
|[02 办公室照明草案](issues/02-office-lighting-draft.md)|01|当前位置真实候选、状态、非空确认单及编辑/取消/过期重核；承接原46|
|[03 公司目录与混合范围](issues/03-company-catalog-mixed-scope.md)|01|七类设备、授权范围、照明/空调混合与排除、门禁提案|
|[04 空调参数草案](issues/04-ac-parameter-drafts.md)|03|开关/温度/模式/风速和批量/逐台编辑，边界不提交|
|[05 能耗统计与夜间](issues/05-energy-statistics-night-analysis.md)|01|计量日、完整期间、夜间、覆盖/排名、卡片明细与追问|
|[06 能耗比较](issues/06-energy-comparisons.md)|05|时间/区域比较、可比集合与归一、页面/对话一致|
|[07 有依据的报告与导出](issues/07-evidenced-reports-exports.md)|05、06|服务器取证、持久报告、PDF/XLSX和同报告追问|
|[08 安全台账与事件引用](issues/08-safety-ledger-event-reference.md)|01、07|三类台账、周汇总报告、详情引用和关联监控候选|
|[09 真实直播与回放](issues/09-real-video-live-playback.md)|01|真实授权画面、表单窗口、返回释放，最终与08组合走查|
|[10 一次性及区间任务](issues/10-once-interval-task-execution.md)|02|真实确认单、放弃、完整执行适配与隔离恢复验证|
|[11 周期计划与报表](issues/11-recurring-plans-reports.md)|07、10|真实Plan语义、下一次时间、发生实例和定时报表|
|[12 条件空调巡检](issues/12-conditional-ac-inspections.md)|04、10|条件、15/30/60分钟、阶段、检查报告和节能依据|
|[13 任务管理、结果与通知](issues/13-task-management-results-notifications.md)|07、11、12|三视图/区域时间轴、逐设备结果、管理变化、审计和报告/通知|
|[14 身份、引用与恢复](issues/14-identity-references-recovery.md)|01|身份/来源隔离、结构化引用、原对象失败恢复和断线重放|
|[15 Java维护配置生效](issues/15-java-maintenance-config.md)|01|业务指引/模型/限制真实生效、重启和事实恢复|
|[16 说明书全场景收口](issues/16-manual-acceptance-readiness.md)|02–15；外部缓存验收|同一最终构建逐项截图、动态视频、维护演示，承接旧17|

08和11增加07作为真实阻塞：它们分别承诺告警汇总报告和定时报表，必须复用可取证的报告能力；不能为增加并行度假装该依赖不存在。各票实现阶段可先调查不受阻部分，但未满足全部Blocked by不得标完成。授权、人工确认和失败语义随每个领域实现，14不是推迟安全约束的理由。

## 缓存支线的集成门禁

外部缓存工作区的47（普通查询/身份准备）、48（能耗流完整性）、49（前端准备落位）、50（真实读取验收）继续有效。它们当前未作为提交进入本分支；本执行入口不建立不存在的本地票据链接，也不声称缓存已通过。

集成时记录明确来源提交及范围，保留当前已有成果，不把业务重构反向同步到Runtime。主验收须包含准备到落位、当前/历史刷新、完整30天/同比环比、手动刷新与身份隔离；Agent工具不能把preparing当无设备或无能耗。16关闭前必须有缓存分支可审查提交、安全集成和最终页面回归证据。此前可以独立推进不依赖未提交缓存实现的业务票。

## 原票处置与追溯

|历史项|本分支处理|
|---|---|
|[17 Final Parity与切换准备](../iot-agent-backend-refactor/issues/17-final-parity-cutover-readiness.md)|重开业务验收；由新16执行，不另建重复实现|
|[18 独立Pi sidecar](../iot-agent-backend-refactor/issues/18-independent-node-pi-sidecar.md)、[22 事件/工具/进程契约](../iot-agent-backend-refactor/issues/22-sidecar-event-and-process-parity.md)|重开业务归属/前端契约；由新01执行，保留已有Pi/私有认证/Session记录|
|原46办公室照明草案（原工作区未提交）|新02自包含承接其空操作范围失败及未复验事实，不假定修复已在分支，不丢原失败证据|
|原31-manual说明书矩阵（原工作区未提交）|新验收矩阵保留旧24个CASE-ID并补齐全说明书，不将旧接口记录判通过|
|01–16、19–21及23–30领域/接入票|保留已有实现和历史工程记录；未闭合业务按新票验收。10/21与17的历史local readiness不再构成总完成结论|
|30-cache、31–40设计、41–44优化、45首屏|缓存历史与新业务验收分层；原工作区45已撤回并转50，本分支旧文件不作为已通过依据|

旧编号30、31重复，不批量重编号；引用须使用完整文件名/标题。原[规格](../iot-agent-backend-refactor/spec.md)和[阶段二](../iot-agent-backend-refactor/phase-2-spec.md)保留历史但已加当前入口，不允许旧“小程序不联调”范围覆盖本轮验收。

## 每票完成与证据

1. 从既有公开入口复现需要修复的行为或接受用户已经报告的失败事实，单一行为回归→实现→原前端路径验证；不要先横向搭所有层。
2. 每票保存具体CASE证据，再做Standards/Spec双轴审查和语义提交。发现未验条件保持公开，不把下一票或最后总验收当作缺图的借口。
3. 主验收真实账号、模型和授权只读上游。草案不执行、任务可不创建；历史/管理证据按矩阵区分真实来源和独立本地范围，生产控制/任务/通知/配置出口禁写。
4. 不清现有登录或向对话注入内容/卡片；二身份专项用授权身份和独立实例。视频必须真实播放，样片/工具响应不算。
5. 最后在同一集成构建重走全部场景。缺前端截图、缺真实数据或缺授权身份均是未验收，不静默缩减为更容易的案例。

本轮文档发布只证明执行依据和依赖已落盘；没有启动此worktree后端、执行新业务案例或声称任何CASE通过。
