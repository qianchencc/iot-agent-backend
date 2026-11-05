# 控制台配置、独立名单与用户审计

2026-11-03盘点为历史快照。**2026-11-04最新指令：白名单仅独立维护手机号与备注，不接实际业务，也不管谁能使用公司Agent；此前准入与移除取消任务解释撤回。其余范围获准实施。** 当前实现进度以票据与真实验证为准；见[ADR0008](../../docs/adr/0008-standalone-phone-list.md)。

## 当前交付（2026-11-05）

- 三项能力及独立管理员登录已实现；前端为纯 JavaScript / Element Plus，白名单没有业务读者。配置保存／冲突／跨页草稿／历史恢复、模型草稿连接检查、观测启动采用均已走实际页面。
- 隔离预览：前端 `3490`、Java `3491`、Node `3492`、只读上游代理 `3495`。部署文件为 Git 忽略的 `var/admin-console-20261104-230158/launch.yaml`（0600）；使用独立 MySQL schema。主开发 `3381/3382` 和生产配置未切换。
- 同一真实账号在微信开发者工具完成办公室照明查询（7台关闭）与公司设备统计（334台），管理端同一成员下出现两来源请求／完成记录，可读公开答复。Node采用观测修订6，本地接收端收到实际span；随后关闭诊断观测、保存修订7并重启确认关闭。重启后同一公司对话仍能回答334台，业务审计保留公司4条／办公室2条记录。这不是客户Langfuse部署验收。
- Java 聚焦10组共62项通过；Node类型检查、34项测试与构建通过，前端构建通过。全套551项运行中549项通过、1项跳过、1项动作草案用例报错：`ActionDraftProposalContractTest.rootAndLightingExclusionsMergeByDeviceIdentityInsteadOfReferenceSpelling`；未以排除该用例冒充全绿，也未改动该并行业务。
- 截图、精确命令、审查修复及未验证边界见[收口票据](issues/05-console-acceptance.md)和[本轮结果](evidence/CONSOLE-20261104/result.json)。真实设备执行、生产任务／通知和原生浏览器200%缩放未验收；已查320px及200%等效视口，不把工程测试冒充真实执行。
- no-`.env` 本机切换（2026-11-05）：根 `.env` 的38项配置已分类、私密归档并删除，`.env.example` 已由 `fixed-backend.example.yaml`／`local-tools.example.yaml` 取代。必要基础设施及探针配置在 `var/local-tools/launch.yaml`（0600），固定后端 YAML 与现有 MySQL 修订不被旧值覆盖。`tools/run-local.py` 仅执行选定条目及其显式环境；Compose 禁用隐式 `.env` 并移除密码弱默认。15项工具回归、两套实际 Compose 解析、隔离探针与固定启动器无文件重入通过；当前业务配置修订9及密文、Pi记录、MySQL／Redis容器和卷保持。见[清理与运行证据](../root-dotenv-retirement/evidence/result.json)。2026-11-03盘点及历史命令保留原貌，不冒充当前入口，也不声称其他工作区已切换。


## 用户已确定

- A「成员分栏」，按用户分层，公司和办公室轨迹均纳入。
- Vue3 / JavaScript / Vite / Element Plus；留在本仓库`web/admin/`，保留已有JavaScript迁移成果，不引入另一套TypeScript实现。
- 单独的一个部署管理员身份；不需要多管理员系统，凭据不经页面修改。
- 可管理参数尽量纳入控制台，支持保存及真实应用；启动／安全根只读。
- 不再使用 `.env`，本地也无例外；默认值＋外置YAML＋MySQL业务修订。允许平台直接环境注入，不加载.env文件。
- 界面遵循本机设计skill，必须真实浏览器视觉检查。A的信息结构已确认，最终配色尚未确认。

## 文档入口

| 需要做什么 | 先读 |
|---|---|
| 修改任意配置／启动／存储／管理界面 | [统一治理规范](../../docs/configuration-governance.md)、[ADR0006](../../docs/adr/0006-admin-configuration-and-user-audit-governance.md) |
| 核对Java参数与实际消费 | [Java全部配置入口](java-configuration-inventory.md) |
| 核对管理字段与实际消费者 | [管理字段／API清单](admin-configuration-inventory.md) |
| 核对Node／Compose／启动工具 | [sidecar与启动盘点](sidecar-startup-inventory.md) |
| 核对事实表／JSON归属／敏感性 | [逐表清单](database-inventory.md)、[Pi四表清单](database-pi-inventory.md) |
| 查看真实本地库元数据／聚合 | [列与数量](evidence/local-mysql-metadata.json)、[索引与约束](evidence/local-mysql-indexes.json)、[审计覆盖](evidence/local-audit-coverage.json) |
| 准备完整控制台 | [内容与交互设计](console-design.md)、[规格草案](spec.md) |
| 纠正白名单范围、确认名单匹配；核对撤回证据 | [范围与研究记录](account-admission-research.md)；不新增认证或官方目录前置，当前不得接入业务 |
| 决定实施顺序 | [交付切片](delivery-plan.md)；按issues中的依赖实施，白名单不作为审计或业务的前置 |

目录规范分工：根AGENTS持入口；`src/main/AGENTS.md`约束Java配置和持久化消费；SQL目录AGENTS约束迁移；sidecar AGENTS约束通用引擎；`web/admin/AGENTS.md`约束正式UI与视觉证据。详细字段不复制到每层上下文。

## 盘点范围与证据

源码审计起点 `7479502`（当前工作树可能有并发修改，实施前重核相关文件）；覆盖生产Java配置声明／条件Bean／计划任务／环境读取／典型资源常量、管理修订及消费者、Node和工具/Compose、SQL资源与Java建表。Java非admin范围发现51处@Value、48处条件属性、10处Environment读取、2处调度属性、3次System.getenv；这些是调用点数不是唯一键数。第三方隐式配置及任意数值字面量不宣称全量证明。

只读检查本机business-preview MySQL容器3338的两个允许schema，未读.env、真实密钥、对话正文或密文；使用只读一致快照统计记录量，另查information_schema列／索引／约束。没有重启、切换、建表、配置写入或清库。元数据共52个schema-table、476列、199个索引列记录、108个约束记录；观测到0个外键。这不是生产数据库或真实业务验收。

| 本地聚合 | iot_agent_business | iot_agent_mysql_final |
|---|---:|---:|
| 表数 | 27 | 25 |
| 会话 | 10 | 2 |
| 会话轮次 | 75 | 24 |
| 公开流事件 | 14125 | 3912 |
| 草案 | 7 | 3 |
| 任务提案 | 13 | 3 |
| 已创建任务／计划／执行阶段 | 0 | 0 |
| 报告事实 | 6 | 2 |
| 草案审计 | 8 | 6 |
| 配置修订 | 5 | 1 |
| Pi scope／document | 4／12 | 2／6 |
| Pi chunk／commit | 473／477 | 217／219 |

冻结库少两张read_snapshot表；不等于应当立即升级它。审计JSON现有ownerKey/source路径覆盖8/8和6/6，principalId或actorId路径覆盖为0；这证明存在归属键路径，不证明姓名可回溯、事件完整或内容不可改。当前这些审计仅有草案提出／重核／取消，不能冒充成员查询、任务和管理员行为全覆盖。记录量为证据文件对应时间快照，不是实时仪表盘数字。

## 历史盘点差距（2026-11-03，非当前实施状态）

1. **没有统一配置定义。** Spring注入、Environment、直接env、管理camelCase、Node fallback并存；默认与单位/边界分散，桥接token有两个不同键。
2. **保存不等于统一应用。** 17个live字段随保存可进入下一轮，其他启动绑定或未消费；active视图可能混合两版，supervisor只确认接受重启。默认工作栅栏恒不忙；没有全组件采用回执。
3. **有可存但未接通项。** 草案TTL/心跳/回读/控制超时/扫描字段与真实属性未完整映射；Langfuse保存在Java但Node独立从env启用。4个旧cache字段已拒绝却仍残留前端类型。
4. **用户审计基础不足。** ownerKey不可逆，没有可信成员目录／准入授权库；audit_events为可覆盖upsert、producer可选，聊天流也可删除。Pi原生日志包含敏感引擎内容，不可直接作为普通审计详情。
5. **存储与恢复须按当前架构。** Java拥有全部27张表和Pi会话；部分表只在Java创建，JSON归属和自然复合键存在不一致；报告taskId根路径与origin路径有漂移。状态过期没有证明物理清理，保留／防篡改／备份调度未定。
6. **no-.env尚未交付。** 当前启动、Compose及runbook仍有依赖；只写规范不能说已移除。历史证据保持原貌，新流程落地后加明确替代入口。

## 当前实施边界

1. 白名单只做独立数据维护；不接业务准入、认证、任务取消或设备权限。其后用途没有在本轮定义。
2. 管理员登录、真实参数配置、按用户组织公司／办公室审计继续实施，采用既定HTTP＋真实消费者＋隔离MySQL／浏览器验证接缝。
3. 不纳入审计导出、法定防篡改、历史删除或自动保留期清理，不把未决定项变成扩大范围的理由。
4. 不修改原Runtime或生产配置；隔离本地验证不得向真实设备下发、创建生产任务或发送通知。

历史盘点、撤回证据与旧选择保留用于溯源，不覆盖以上最新范围。

## 原型来源与设计限制

原型保存在 `prototype/admin-user-console` 分支；本次核验分支HEAD为`2cd6cae6ae7694c2f682d7afea40fe375cf8146e`。旧`/tmp/iot-admin-user-console`路径本次不存在，旧5186地址不作为当前可访问预览保证。A布局选择有效；原型中的姓名／部门／健康绿灯、保存与权限模拟、Node文件备份均不是正式业务依据。

## 本轮验证

本节仅描述2026-11-03盘点：[文档校验记录](evidence/document-validation.json)保留当时11份文档、30个管理字段、17个live字段和本地27种表的核对结果。2026-11-05实施与实际界面证据以本文“当前交付”和收口票据为准；旧盘点数量不是当前字段数量，也不替代业务验收。
