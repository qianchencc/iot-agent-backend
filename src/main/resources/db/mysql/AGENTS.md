# MySQL 结构与迁移

建表、修改 JSON 事实结构、数据迁移和恢复前读根 `docs/configuration-governance.md` 第5节，以及 `.scratch/admin-console-governance/database-inventory.md`。业务事实与 Pi Session 的权威在 Java/MySQL；Redis不是替代库。

- 同一结构的 SQL 资源、Java 初始化 DDL、兼容升级和实际旧库必须对应；`CREATE IF NOT EXISTS` 不承担已有列／索引迁移。迁移登记版本和影响，避免每次启动重复业务副作用。
- 遵守根建表规则：bigint unsigned id、create_time/update_time、decimal、无外键级联、适用时 null 逻辑删除与联合唯一键。既有自然／复合主键表的差异先记录，未经迁移方案不改在线结构。
- 业务唯一、幂等与 CAS 是数据库约束，不以应用内先查后写代替；冲突不覆盖审计原证据。JSON 中的 tenant／owner／source 必须在写入及关联读取时同样核验。
- 配置修订与任务凭据密文不回显；Pi 原生日志、用户正文和报告未获证明是应用加密数据，按敏感业务内容保护，不做任意表浏览器。
- 到期、六小时活跃窗口、逻辑删除和用户清会话不是审计保留期。保留／清理策略未定前不增加无界自动删除。
- 本地核查只读元数据及聚合数量，不导出正文、Token、密文或凭据哈希。全库一致备份包含业务、配置、Pi四表和 storage_migrations；恢复先入独立库，不覆盖运行实例。
- 本地目录迁移走 `SchemaMigrations.ensureLocalCompanyDirectory()`；`local_sync_scopes` 是同步范围/凭据代次/完整批次权威，不以Redis条目存活代表已登记。新增领域先读 `.scratch/local-data-sync/issues/02-company-catalog.md`，复用完整批次发布与主体隔离约束。
- Office区域增量迁移为 `local-office-directory-v1`，经同一目录初始化入口执行；`local_office_regions` 保留无设备区域。设备与成员关系复用既有目录表，按source进入不同scope_key；区域、关系、授权与版本必须同事务发布，不分别采集后拼接，不从设备反推完整区域。
- 最新状态经`local-device-states-v1`建表、`local-shared-observations-v2`迁移：`local_device_states.scope_key`为网关／租户／来源共享事实域，device_key包含实际采集绑定。`local_device_state_sync`同时保存逐设备采集进度与主体DISPATCHED调度游标；后者last_success_at必须为null，不能作新鲜度。事实与设备进度同事务发布，失败不推进；旧采样不覆盖新采样，无时间不替代已知时间。凭据轮换先重建本人授权，不能强制重采仍有效的物理事实。
- 能耗迁移`local-energy-history-v1`经同一目录初始化入口登记六表：独立专用授权/当前及全量进度、原始样本、不可见批次暂存、完整流覆盖和持久补缺请求。样本键包含来源ID或显式命名空间的缺ID回退、计量点与原始时间；同秒不同来源记录不合并，质量字段不进入身份。DECIMAL(65,30)超范围或超精度失败，不静默舍入。
- 历史页先暂存，所有页与完整性检查成功后，同事务upsert事实及推进覆盖；失败不发布半份。较早启动的全量扫描不能覆盖其后当前采集已发布的值；MySQL INSERT SELECT upsert的旧值引用须限定目标表，不能只依赖H2兼容。首次及上海03:00全量无日期/24个月/总运行时间截断；不删除既有原始历史。
- 环境迁移`local-environment-history-v1`同入口登记四表：独立当前/全量进度、原始样本、私有暂存、持久请求。样本身份包含来源ID/显式缺ID命名空间、原始采样时间与测点代码；NULL代码不等于字面site。来源pm101对应历史PM10，pm10对应历史PM1；不混淆当前影子字段与历史指标。真实MySQL及断源重启证据见`.scratch/local-data-sync/issues/06-environment-history.md`。
- `local-shared-observations-v2`在同一事务内登记并合并旧当前观测、环境历史／进度／请求；仅迁移可证明当前绑定的旧观测，无法证明的原行保留但不可作为新绑定事实。环境历史scope_key为相同授权物理测点集合，事实代次仅记采集来源。共享事实不随采集账号清理；电表既有共享历史不改写。回滚须停服配对恢复受影响表和迁移登记，再启动旧包，禁止仅换旧二进制。详见ADR0009。
