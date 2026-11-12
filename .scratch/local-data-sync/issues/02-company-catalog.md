# 02: 公司目录与主体授权本地同步及本地读取（首个可运行同步骨架）

**What to build:** 企业（company）身份的公司设备目录与主体授权按完整批次同步入本地 MySQL；company-devices 的普通 HTTP 与 Agent 读取改走本地库，Redis 只作可重建缓存（miss 从 MySQL 重建，重建后仍校验当前身份权限），Redis 清空不影响读取。本票同时交付首个可运行同步骨架（承载真实公司目录数据，不是通用空框架）：同步范围/进度/覆盖持久化、同范围 singleflight、错过调度的恢复补跑、批写成功才推进进度，03-07 直接复用。MySQL 不可用时返回明确 UNAVAILABLE 错误，任何读路径都不得查询回源兜底。

**Blocked by:** [01-sync-contract.md](01-sync-contract.md)（G1—G4未闭不得开工；Status表示待实施，不表示依赖已完成）

**Status:** in-progress（工程目录闭环已实跑；真实小程序UI未验收）
**Type:** implementation
**契约参照:** [../spec.md](../spec.md) · [../acceptance.md](../acceptance.md)

## 范围
- schema：公司目录表、主体授权表、同步范围/进度/覆盖表，经既有 SchemaMigrations 唯一迁移入口登记；隔离域=tenant_code+来源实例+source+对象ID；bigint unsigned id、create_time/update_time、数值用 decimal、禁外键；结构化列为主，扩展才 JSON；token 不作永久对象ID。
- 同步：经既有上游出口 IotAdapter.loadAuthorizedDeviceScope 拉取完整批次；整批幂等写成功后短事务发布新版本，失败保留上次完整版本；目录指纹口径与既有 catalog_fingerprint 一致可对比；授权视图差异未证实前各主体独立存正文，不跨主体共用。
- 骨架：范围登记与暂停（G1 口径）、进度/覆盖持久、同范围 singleflight、启动与错过窗口恢复补跑（服务重启后按持久进度续跑，不重复已完成批次副作用）、受限并发沿用现有上限；调度为进程内周期任务，默认节奏与暂停条件按 G1 结论登记，不另造队列/框架；采样时间/成功同步时间/尝试时间三列分开，刷新不伪造采样新鲜度。
- 读取：本地目录读取入口从 iot 包根公开，其他模块调用者不得 import iot.internal；ReadSnapshots 缓存语义保留，miss 落 MySQL。
- 缓存与重建：Redis 命中仍按 ReadSnapshots 既有 barrier/恢复检查点语义防串读；本地重建不引入第二套缓存实现。
- 运维可见性：同步范围/进度/覆盖提供只读查询（供12号票运维证据复用），不暴露凭据正文。
- UI：企业侧目录列表渲染本地数据；没有已完成覆盖时按既有准备/不可用协议展示，只有完整成功且确实无成员才为空目录，部分数据不能渲染成完整目录。

## 非目标
- 不做 Office 区域/候选（03）、状态（04）、能耗（05）、环境（06）、安全（07）数据面；跨领域恢复综合验收归12，全局出口检查归13；本票自身旧回源loader及失去用途接线随切换删除，不留兜底。

## 行为验收
- [x] 迁移经 SchemaMigrations 登记且重复执行安全；无外键；对象唯一键符合G2，主体授权视图不会因共享来源对象ID被合并泄漏。
- [x] 同步成功后 company-devices 与 Agent 同域读取不再触发上游调用（契约测试及隔离jar/HTTP实跑）。
- [x] 读取投影为空且Redis禁用时仍返回完整目录（从MySQL重建），重建结果按当前身份重新核验权限；真实Redis清空场景仍归最终综合验收。
- [x] 跨租户/跨主体读取不命中他主体目录正文（隔离测试）。
- [ ] 批写中途失败：进度/覆盖不推进，读取仍见上次完整版本；并发同范围仅执行一次同步。
- [x] 服务重启后按持久范围/进度恢复补跑（定向行为回归）；实际jar重启后读取已发布目录无新增目录来源请求。
- [x] MySQL 不可用：返回 UNAVAILABLE 明确错误，无回源兜底、无旧数据伪装新鲜。
- [x] 上游分页与页号漂移按 G2 核实口径处理，不凭空承诺增量游标。
- [ ] 企业目录页开发者工具 UI 截图：正常态、空态、错误态均可复现。
- [ ] 目录版本发布原子：读取只见完整旧版或完整新版，不出现撕裂半批。
- [ ] 普通同步暂时失败时，仅在身份及授权仍有效且符合G4时展示有时间标注的旧值；凭据过期或明确401/403按G1/G4拒绝并暂停，不以旧目录继续授权。
- [x] Agent 工具与 HTTP 同一本地入口实现，不出现双实现分叉。
- [x] 迁移注释登记 G2 核实的来源字段、唯一键及实际需要的数值精度；运行隔离程序证明持久读取，不只跑契约测试。真实UI抽样按总验收图，不写生产控制/任务/通知/配置。

## 证据与既有公开接口
- 公开符号：IotAdapter.loadAuthorizedDeviceScope、AuthorizedDeviceScope、AuthorizedDevice、IotSourceEnum、RequestIdentity、ReadSnapshots（read/load/invalidate/revoke）。
- 既有测试接口：IotAdapterContractTest、AgentReadIotAdapterContractTest、IotAdapterConcurrencyContractTest；新增测试沿用既有框架、经包根入口断言，不从 internal 导入，不新增默认失败掩盖。

## 本轮工程证据（2026-11-06）

- 定向Java：`CompanyDirectoryBehaviorTest,IotAdapterContractTest,AgentReadIotAdapterContractTest,IotAdapterConcurrencyContractTest,ArchitectureTest`通过。完整服务端设备字段及catalogFingerprint可往返；HTTP仅投影公开字段；撤权/重新准入代次不可复用旧授权；坏密文可经已核验凭据重新登记。
- 真实MySQL隔离库程序：基础表已有情况下重复执行新增迁移、冷读不采集、后台HTTP收集、重建目录实例/清空投影/关闭来源后读取、Agent observationOnly保留、密文与无外键检查均通过。单设备15.18ms仅为工程夹具读耗时。
- 实际冻结jar＋独立YAML、34781端口和独立MySQL：无身份401；首次目录为preparing且没有devices空数组；后台通过8个真实生产协议形状的localhost目录来源请求发布一批；停止再启动jar且Redis禁用后HTTP读同目录，没有额外目录来源请求；关闭来源后仍可本地读；同步元信息不含凭据。身份核验来源请求单列，未混计为业务数据采集。
- 小程序补充目录同步时间/待更新标注；上海跨日及缺失/非法时间脚本通过，`agent-company-overview.test.mjs` 4项通过；隔离目录uniapp编译完成。构建保留既有Vue default导入及平台兼容告警，未把编译当真实微信验收。
- 工程烟测进程均已停止；固定3381/3382、控制台、真实数据库和前端地址未切换。工具托管启动的环境表现与直接子进程不同，托管两次均未启动成功；实际jar工程证据来自显式argv的有限生命周期烟测，不宣称该托管环境问题已定位。
