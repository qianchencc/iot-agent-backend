# 05: 原始能耗持久采集与统计/比较/报告读取本地化

**What to build:** 电表原始样本持久化入本地 MySQL，全部能耗读取——energy-history、energy-comparison、energy-report、company-energy-daily、company-energy-report、company-meter-report——的 HTTP 与 Agent 路径改走本地计算；保留 08:00 计量日、20:00-08:00 夜间窗口、同比/环比/自定义区间、边界采样与 partial/truncated 质量语义；历史按需补缺，不为速度缩短日期或伪造零值。

**Blocked by:** [02-company-catalog.md](02-company-catalog.md)（复用同步骨架；未完成不得开工）

**Status:** in-progress（工程实现及隔离MySQL进程验证已通过；真实微信UI未验收）
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- schema：原始电表样本按真实来源记录ID或G2核实的稳定组合唯一键去重；不假设设备同秒唯一，不把可变质量字段当记录身份。采样时间、质量及隔离维度可查询；数值用decimal，精度按来源契约确定；月/日统计复用原始样本与现有计算，不另建事实库；经SchemaMigrations登记。
- 同步：当前样本随约60秒节奏幂等批写（成功才推进进度/覆盖，失败保留上次完整覆盖）；首次及每天上海深夜全量同步合法来源可访问历史（2026-11-06最新决定覆盖此前首次仅按需限制），无24个月边界；夜里定时启动后跑完，不新增到点中止/跨夜轮转；错过计划恢复补跑；能耗并发沿用6。日期过滤不可靠、页号会漂移，采用无筛选流和已核实完整性检查，不承诺增量游标。
- 读取：六类能耗端点本地入口（包根公开），统计口径与 CompanyEnergyObservationComposer/MeterReports 现契约一致；Redis miss 从 MySQL 重建；CompanyEnergyPolicy 可见性口径不变。
- 双入口一致：六个能耗端点与 Agent 能耗工具共用同一本地入口与聚合实现。
- 报告事实：已存报告事实（report_facts）不被补数静默改写，重新取数只影响新报告（报告侧复核归08）。
- UI：能耗曲线、对比、月报/日报渲染本地数据；尚未覆盖明确准备，覆盖不完整按既有partial/truncated等语义说明缺口；只有已完整取得且确实无读数才是原有noData/空态，不画伪零。
- UI错误：补缺中区间显示进行中标记与缺口说明，不画假平滑曲线。

## 非目标
- 不做环境（06）；不做报告生成与下载授权复核（08）；不做 dashboard 统一（11）；不做跨领域历史校正调度（12）；已采集原始数据不新增自动删除（正式保留期待审批，不在本票偷定）。

## 行为验收
- [x] 六端点读取不再触发上游；同一普通适配器供HTTP与Agent使用，聚合结果沿既有Composer契约。
- [x] 同窗口重复同步幂等：不产生重复行、计数不重复累计。
- [x] 补缺未完成明确准备/覆盖不足，不以EMPTY或零表示尚未采集；已有完整结果不被半份更新覆盖。
- [x] 采样时间/成功同步时间/尝试时间分列；接口observedAt和逐行时间保持真实来源，cache.updatedAt为同步时间。实际UI展示仍见未验项。
- [x] 08:00计量日、20:00-08:00夜间、同比/环比/自定义、边界采样和partial/truncated保留既有公式，定向契约覆盖。
- [x] 跨租户/主体/来源隔离；MySQL不可用明确UNAVAILABLE，无回源兜底。
- [ ] 能耗页与月报开发者工具 UI 截图验证（含 PARTIAL/EMPTY 态）。
- [x] 补缺范围持久化；重启后保留，首次/每日全量成功推进READY。
- [x] 08:00跨日归属和原始采样可追溯，不因本地化改公式。
- [x] 来源JSON数字→BigDecimal→MySQL DECIMAL→HTTP数字精度往返无损，不先经浮点解码。
- [x] 失败保留上次完整覆盖并可重试，尝试/成功时间分开；专用授权刷新失败同样标stale，重复前台读取不抹掉重试到期。
- [x] 隔离真实jar/MySQL实跑未访问2020窗口准备→全量发布，重叠窗口复用，进程重启且客户数据全503仍读六路本地结果；不代替真实微信截图。

## 证据与既有公开接口
- 公开符号：`EnergyHistory`（替代已删除的`EnergyHistoryStore`上游扫描缓存）、`DeviceDirectory.energyHistory()`、`MeterReports`、`NightMeterReport`、`MeterReportLayout`、`MeterReportProfile`、`MeterReportWindows`、`CompanyEnergyPolicy`、`CompanyEnergyObservationComposer`。
- 既有测试接口：CompanyEnergyObservationComposerContractTest、ObservationAdapterContractTest、IotAdapterConcurrencyContractTest；测试经包根入口，不从 internal 导入，不开新测试框架。

## 本轮实现与工程证据（2026-11-06）

- 六表迁移`local-energy-history-v1`已在已有隔离MySQL库升级并重复启动；包根`EnergyHistory`提供专用电表范围、原始页与含左右边界/最新元数据的样本窗口。普通HTTP、Agent与既有能耗Composer只使用本地入口；删除旧`EnergyHistoryStore`、月份热缓存、普通读取上游扫描及页数/行数预算。
- 独立当前采集约60秒；首次及每日上海03:00无筛选全量扫至可信末页，已知总页最多6路并发，无24个月边界、总120秒预算或跨夜轮转。检查页号/总数/末页/来源排序/重复与跨页漂移；私有暂存完整成功后原子发布。同秒不同ID保留，缺ID显式命名空间，冲突不充当可计算样本。
- `EnergyHistoryBehaviorTest`覆盖精确decimal、2020年历史、未访问窗口持久补缺、同秒/回退键、幂等修订、失败半页、异常总数/精度、03:00与停机补跑、换证/独立授权、跨主体/租户、DB故障、较慢全量不得覆盖较新当前值、授权源失败及补缺不绕过后台重试。既有Composer公式和HTTP准备→采集→本地读取契约已迁移，不固定旧扫描预算。
- 最新能耗/IoT/真实动作/目录/观察/任务读取/ArchUnit定向构建：20组199项，198通过、1条件跳过，`mvn … package`成功；不代表全套回归通过。
- 真实程序：冻结jar独立端口34781、生产协议模拟来源34789、独立MySQL schema、Redis禁用。2020样本`12345678901234567890.1234567890123456789`以JSON数字输入，数据库和HTTP Decimal结果相等；30个08:00日窗口各用量10，明确区间统计和计量报告均为10。六端点200，普通读数据源调用增量0。
- 同一最终jar重启，客户数据分支全部503而账户身份核验可用：六端点仍200，原始样本及observedAt不变，统计值保持；强制隔离库全量到期后，专用授权刷新真实失败，进度为FAILED，六类cache均stale，之后普通读取仍零来源调用。所有临时进程已关闭，未切换固定实例或进行设备动作。
- 烟测发现并修复：同步器误注入普通Iot包装器导致授权自依赖；MySQL INSERT SELECT upsert旧值列歧义（H2未报）；默认HTTP JSON解码先转浮点造成小数损失。原始Iot/Observation传输现以独立Source类型注入后台。
- 未验：真实微信能耗曲线/日报/月报及PARTIAL/EMPTY画面；RUNNING中途崩溃恢复、跨领域运维与统一最终构建继续归12—14，不以本票定向测试冒充。

## 2026-11-06 重启刷新与凭据续期修复

- 已用公开`EnergyHistory`/`DeviceDirectory`合成夹具复现并修复：不变授权的定时刷新误使读取失效、授权元数据与列表分两次查询产生假空范围、同一窗口混用发布前后样本，以及合法同主体换证后已有历史被凭据代次隔开。失败先行的行为回归保留在`EnergyHistoryBehaviorTest`；HTTP契约确认真实范围变化返回`ENERGY_AUTHORIZATION_CHANGED`/409而非200 preparing。
- 授权版本只反映成员/凭据代次变化；元数据与列表一次查询。样本和覆盖仍以来源实例/租户/已核验主体/来源的稳定scope隔离，查询通过当前已核验专用电表授权过滤；凭据代次继续约束授权接纳、暂存提交与迟到写入，不再充当历史对象身份。收窄授权立即拒绝被移除电表，不触发剩余电表重拉；新增电表沿既有补全规则。换证未取得新授权、跨主体/租户及旧代次结果均不能绕过校验。
- 统计窗口改为单条MySQL一致性查询：批量范围、一次分组边界和最新采样、同刻全部来源记录、覆盖一并读取；没有新增缓存或自动重试。首版相关子查询在真实MySQL退化，已替换，`EXPLAIN ANALYZE`正常数据查询约505ms；H2通过不替代MySQL执行计划证据。
- 最终定向19项能耗行为＋1项HTTP＋18项Composer＋4项ArchUnit均通过，`mvn … package`成功；完整`mvn test`为701项、670通过、28跳过、3项原有告警/视频断言失败，不标全绿。新增覆盖含真实撤权、同主体续期/重启复用、新凭据专用授权失败关闭、旧代次采集不能覆盖新结果、同刻边界及只返回选中电表。
- 固定Java3381于21:22运行`var/fixed-energy-20261106-212234/backend.jar`（SHA256 `89318377c7193721bedf81d6ad5aa518f3d090f9ec6afbea5b348940dda675a5`）。Node3382未重启；配置/密钥/库/Redis未改变，没有备份、模拟业务结果或设备控制。此前旧版20:42凭据续期确实触发过不必要全量，20:54自然完成；最终重启前后样本237624/43台、覆盖43条、全量开始/成功/下次到期保持，后台正常刷新授权版本仍98。
- 微信真实前端强制读取并打开能耗统计：日统计1762/891ms，三期月统计602/754/816ms，全部200且无preparing，30天/昨日123.43度、26楼66.27度、27楼57.16度进入实际渲染；没有错误通知。原生截图接口超时；桌面窗口截图`/tmp/energy-history-final-desktop.png`可见昨日及楼层构成，顶部受开发工具账号弹层遮挡。不能据此关闭完整UI、PARTIAL/EMPTY、手机真机或12—14票的恢复验收。
- 维护检查：重启验证需同时记录普通刷新与合法换证前后的持久覆盖/范围版本，不能仅凭healthz和一次200收口；SQL优化必须在真实MySQL上测实际扫描成本，不能只以H2行为测试或SQL条数判断性能。
