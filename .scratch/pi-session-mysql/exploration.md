# 探索收敛记录

Date: 2026-11-02
Scope: 本轮只探索与规划，无实现、部署、数据迁移或子代理。

## 设计分支与结论

|决策|已选方向|依据/取舍|
|---|---|---|
|持久化归属|Java/MySQL唯一权威|客户已有MySQL，Java团队维护；不让Node接管数据库迁移和凭据|
|通用引擎归属|Node继续Pi，不重写循环|保留底层能力，业务定义和规则仍归Java|
|存储语义|Pi 0.84.4 SessionRepo/SessionStorage|已读取当前安装版声明，涵盖Entry、LaneRecord、共享sequence、lane、fork、日志、统计、开放操作等|
|社区实现复用|借鉴契约，不直接安装PostgreSQL包|PiCloud历史ADR针对0.84.1，当前源码已升级；另一个包是Pi Durable 1.x Alpha，均非本版MySQL适配器|
|原会话处理|一致性备份并保真导入新后端存量|不清历史、不猜租户、不复制业务事实，不碰原Runtime生产库|
|在途失败|保留已提交检查点，未确认工具不自动重放|不承诺模型请求无缝续跑，更不承诺外部副作用exactly-once|
|写入冲突|入场所有权和陈旧写拒绝|末尾CAS不能阻止两个模型先行重复调用工具；换MySQL不自动解决writer生命周期|
|迁移发布|集成分支一次受控切换|无双写、SQLite自动回退或空历史假恢复；04/05合完才提供发布候选|
|运维|Java管理面和MySQL备份恢复|SQLite专用页面、Java转发、Node运维入口及部署卷都属于切除范围|
|最终验证|官方契约+真实进程故障注入+开发者工具截图|只有工程证据不能判业务通过；只在全部完成后做一次审查，不启动子代理|

用户已经给出Java主维护、去SQLite、保留Pi及完整业务的目标；按保留数据、禁止未确认副作用重放的保守方式收敛，没有另造需要用户选择的技术分支。后续若必须缩减旧历史保真或Pi接口范围，必须明确提出，不能默默改变目标。

## 已执行的探索验证

在当前业务worktree中，使用Node22从已安装的固定Pi0.84.4导入公开的 `@earendil-works/pi-agent-core/session/testing`，调用 `createSessionBackendConformance` 枚举用例但不调用工厂、不运行用例：

- publicEntrypointLoaded: true
- availableCases: 30
- executedCases: 0

这证明官方一致性入口在本项目实际可加载；**不证明MySQL实现存在或一致性测试通过**。

对本轮规划文件做了本地链接与阻塞图检查：六张独立票据、唯一根票01、无循环依赖；02/03只依赖01，04依赖02+03，05依赖04，06依赖05。所有执行状态均为not-started，不因规划检查通过标成实现完成。

## 实施时仍须取得的证据

- 01用真实Node→Java→MySQL跑完整官方一致性测试，验证私有批次与事务语义；不能只通过内存仓储或JDBC单测。
- 典型和长历史下测量读取、追加、上下文重建的时延及传输量；不在每次追加时全量读取/重写整个历史，不凭空承诺性能提升。
- 生产MySQL版本、字符集、时区、权限和备份策略按客户实际环境确认；本地实例验证不冒充生产部署已验收。
- 备份覆盖已提交WAL，旧库中无法确认的归属或未知记录必须有清单，不能跳过后宣称无损。
- 现有Java后端的业务缺陷和未通过回归独立保留；本计划不把它们消失或父票已完成作为假设。

## 资料与定位

- 当前源码：Node的PiSessionStore与AgentRunner；Java按owner/source/conversation生成sessionScope；Java管理操作仍转发Node SQLite路径备份/恢复/租约接口。
- 公开契约：已安装Pi0.84.4的harness/session类型声明及session/testing公开导出。
- 历史决策：ADR0001/0003/0004及旧persistence-route规格，均已加ADR0005后续方向链接，历史内容保留。
- 社区一手来源及版本限制见[研究记录](../agent-business-recovery/research-pi-mysql-session.md)。
