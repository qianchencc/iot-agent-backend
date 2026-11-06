# Spring Boot 3 技能安装与后端审查

2026-11-06。第一阶段为技能安装与只读审查；用户随后授权按 Spring Boot 规范完成改进。以下“原始审查”保留当时证据，当前交付状态以本节为准。保持 Boot **3.5.16**、Java 21 编译目标，不升级 Boot 4。

## 改进交付（2026-11-06）

| 项目 | 已落地变化 | 验证 |
|---|---|---|
| R1 HTTP 错误 | 保留框架状态与相关响应头，406 不强写无法协商的 JSON；未知异常仍脱敏500 | 修前405/415回归失败；修后真实HTTP为405+Allow、415、406 |
| R2/R4 存储配置 | Boot 类型化配置装配 Hikari；Lettuce 消费 ACL、编码密码、库号、TLS、命令／连接超时 | 真实MySQL连接池限额2；TLS-only且禁用default用户的Redis完成读写／幂等验证 |
| R3 旧库升级 | 共享事实初始化在列定义收敛前幂等补齐任务阶段列 | MySQL 8.4旧表修前报缺definition_json；修后保留payload／状态并重复升级通过 |
| R5 启动种子 | 惰性 Supplier 只在无修订时求值；严格解析整数／布尔；读取／解密失败不重新播种 | 文件与真实MySQL修订权威回归；非法首次种子拒绝，已有修订忽略未使用的非法种子 |
| R6 管理端职责 | 远端读取／采用策略移至内部 AdminObservabilityStatus；只接受Boolean报告 | 空对象、null、字符串报告修前误报已采用，修后保持未知且未采用 |
| R7 模块边界 | TaskFacade仅暴露Duration而非内部normalizer；完整依赖、公开泛型签名、全部测试产物门禁 | 新门禁修前发现3个公开签名泄漏；修后ArchUnit及任务契约通过 |
| R8 真实停机新发现 | 容器关闭事件先停止扫描续投，再沿既有stopAndDrain排空；保留幂等销毁 | 实际Spring调度器回归修前拒绝续投；修后真实关闭／重启无TaskRejectedException |

未机械引入 Service／DTO／Repository 层；保留现有 TransactionTemplate、TaskAtomicTransactions、严格输入协议、模块包根入口与 SQL 迁移入口。没有新增框架、运行脚本或 Python 依赖。

### 最终验证与边界

- 干净 HEAD `652aa17afa9d09ec4cdd665933d01268bc663637` 加本轮自有代码差异，在独立目录执行 `mvn -o package`：**657项，0失败、0错误、23跳过**，打包成功；跳过不计通过。原混合工作树与交付候选分开验证，没有覆盖根 `target/`。
- 真实 MySQL 8.4、Redis 7.4 TLS+ACL，以及生产 Spring Boot HTTP、配置修订重开、连接池限额和正常关闭均隔离实跑。最终 jar 通过常规 `java -jar … --spring.config.additional-location=file:…` 启动，HTTP 200/405/415/406均核验。见 [改进证据](improvement-evidence.json)。
- 两个只读终审覆盖配置／存储／迁移及HTTP／模块边界；停机改动另经只读复审，均未提出新增可操作缺陷。
- 并发工作树在本轮开始快照中已有3条Agent业务断言失败，以及1条MySQL报告测试因新目录配置缺失无法建上下文；独立复现一致，未修改、收进本轮提交或声称混合工作树全绿。
- Maven全量测试有Surefire fork退出等待30秒后强制结束的诊断，虽然测试计数和构建均成功；本轮没有将其伪称为正常测试JVM退出。生产进程的扫描续投关闭异常已单独复现并修复，两者不混为一个问题。
- 固定3381/3382/5185实例、真实业务库、生产Runtime、设备控制和通知未触碰；本轮是源码／隔离工程交付，不是固定实例升级或真实微信业务验收。

## 原始审查（修复前留档）

## 安装交付

- 六个专项：configuration-properties、layered-architecture、transactional-patterns、testing-pyramid、rest-api-conventions、spring-modulith。
- 来源：[rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills/tree/4bcf9a165e13e5cbbac3415851d9c449b13a4660/skills/spring-boot-3)，固定提交 `4bcf9a165e13e5cbbac3415851d9c449b13a4660`，仅 Boot 3 树，仓库级 copy 安装。
- 既有 java-springboot 总入口已限定 Boot 3，并纠正强制 ProblemDetail、每次文档修改都构建应用等不适用规则。
- [项目适配契约](../../.agents/skills/PROJECT.md) 是共享约束，根 AGENTS 提供强制读取入口。保留深模块、JDBC/MyBatis、TransactionTemplate、现有 HTTP/NDJSON、外置 YAML/configtree 与 MySQL 业务配置权威。
- 已移除本次导入但不适用的 layering/REST/testing 示例模板，避免 JPA/Lombok、统一响应包装、直接 internal Controller 测试及完整异常日志模板被复制。其他保留示例明确受项目契约约束；不会因此引入 Modulith、消息框架或 JPA。
- [skills-lock.json](../../skills-lock.json) 保留上游 ref/安装哈希；[provenance.json](../../.agents/skills/provenance.json) 记录本地适配哈希。两份 MIT 许可保留；直接 update/restore 上游会覆盖适配，更新须显式比较并合并。
- 未导入 Python／shell／JS 校验脚本，未创建启动器；未修改 pom、业务源码或实际部署配置。

## 审查范围与证据

审查当前工作树，记录起点 HEAD `652aa17afa9d09ec4cdd665933d01268bc663637`；工作树已有并发未提交业务改动，审查结论不是纯提交差异审查，也不代表这些改动已交付。

主代理完成安装、裁剪与运行观察；两个只读子代理分别按已安装的配置/事务技能和模块/HTTP/测试技能核查源码。重点覆盖基础设施绑定、首次配置修订、错误 advice、task 公开面、架构规则、schema 旧库升级及 admin 采用状态。

三个临时 Java 程序使用 `javac --release 21` 重编译当前被审类，在独立临时目录运行；配置对象仅构造、不连接数据库或 Redis，HTTP 使用进程内 MockMvc 和合成路由。没有启动 Spring Boot 应用、调用客户上游或写业务数据。被审源码 SHA-256 与实测输出见 [evidence.json](evidence.json)。临时程序与编译输出已清理，不新增永久测试或运行依赖。

## 发现：功能与数据风险

### R1 / 高：405、415 被通用异常处理改成 500（已实跑）

证据：`src/main/java/ltd/qianc/iotagent/error/internal/BusinessErrorAdvice.java:32-56`。

当前 advice 处理指定绑定错误和未知路由，其余异常进入 `@ExceptionHandler(Exception.class)`。当前 advice + 合成 POST JSON 路由实跑：

| 场景 | 应保留的框架状态 | 实测 |
|---|---|---|
| 不支持的 HTTP 方法 | 405 + Allow | 500，Allow 为空 |
| 不支持的请求媒体类型 | 415 | 500 |
| 不接受的响应媒体类型 | 406 | 最终仍为406；advice 写响应再次失败，由默认 resolver 接管 |

影响：调用方错误被误记为后端故障，方法发现响应头丢失。不能概括为“所有4xx都变500”。本轮没有实跑管理端完整安全链，不能把合成路由证明说成线上接口故障复现。

建议：在现有 error 模块内保留框架状态/相关头，继续输出项目错误契约；补消费者可见的405/415回归，不引入第二套 advice 或 ApiResponse。

### R2 / 高：Redis URL 和超时没有完整生效（对象配置已实跑）

证据：`storage/internal/StorageConfiguration.java:59-69`；启动类排除了 Redis 自动配置，不能依靠其补齐手工工厂配置。

合成输入 `rediss://synthetic_user:synthetic-password@127.0.0.1:1/4`、`spring.data.redis.timeout=9s`，当前工厂对象输出：username 缺失、SSL=false、command timeout=2s。无实际握手。

影响：[INFERENCE] TLS-only／非 default ACL Redis 连接可能失败；配置 rediss 不能证明实际加密，超时配置不能按预期控制调用。当前本地实例是否使用这些模式未读取、未判断。

建议：保留显式开关及 fail-closed，绑定并验证 scheme、ACL、database、受支持的超时；避免在错误中暴露带凭据 URL。普通本地 redis:// 模式不据此判为故障。

### R3 / 高风险静态发现：旧 task_phases 补列晚于 MODIFY COLUMN

证据：`schema/internal/JdbcSchemaMigrations.java:60-84,98-101,304-317,425-436,665-679`；`task/internal/TaskConfiguration.java:71-75`；`schema/SchemaMigrationsUpgradeBehaviorTest.java:286-290` 的旧表夹具确实缺少对应列。

执行顺序为：共享 facts 的 CREATE IF NOT EXISTS → shared-facts-column-comments-v1 polish → 后续 task lifecycle 补 definition_json/result_json/failure_reason。polish 在 MySQL 上对资源全部带注释列生成 MODIFY COLUMN，不只是修改表注释。

触发条件：旧表缺少这些列、且尚未登记该 polish 迁移。[INFERENCE] MySQL 会因 MODIFY 不存在的列而拒绝初始化，后续补列无法执行。列已齐全的新库/旧库不受此具体路径影响。

建议：先幂等补齐/回填，再进行完整列定义收敛；保持同一 schema 迁移入口。本轮未启动真实 MySQL 旧库演练；H2 会跳过 polish，已有 H2 用例不能证明该路径安全。不以此项授权直接改在线库或引入 Flyway。

### R4 / 中：Hikari 原生参数未绑定（已实跑）

证据：`storage/internal/StorageConfiguration.java:37-43`。

输入 `spring.datasource.hikari.maximum-pool-size=3`，经当前工厂创建并 validate 的 HikariDataSource 实际 maximumPoolSize 为10。仅 validate，不申请连接。

影响：部署以为设置了池容量，实际继续使用池默认值；没有证据声称当前已发生连接耗尽。

建议：在受控数据源装配中复用 Boot/Hikari 的类型化属性绑定，并验证实际消费者；不直接删除全部自动配置排除。

### R5 / 中：首次启动显式非法种子配置被悄悄替换（解析器已实跑）

证据：`admin/internal/AdminModuleConfiguration.java:134-170`；`admin/internal/JdbcAdminConfigurationStore.java:171-185`。

实跑 initial(Environment)：scan-interval-ms 填非整数文本后变成1000，reasoning 填 `tru` 后变成false，没有解析失败。未创建任何数据库修订。

[INFERENCE] 空配置库首次初始化可能把这些替代值写为第1修订，掩盖部署错误；已有修订的重启不会因此覆盖历史配置。管理 API 的严格整数校验与此入口不同，不能说全局都未校验。

建议：仅缺省采用默认；显式格式错误、溢出或非法布尔值按键名报错并脱敏，保留 *-ms 毫秒协议。

### R6 / 中：不完整观测报告可能被当作“已采用”（静态边界发现）

证据：`admin/internal/AdminController.java:233-258`；`agent-sidecar/src/app.ts:46-47,64-69`；正式报告入口 `agent-sidecar/src/server.ts:22-27`。

`Boolean.TRUE.equals(reported.get("enabled"))` 将缺字段、null、错误类型统一变为false。保存配置也关闭时，`observability:{}` 可被判 adopted=true。

影响：[INFERENCE] 报告不完整时管理端展示了没有充分证据的采用状态。正式 server 当前会提供布尔 enabled，因此这是缺失/兼容性边界，不能称当前线上观测配置已误报。

建议：只接受 Boolean 启停报告；不完整时保持 reachable=true、enabled=null、adopted=false。客户端装配和采用策略收回 admin 内部应用服务，Controller 仅保留传输映射。未实跑此边界。

## 发现：架构规范

### R7 / 中：TaskFacade 公开构造器泄漏内部类型

证据：`task/TaskFacade.java:75-95` 的三个 public 构造器接受 `task.internal.TaskProposalNormalizer`；`ArchitectureTest.java:46-57,73-89` 检查成员访问且测试类仅按 Test 后缀筛选，没有单独限制公开签名的 internal 类型。

影响：内部实现类型进入模块公开契约；外部若显式提供该参数就要跨越约定入口。不是“大类看起来不美观”的风格判断。

建议：装配留在模块内部，通过稳定包根工厂提供构造入口；扩展既有 ArchUnit，检查完整类型依赖、公开构造/方法/泛型签名及测试辅助类。无需先引入 Modulith 依赖。

另有局部职责混合：AdminController 自行构造 WebClient、读取远端健康、计算配置采用；部分 observation controller 决定快照完整性策略。适合局部收薄，但不为每个现有 facade/store 调用机械增加 Service。已存在的薄 controller 保持不动。

## 未误判为缺陷的部分

- TransactionTemplate、TaskAtomicTransactions 与同 DataSource 的多个 manager 实例，不等于缺少事务或事务分裂；当前未发现必须依赖声明式事务 manager Bean 的业务消费者。
- 深模块包根/internal 是保留目标，不改为全局 controller/service/repository 包。
- Map/JsonNode + RequestValues 有意保持严格数值协议，DTO 化须先证明语义等价。
- Security 配置中的 permitAll 不代表管理端裸奔；独立 AdminBoundaryFilter 仍校验 Origin/Cookie/CSRF，业务端另有身份核验链。
- schema 已具备补列、回填、幂等登记、数据保护；R3 是具体次序风险，不是“项目没有数据库迁移”。

## 建议顺序

1. 先修 R1 错误状态与 R2/R4 基础设施配置真实消费；R3 在下一次旧库升级前完成独立 MySQL 复现及修复。
2. 收拢启动配置校验并修 R5，继续保持动态 MySQL 业务配置与启动绑定分离。
3. 修 R7 公开接口/ArchUnit，局部收薄 admin；同时补 R6 不完整报告边界。
4. Flyway 继续作为单独迁移治理决策，不随技能安装引入。

## 验证与限制

- `npx skills list` 实际发现7个项目技能；六个新增锁定 Boot 3 同一提交。
- 七份 frontmatter、六份 agent metadata、共享契约指针、所有技能本地 Markdown 链接校验通过；无缺失链接，无 Python/shell/JS运行脚本。
- 当前会话 `skill://configuration-properties` 返回 Unknown skill：短名注册未热加载。当前审查已通过显式 `.agents/skills/.../SKILL.md` 路径应用；未声称自动触发通过。刷新/新会话后仍需确认短名发现。
- 项目契约的 `../../docs/...` 链接已按文件所在目录实际解析，目标存在；子代理提出应多退一级的建议是误报，未采纳。
- 本轮没有业务代码变更，因此未运行全量 Maven 测试；3个临时程序仅证明所列机制。没有真实微信、生产部署、真实设备动作或旧库升级验收。
- 安装与只读审查阶段未提交或推送，问题当时尚未修复；这是历史状态，不覆盖文首后续改进交付结果。
