# Spring Boot 项目适配契约

修改 Java、配置、事务、HTTP 或测试时，先读本文，再读对应 SKILL.md。仓库规范与既有消费者契约优先于上游示例；示例不是引入依赖或更换架构的授权。

## 技术与职责

- 以 pom.xml 为版本事实；本项目保持 Spring Boot 3.5.x、Java 21、Framework 6、Security 6、Jackson 2。升级主版本须单独授权；不采用 Boot 4 的 starter、Jackson 3 或 @ImportHttpServices。
- 保持业务模块包根入口和 internal 实现。Controller／应用服务／存储职责在模块内部划分；公开参数、返回值与构造器也不得泄漏 internal 类型。Spring 工厂装配是有效入口，不为每层增加转发接口。
- 延续 JDBC／MyBatis-Plus、WebClient、TransactionTemplate 和现有显式 Bean。技能不授权引入 JPA、Lombok、Spring Modulith 依赖、消息中间件、Testcontainers 或替换持久化技术。
- Java 拥有业务定义与 MySQL 事实／Pi Session，Node 只提供通用执行。Redis 只承载协调与可重建读取；普通读取及 cache miss 从后端已有数据构造，客户上游只由后台同步和真实动作出口访问。

## 配置与协议

- 配置、启动、存储及控制台修改先读 [配置治理](../../docs/configuration-governance.md)。分清程序默认、部署启动配置和 MySQL 业务修订；类型化绑定只收拢启动配置，不冻结动态业务修订。
- 使用原生外置 YAML／configtree 和常规 Java、Node、npm 命令；tmux 仅手动终端。运行和构建不依赖 Python、tools/、dotenv 或自定义启动清单。
- 保留显式基础设施开关与关闭态 fail-closed；只对实际启用的能力校验所需凭据。不要为恢复自动配置而让测试库、localhost Redis 或弱默认悄悄接管。
- 已有 *-ms 数值键的单位就是毫秒；保留已发布键及单位，新增 Duration 类型须验证绑定。收拢真实消费者、默认和校验，不能只改配置声明。
- 密钥／带凭据 URL 不写日志、前端、报告或配置对象的生成 toString；采用显式脱敏。Node 不读取 Java 数据库、管理员或加密安全根。
- HTTP 以 [现有契约](../../docs/api/java-contract.md) 和根 AGENTS 的 Runtime 对齐要求为准。保留路由、状态、分页、字符串大整数、NDJSON 与错误体 {code,message,recovery?}，沿用 error 模块，不改为 ProblemDetail、统一 ApiResponse 或自动 /api/v1 前缀。
- Map／JsonNode 的严格解析不等于缺少校验；RequestValues 对缺失、null、字符串、小数和溢出的区别必须保留。新增 DTO／@Valid 先验证 Jackson 标量转换与原契约等价。

## 事务与验证

- 编程式事务是正规 Spring 用法。保留 TaskAtomicTransactions 等入口及短事务；外部控制、授权和网络访问不因增加 @Transactional 而进入长事务。不同 manager 实例本身不证明事务分裂，需查 DataSource 与实际传播。
- 复用已有任务检查点、报告／通知尝试与 schema 入口，不凭示例增加 Saga、事件总线、第二套 outbox 或迁移器。AFTER_COMMIT 不是持久投递。
- 测试通过包根入口；HTTP 测试保留真实授权／Cookie／Origin／CSRF。复用进程内生产协议夹具，不因 slice 示例直接 import internal Controller 或监听客户网关端口。
- 永久测试验证消费者可见行为、边界、原子性与状态转移，不断言源码、固定措辞、mock 回声或仅仅未抛异常。MySQL／Redis 语义需真实隔离实例，条件跳过不得称为通过；H2 仅能证明其实际覆盖范围。
- 验证按根 AGENTS 使用 Maven 与现有工程契约；显著行为改变另做隔离实跑。不得以安装技能、编译、HTTP 健康或旧截图代替业务验收。

## 技能入口

| 任务 | 读取 |
|---|---|
| Java 实现／代码审查总入口 | [java-springboot](java-springboot/SKILL.md) |
| 成组启动配置、绑定、校验与秘密 | [configuration-properties](configuration-properties/SKILL.md) |
| Controller／应用服务／存储职责 | [layered-architecture](layered-architecture/SKILL.md) |
| 事务、回滚、提交与副作用 | [transactional-patterns](transactional-patterns/SKILL.md) |
| 测试层次、夹具与真实基础设施 | [testing-pyramid](testing-pyramid/SKILL.md) |
| HTTP 参数、DTO、分页与错误 | [rest-api-conventions](rest-api-conventions/SKILL.md) |
| 模块公开面与依赖验证 | [spring-modulith](spring-modulith/SKILL.md) |

## 来源与更新

六个专项技能来自 rrezartprebreza/spring-boot-skills 的 spring-boot-3 树，固定提交 4bcf9a165e13e5cbbac3415851d9c449b13a4660，以本仓库适配后的文件为使用版本；许可证见 [LICENSE.spring-boot-skills](LICENSE.spring-boot-skills)。既有 java-springboot 来源保持在仓根 skills-lock.json。

skills-lock.json 记录上游 ref 与安装哈希，不代表本地适配字节。provenance.json 记录本地适配文件哈希。更新需显式比较固定提交、合并适配并复核协议；不直接用 skills update／experimental_install 覆盖本地规则。安装不包含上游 Python 校验脚本，也不安装 Boot 4 插件。
