# 同步与存储治理：只读事实记录

> 本文是2026-11-11实施前的只读快照，源码行号、旧日志及归档状态不代表当前运行。2026-11-12已完成日志退出、四处局部减写及部署；当前结果以[执行入口](README.md)和[实际验证](verification.json)为准。

> 用户后续已纠正治理方向：上游数据是方便本地读取的可重建副本，不以保护其不可丢失为前提设计binlog／灾备体系。本文保留历史测量与技术事实；恢复门禁、强制去重迁移等旧建议已撤回，当前取舍以[修订方案](spec.md)为准。

日期：2026-11-11。本文是当日现场快照，不是持续监控，也不是修复后证据。处理方案见[规格](spec.md)。

## 1. 调查范围与方法

- 本轮不修改业务代码、数据库、配置或服务；不调用客户上游，不手动触发同步，不执行设备控制。
- 通过本地 Docker 内 MySQL 查询 information_schema、performance_schema、同步进度和聚合结果；记录时间来自本机／现有库，不更改时区。
- 使用 `SHOW BINARY LOG STATUS` 记录起止游标；`SHOW BINLOG EVENTS` 读取这段日志的事件位置和表映射，按行事件字节归因。未输出行正文、身份、凭据或设备值。
- 跨自然同步周期在内存中比较最近发布的500条电表记录，输出相同身份数量及变化字段计数，不保存客户数据。
- performance_schema 的写计数包含 INSERT … ON DUPLICATE KEY UPDATE 的插入尝试，不能把 COUNT_INSERT 当作净新增行数；净增量另用 COUNT(*) 核对。
- 不重跑前轮已报告的缺表空间故障，不将累计日志大小除以实例运行时间当作当前增长率。

## 2. 运行版本核对

- Java PID63661：`var/safety-identity-20261111-153405/backend-revised.jar`。
- 运行JAR SHA256：`ef026a070bbb8b0e99cafbd81db84bd50075dfcb6edafd170d5ca7cf91353d47`。
- 运行JAR与现有target/classes中以下类逐字节一致：JdbcCompanyDirectory、EnergyHistoryCollector、JdbcEnvironmentHistory、EnvironmentHistoryCollector、JdbcSafetyHistory、JdbcDeviceCatalog。
- JdbcEnergyHistory、JdbcDeviceObservations的class原始字节不同；临时提取后，`javap -c -p -constants`输出归一常量池编号，指令差异为0。临时提取文件已删除。
- 以上用于限定本次源码分析与运行现象的对应关系，不等于重新从源码构建、全JAR逐字节可复现或全量部署一致性证明。Java LSP引用查询启动失败，本轮未修改符号；依据直接源码调用及运行指令核对。

## 3. 空间口径

下表是不同口径，不能直接相加，也不能把物理分配空间当作有效业务正文。

| 对象 | 当日结果 | 含义 |
|---|---:|---|
| 清理前工作目录 | 5.163 GB | 源码、Git、依赖、运行产物、本地基础设施等 |
| 清理后工作目录 | 1.803 GB | 保留现行运行、回退、备份及本地数据 |
| Git跟踪文件内容 | 94.61 MiB | 含77.62MiB的.scratch材料，不含历史 |
| 跟踪文件去掉.scratch | 16.99 MiB | 代码、资源、文档等，不等于只有Java源码 |
| 跟踪文件磁盘分配加.git | 195.36 MiB | 当时本地仓库口径，不是精确fresh clone大小 |
| 主业务schema数据＋索引 | 552.59 MiB | information_schema估算，321.19数据＋231.41索引 |
| 主业务schema物理文件 | 850.9 MiB | 含页分配、空闲空间等，不保证删除行后立即缩小 |
| 独立业务MySQL整个卷 | 20.80 GB | 不在上述工作目录内，含其他旧验证schema和日志 |
| 独立业务MySQL binlog | 19.371 GB | 全实例累计，不仅当前业务schema，也不全来自当前版本 |

清理轮次处理的是工作目录内本地MySQL旧日志及停用发布产物，不是独立业务MySQL卷。不能把“工作目录已清理”表述为“所有数据库存储已治理”。原清理记录见[运行入口](../agent-business-recovery/local-preview.md)。

## 4. 自然同步窗口

- 时间：2026-11-11 22:13:00至22:14:10，含查询耗时约71.5秒。
- 起点：`binlog.000021`，position457925599；期间无轮转。
- 电表样本：477866→477866，净增0。
- binlog增长：3.717MiB。约3.12MiB/min；若同负载持续整日，外推约4.38GiB/day，**不是实测全天增长或容量承诺**。
- 行事件可归因3.077MiB，其余包含事务、表映射等事件开销。

| 表 | 该窗口行事件MiB |
|---|---:|
| local_company_devices | 0.538 |
| local_energy_sample_batches | 0.456 |
| local_energy_samples | 0.450 |
| local_company_locations | 0.287 |
| local_device_state_sync | 0.240 |
| local_company_grants | 0.204 |
| local_device_states | 0.201 |
| local_device_catalog | 0.135 |
| local_energy_meters | 0.135 |
| local_safety_batches | 0.113 |
| local_safety_events | 0.100 |
| local_environment_sample_batches | 0.082 |

上述是字节归因，不等于每一字节都可消除。设备真实新采样、权限更新、领取／发布进度仍需写库。

随后约101.6秒对照最近500条电表记录：前后500个记录身份相同；meter/source ID、缺ID标识、read_time、四个decimal数值及conflicted全部未变；500行update_time和synchronized_at均变化。证明“事实内容未变，仍发生逐行时间改写”，不证明本周期发生全量历史重扫。

## 5. 历史规模与重叠版本

- 当前保存477866行，43台表，read_time覆盖2024-05-23至2026-11-11；当地入库始于2026-11-06。
- 按来源网关实例、租户、company／office、sample_key分组：238054个记录键、239812个额外来源副本，单键最多5份。
- 全部238054个键存在跨scope重叠。对epf、epz、total_electric、power_factor、conflicted五项做一致性聚合，业务值不同的键为0。这里只证明这五项在该快照相同，不表示来源元数据／覆盖／授权／发布顺序可丢弃，也不是通用合并规则。
- 两份最大的来源副本分别238054和237839行；较旧副本最后更新在11月9日。另有715、629、629行的部分副本。不据此宣称正在持续翻倍或账号越多必然全量重采。
- 电表读取在[JdbcEnergyHistory](../../src/main/java/ltd/qianc/iotagent/iot/internal/JdbcEnergyHistory.java)的window中按sample_key做ROW_NUMBER，顺序为synchronized_at降序、scope_key，再取revision_rank=1。**物理重叠不证明报表重复计数**。
- [ADR0009](../../docs/adr/0009-shared-device-facts-independent-grants.md)明确本轮既有共享迁移不改写电表原始历史，并保留事实来源索引。因此“当前读共享、历史按scope存储”是已有保留设计；转为一份物理事实需要新的受控迁移，不是清理脚本。

## 6. 调度与日志配置

- 活跃的4个能耗范围为READY，查询时下一次全量均指向2026-11-12 03:00；另1个DENIED范围保留过去到期时间，不代表它仍在正常采集。
- 查询时暂存表0行，无正在执行的能耗全量。历史调度可因首次、错过计划、失败恢复、新授权范围触发白天补跑，因此最新全量时间不在03:00本身不是故障证据。
- 业务MySQL：ROW、FULL行映像；binlog_expire_logs_seconds=2592000（30天）；自动清理ON；sync_binlog=1、innodb_flush_log_at_trx_commit=1。
- SHOW REPLICAS、复制连接状态、在线Binlog Dump连接均无结果。这不证明不存在离线消费者、备份依赖或时间点恢复要求。
- 仓内Compose未显式给出业务binlog保留策略。30天是当前有效值，不可描述为“完全没有自动清理”。恢复点目标、可接受数据损失及备份周期没有在已读规范中定案，不能擅自改为某个天数或关闭日志。

## 7. 核心实现依据

| 位置 | 已核实行为 |
|---|---|
| [JdbcCompanyDirectory](../../src/main/java/ltd/qianc/iotagent/iot/internal/JdbcCompanyDirectory.java):320–343 | 目录、当前、历史三个5秒调度检查；业务到期条件并非每5秒都采集 |
| 同文件:372–435、489–519 | 当前约60秒，历史按到期；复核凭据／授权，能耗finally清暂存 |
| 同文件:630–670 | 目录按scope整批删除／插入，推进batch_version；版本还被读取一致性和在途发布使用 |
| [EnergyHistoryCollector](../../src/main/java/ltd/qianc/iotagent/iot/internal/EnergyHistoryCollector.java):39–78 | 无筛选分页；先验证／暂存该页，再在完整历史边界停止当前扫描；不是每次只取一条最新值 |
| [JdbcEnergyHistory](../../src/main/java/ltd/qianc/iotagent/iot/internal/JdbcEnergyHistory.java):44–53、338–373 | 当前和全量均写暂存并upsert，旧行即使值相同仍刷新时间；时间同时参与迟到发布保护 |
| 同文件:252–280 | 电表专用授权独立；成员和凭据不变时grant_version不增加，但授权成员表仍删除重插 |
| 同文件:289–314、376–399 | 相同电表集合复用覆盖和尝试等待；当前cutoff来自已完整覆盖边界，不可假设上游有可靠增量游标 |
| 同文件:80–128、215–248 | 一致本地窗口、左边界样本、来源版本选择、返回前重验授权、成功／失败新鲜度 |
| [LocalDataKeys](../../src/main/java/ltd/qianc/iotagent/iot/internal/LocalDataKeys.java):13–31 | 用户目录键含owner；当前事实按来源域和实际采集绑定；环境历史按相同授权物理测点集合 |

## 8. 独立风险与结论边界

- 前轮本地3307实例全库mysqldump已返回1812：旧预览schema的local_energy_samples表空间缺失。未重试确认、未修表或删库。它不是主业务3338实例损坏的证明。
- 封闭本地binlog已先无损归档并校验再PURGE；归档和gzip完整性不等于全库恢复成功。
- 当前业务查询数值错误、每分钟全历史重扫、持续重复净增、所有新账号重复采集，均没有本次证据支持，不列为已证实故障。
- 已确认：不变电表内容逐行刷新、目录重建写入、按来源scope保留重叠物理样本，以及写入量与日志保留带来的存储压力。最终处理须保存业务语义，而不是取消采样、缩历史或清事实。

## 9. 分领域补充核对

| 领域与源码 | 实际语义及优化约束 |
|---|---|
| [JdbcDeviceCatalog](../../src/main/java/ltd/qianc/iotagent/iot/internal/JdbcDeviceCatalog.java):80–103 | 完整展示目录按网关／租户共享；成功完整列表删除重插，失败保留旧值。可在原事务和进度锁下差量发布；成功空列表才可清空。完整目录不等于用户操作授权 |
| [JdbcDeviceObservations](../../src/main/java/ltd/qianc/iotagent/iot/internal/JdbcDeviceObservations.java):103–148、188–214 | 同值但来源采样时间更新属于新观测。事实行synchronized_at与设备进度success不一致时，会判为迟到／缺数并返回partial或stale；直接停写事实时间会改变结果 |
| [JdbcEnvironmentHistory](../../src/main/java/ltd/qianc/iotagent/iot/internal/JdbcEnvironmentHistory.java):37–46、226–276 | 每页暂存、全量或当前分别CAS、事务发布；逐行时间承担迟到保护；全量成功无条件重标该域历史请求READY。只能在完整性和覆盖证明保留后优化 |
| [JdbcSafetyHistory](../../src/main/java/ltd/qianc/iotagent/iot/internal/JdbcSafetyHistory.java):179–232 | 三类台账各自领取及提交，不是三类一起原子；同一类当前与全量共用attempt领取保护。事件正式表无条件upsert，成功／覆盖／publication_version另存进度表 |
| 同文件:263–299 | 原事件键含ledger、deviceId、eventId、occurredAt；事实共享限制在同来源域、同类、相同授权物理集合。不把alarm/report解释成恢复或处置 |

安全台账内容与当前目录的解释遵循[ADR0010](../../docs/adr/0010-safety-event-current-name-historical-playback.md)：改设备当前名称／位置不重写原告警；回放仍基于原事件时间与地点线索。

**新旧批次的关键反例：** 全量A先取得旧值90；当前B随后重新确认库内值100。若B因“值相同”连发布保护时间都不更新，A可能随后把100写回90。优化必须保留对B实际观察记录的保护；只用整台表最新水位又可能误拒绝A对其他历史记录的合法补数。此为从现有条件推导的实现风险，须用合成夹具验证，不声称现场已发生数据倒退。

**测试依据与缺口：** 现有CompanyDirectoryBehaviorTest、EnergyHistoryBehaviorTest、EnvironmentHistoryBehaviorTest、SafetyHistoryBehaviorTest及相应HTTP测试提供公开入口和来源夹具；多数本地行为夹具使用H2，不能证明MySQL binlog、空间回收或锁竞争。新增验收应测试消费者结果、授权、失败、并发和事实幂等；另用隔离MySQL量行事件字节，不用JDBC affected-row或COUNT_INSERT代替真实写量。

**待验证而非已证实故障：** 当前状态先读进度再读事实的发布一致性、完整目录核验后到事务提交前的撤权竞态，均仅有源码风险线索。将其列入变更测试，不据此宣称当前发生越权或错误读。
