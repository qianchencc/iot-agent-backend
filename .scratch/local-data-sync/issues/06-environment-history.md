# 06: 环境当前/历史测点样本及 HTTP/Agent 只读本地化

**What to build:** 环境测点当前值与历史样本同步入本地MySQL；environment-history、dashboard环境段与Agent只读改走本地，保留测点/指标/单位/时间和质量语义。缺本地覆盖明确准备/不可用，只有完整覆盖且确实无样本才显示原有空态；不回源、不伪造样本或测点区域归属。

**Blocked by:** [03-office-candidates.md](03-office-candidates.md)（区域/目录关系先行；未完成不得开工）

**Status:** in-progress（本地读取、前端质量处理、定向构建及真实jar/MySQL隔离烟测完成；真实微信UI待验，固定实例未由本票切换）
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- schema：环境样本按真实来源记录ID或G2核实的稳定组合键去重；不假设同测点同秒唯一，不把可变质量当记录身份。结构化保留测点/指标/采样时间/质量/隔离维度，数值用decimal，扩展才JSON；经SchemaMigrations登记。
- 同步：当前值随状态节奏、历史按持久范围挂接02骨架（独立进度/覆盖行，同 singleflight 语义）；不为速度丢弃边界样本或缩短区间；错过窗口恢复补跑。
- 读取：environment-history 与 dashboard 环境段基础入口本地化（包根公开）；时间范围/粒度参数的外部契约语义不变；Redis miss 从 MySQL 重建并重验当前权限。
- 同源一致：dashboard 环境段与 environment-history 读同一本地入口，不各自实现。
- 测点发现：测点/设备维度随目录与状态节奏同步，历史范围独立持久。
- UI：环境历史曲线与当前值展示本地数据；完整覆盖且确实无样本才显示空态，未覆盖显示准备，部分缺失如实说明，不凭缺数连线伪造趋势。
- UI错误：空态（区间无样本）与错误态（不可用）区分展示，不互相冒充。

## 非目标
- 不做区域时间轴/日历/未读聚合（11）；不做能耗（05）；dashboard 四域统一消费与首屏指标归11。

## 行为验收
- [x] environment-history 与环境段读取不再触发上游（契约测试及实际进程断源验证）；Redis禁用仍可读。
- [x] 跨租户/主体/来源环境样本互不可见。
- [x] 未覆盖区间不是EMPTY：按既有准备协议；已覆盖部分缺样本说明真实缺口，完整覆盖无记录才是业务空态。
- [x] 重复同步幂等；采样时间不被同步/刷新时间覆盖。
- [x] MySQL 不可用→UNAVAILABLE 明确错误，无回源兜底。
- [ ] 环境页开发者工具 UI 截图验证（含空态/错误态）。
- [x] 聚合粒度边界不越界：请求区间外样本不计入结果。
- [x] 无 observed_at 或质量异常的样本按 PARTIAL/truncated 口径透传，不静默丢弃。
- [x] 测点从目录消失后历史样本保留，不因目录更新删除事实。
- [x] 服务重启后按持久范围恢复补跑，不重复已成功批次；RUNNING崩溃恢复继续归12。
- [x] 环境历史与 dashboard 环境段同源一致（持久夹具及实际进程覆盖）。
- [x] 隔离程序演示同步、Redis空与客户数据上游不可用时的本地读取；微信抽样按总验收图，工程证据不替代真实UI，不写生产控制/任务/通知/配置。

## 证据与既有公开接口
- 公开符号：ObservationAdapter.read、ObservationRequest、ObservationResponse、ObservationOperationEnum.ENVIRONMENT_HISTORY、ObservationStatusEnum、ReadSnapshots。
- 既有测试接口：ObservationAdapterContractTest、CompanyOfficeObservationAdapterContractTest；测试经包根入口，不从 internal 导入，不开新测试框架。

## 工程证据（2026-11-06）

- 最新冻结产物SHA256为`7e14e4b8642aff3cd965c455bf015619b2f98c0b9aef0339016af0848eb87876`。独立Java34781、模拟源34789、独立MySQL schema，Redis禁用；未更改3381/3382及其数据，烟测进程已停止。[实际运行证据](../evidence/environment-history-smoke-20261106.json)。
- 冷窗口先返回preparing；后台完整流成功后，2020温度`25.1234567890123456789`经真实JSON数字/MySQL/HTTP保持精度。当天缺温度值保留NULL，未知时间的999保留原文与质量但不计峰值，已知峰值26，整体partial且observedAt为NULL。
- 当前环境值独立来自本地DeviceObservations，不以历史末条或未知时间冒充当前；dashboard温度26及真实采样时刻保持。两段历史和dashboard普通读均未增加数据源请求。
- 同一冻结产物重启，关闭全部客户数据出口而保留身份核验，历史/当前仍200且样本和原时间一致；全量重试失败后cache为stale、lastError明确，updatedAt未改，普通读取数据源增量仍0。
- EnvironmentHistoryBehaviorTest最新13项与AgentBusinessDefinitionContractTest18项通过；另运行观察/组合定向package。前端Agent339项及182文件边界检查通过。缺值、未知时间、精度、跨页失败、权限代次、幂等/修订、03:00补跑、并发旧全量不覆盖新当前、曲线NULL断点均有行为回归。
- 微信编译/真实环境页空态错误态/趋势交互截图尚未取得；不把上述模拟来源、H2、HTTP或编译结果记为真实业务验收。

## 2026-11-08：Agent环境趋势完整窗口

- `query_environment_history`与`environment_window`引用统一设置内部`fullWindow=true`，既有本地读取一次查询完整窗口，再沿原有统计和事件映射生成单张卡片；工具不再暴露page。HTTP原始明细保持100条分页，不新增缓存、同步或模型配置。
- 定向回归验证165条样本、早期页峰值35、引用与卡片统计一致、原始明细100＋65及零普通读取回源；101条未知时间样本读全后仍为partial，未知时间不制造峰值。
- 真实MySQL同一窗口只读烟测返回165条、完整且无截断；原始第一页仍100条且nextPage=1。全窗温度峰值27.4°C，首次出现于上海时间11月4日12:00（原第一页只见到11月5日16:00的同值峰值）。授权范围为诊断夹具，仅验证真实数据读取，不冒充身份链路或微信业务验收。[脱敏工程证据](../evidence/environment-full-window-20261108.json)。
- 定向环境/Agent契约54项通过。全量710项中681通过、28跳过、1项`SafetyReviewToolContractTest.singleCandidatePreparesRealPlaybackCardAndProjectsVideoPlaybackBlock:81`失败；不扩展修改安全录像。后续按用户要求已于22:41将固定Java3381更新到`var/fixed-environment-window-20261108-224008/backend.jar`（PID4647），Node3382/PID32963保持不变；原配置和业务记录数保持，私有发布目录保留数据库备份、回退路径和verification.json。微信新查询及会话恢复仍待验收；已有历史卡片不回写。
