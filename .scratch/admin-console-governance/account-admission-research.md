# 手机号白名单：独立维护范围与历史纠正

日期：2026-11-04。最新结论：**白名单仅维护手机号和可选姓名备注，暂不接实际业务，也不用于控制谁能使用公司Agent；添加、移除与空名单不影响任务或设备。** 此前准入／取消绑定撤回，以[ADR0008](../../docs/adr/0008-standalone-phone-list.md)为准。其余控制台范围已获准实施，历史查询和撤回证据不作为名单功能验收。

## 本轮已完成的撤回

- 删除 `AgentIdentityFilter` 对公司请求的白名单检查和对应依赖；身份仍由 `AccountPrincipalVerifier` 核验。
- 删除 `ConversationController.create` 对请求体 `source=company` 的额外白名单检查；请求仍必须通过原有认证过滤器。未增加旁路开关、默认授权或自动加入白名单。
- 审查后进一步删除 `CompanyAgentAllowlist.requireCompanyAgent`、JDBC拒绝逻辑、无存储拒绝实现及旧准入测试；不保留可重新挂接的遗留准入API。管理CRUD的存储失败只影响管理读写，不再宣称公司业务不可用。
- 固定 Java `3381` 更新为冻结产物；Node `3382`、前端地址、客户网关和原有安全配置保持不变。
- 原控制台 CRUD、表结构与历史记录保留，**不再决定业务可用性**；旧三字段表单不是已批准的新设计，不得据其存在宣布白名单交付完成。
- 最终工程验证 444 项，失败／错误／跳过均为 0（删除一项旧准入测试；此前断开业务接入版本为445项）；真实小程序开发者工具 `9451` 的公司总览显示设备目录334台，公司与办公室会话读取均成功；无认证请求仍为 `401 UNAUTHORIZED`。本轮不创建任务、不控制设备、不发送生产通知。
- 运行证据：[撤回结果及截图](evidence/allowlist-withdrawal/result.json)。这只验收“撤回拦截后恢复访问”，不代表任务、控制或白名单业务已验收。

## 官方账号事实

本轮在已登录小程序中调用现有 `getPrincipalAllInfo()`，实际读取官方 `GET /sso-api/account/me`，只输出必要字段及相等判断，没有保存 Token、完整用户档案或原始用户 ID。

| 字段 | 本轮实读事实 | 用途 |
|---|---|---|
| `content.personalName` | 刘志龙；Unicode `5218 5fd7 9f99` | 官方展示姓名；不是唯一键 |
| `content.mobileNumber` | 与用户提供的测试账号一致；脱敏为 `172****1949` | 本例可以帮助识别成员；不能据单个账号断言所有登录名都是手机号 |
| `content.id` | 非空32位字符串，与手机号不同 | 原有用户/会话归属标识；不因白名单需要管理员预先查询或手填 |
| `content.userPrincipalName` | 本例为空 | 不能假定它能提供通用登录账号 |
| `identityRealm` | 官方响应没有此字段；本地从配置网关地址派生为 `https://ncim.577119.com` | 内部身份来源标识，不是管理员输入项 |
| `tenantCode` | 当前请求为 `XSJ`；来自小程序所选空间的请求头 | 原有网关路由／权限上下文；不因界面去掉园区字段就删除该校验 |

源码来源：[`apis/user/principal.js`](../../../mp/apis/user/principal.js) 的 `getPrincipalAllInfo`；[`store/modules/user.js`](../../../mp/store/modules/user.js) 的登录和空间选择；[`WebClientAccountPrincipalVerifier`](../../src/main/java/ltd/qianc/iotagent/auth/internal/WebClientAccountPrincipalVerifier.java) 的 `/sso-api/account/me`、`content.id` 和 realm 派生；[`PrincipalInfo`](../../src/main/java/ltd/qianc/iotagent/auth/PrincipalInfo.java) 的稳定 ownerKey；[`WebClientIotAdapter`](../../src/main/java/ltd/qianc/iotagent/iot/internal/WebClientIotAdapter.java) 的官方 `personalName` 读取。当前行为优先于早期“手机号字段未核验”的研究记录。

## 截图中的技术字段

- `XSJ` 是本例实际空间代码，不是业务上必须展示的输入框。
- `https://ncim.example.com/` 在源码中是测试／占位示例，不是官方账号返回值，也不是当前网关配置。已核对的 SQL 只有建表，没有相应种子记录；不能仅凭截图断言某条记录由程序预填或已存在于哪个数据库。
- 旧表单曾直接收集tenant、identity realm、principal三元组并写入旧CompanyAgent名单；这是已撤回的旧设计，不应要求管理员理解或复制这些技术值。旧数据只作历史保留，不猜测或伪造手机号转换。

## 当前范围：维护名单，而不是认证用户

1. 管理员直接录入手机号与可选姓名备注；不等于创建官方账号，也不声称验证账号存在，不要求目标用户先登录。
2. 名单是独立管理数据，本轮不读取业务Token、查询当前用户或执行准入匹配；原有登录、身份核验及设备权限保持不变。
3. 姓名仅为名单备注，不参与手机号记录去重，也不能冒充官方姓名。
4. 没有登录档案或业务轨迹也可预先登记；名单不作为审计成员索引的来源或前置。
5. 园区、身份域、principalId与ownerKey仍归原有业务／审计上下文，不作为名单录入要求。
6. 添加、移除、空名单和名单存储错误均不得改变公司／办公室Agent业务可用性或任何任务、周期计划、设备执行。
7. 管理端仍使用独立部署管理员登录，名单维护不能绕过管理会话与CSRF。

### 源码核对：已有调用与字段传递

历史核对曾确认官方`/sso-api/account/me`存在手机号，而Java身份对象仅保留原有认证归属字段。本轮不需要修改身份对象、传递手机号或增加任何匹配／查询调用；名单只在管理端读写。

### 当前决定与已撤回解释

- 保留：直接维护手机号与可选备注，不查官方目录、不先登录。
- 撤回：将名单解释为公司Agent使用资格，以及移除后取消任务／周期计划后续执行。
- 当前：添加、移除和重新添加只改变名单记录；不改变业务、设备或任务状态。
- 未来用途没有在本轮定义，不能把旧准入或取消解释带回实现。
- 权威记录：[ADR0008](../../docs/adr/0008-standalone-phone-list.md)；[ADR0007](../../docs/adr/0007-phone-allowlist-and-task-cancellation.md)仅保留为已撤回历史。

## 已撤销的错误前置

助手此前把“不要求目标用户先登录”错误解读为“必须接入SSO任意账号检索、先查询内部ID才能添加名单”，并将查询接口与只读调用资格列为阻塞。用户已明确：目的只是名单信息匹配，不是认证。这些前置现全部撤销，不再要求客户补接口或用户管理入口；不存在“先登录后添加”这一必要步骤。

下方官方目录调查保留为历史证据，不代表当前需求，也不构成白名单实施的前置条件。官方目录搜索将来若单独提出，只能作为管理员录入的辅助功能，不能反过来扩大此票范围。

## 历史目录实测：不作为当前白名单前置

公开定义：[服务目录](https://ncim.577119.com/micro-api/swagger-resources)、[api-card Swagger](https://ncim.577119.com/micro-api/api/card/v2/api-docs)。一卡通确有 `GET /account/page`、`GET /account/list`，参数包含accountNumber、name、fulltext、tenantCode等；返回卡账户id、name、mobile及财务／组织字段。这里只选择现有测试账号做窄查询，不枚举其他用户。

| 只读调用 | 实测结果 | 可以得出的结论 |
|---|---|---|
| `/micro-api/api/card/account/page?accountNumber=<测试登录账号>&page=0&size=5` | HTTP200，业务码204，0条 | accountNumber检索不能定位该已存在的SSO登录账号 |
| 同一分页接口，`fulltext=<测试登录账号>` | HTTP200，业务码204，0条 | 全文检索也不能定位该账号 |
| 同一分页接口，`name=<测试官方姓名>` | HTTP200，业务码200，1条，但id不是当前SSO用户id | 不能凭姓名把该记录绑定给测试账号 |
| `/micro-api/api/card/account/{当前SSO用户id}` | HTTP200，业务码200，同id，但name为null，accountNumber和mobile均不等于测试登录账号 | 通过相同id访问卡账户不能证明其包含完整SSO账号资料 |

`apis/home/index.js`的fetchCardPrincipalInfo和`store/modules/user.js`确实把SSO id传给卡账户接口，但这只是调用方式；上述真实结果推翻“因此一卡通可以充当任意官方账号目录”的推断。普通成员对这些查询收到200，也不证明本地控制台管理员具有SSO全量目录权限。

桌面门户的真实入口是[portal桌面脚本](https://ncim.577119.com/portal/js/index-0a8dd1d4.js)，应用目录调用来源是[platform脚本](https://ncim.577119.com/portal/js/index-c2ba666c.js)。静态读取器的UA可能取得移动端HTML；已通过真实Chromium读取桌面脚本，确认只读 `GET /sso-api/client/my/list` 和 `GET /sso-api/resource/current/user/resource/v2?resourceCode=...`。当前账号应用目录中包含“统一身份认证中心”，但没有提供该应用的loginUrl；没有调用含Token路径的OAuth登录跳转或提权。

公开SSO定义入口 `/sso-api/swagger-resources` 和 `/sso-api/v2/api-docs` 实测均404。已读取的其他业务定义只提供乡村运营人员、办公室成员、考勤分组或既有accountId的角色绑定，不能据此构造全量登录账号目录。

历史研究曾缺少任意账号查询契约与只读权限，但这**不是当前白名单的缺项或阻塞**，不再向客户索要这些资料。历史结果仅说明不能把一卡通记录或同名记录冒充官方登录账号信息。

脱敏记录：[官方账号查询实测](evidence/official-account-lookup.json)。本轮只发起查询请求，没有启用白名单或更改生产任务／设备／通知。
