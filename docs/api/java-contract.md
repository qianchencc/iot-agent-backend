# Java 后端 HTTP 契约补充

业务 HTTP 和 NDJSON 基线仍为 [Runtime OpenAPI](../../../iot-agent-runtime/docs/api/openapi.yaml) 与 [事件协议](../../../iot-agent-runtime/docs/api/ndjson-events.md)。本文记录 Java 当前维护的参数规则、独立管理端扩展与返回语义，不复制一份会独立漂移的 Runtime 全量契约，也不把已删除的 Runtime 管理功能当作 Java 能力。

## 通用约定

- 业务路径 `/api/agent/**` 必须携带 `Authorization` 和 `Tenantcode`；登录令牌由服务端核验。对象还需通过主体、租户、来源及设备授权检查，知道 ID 不等于有权访问。
- `source` 为字符串 `office` 或 `company`；省略时沿用各接口原有的默认／对象来源推断。显式非字符串不会转换成默认来源。
- 对象 ID（会话、草案、设备、任务、计划、报告、事件）为字符串。内部自增行号不是对外对象 ID。游标是不透明字符串，调用者不自行拆解或生成。
- JSON 请求正文须为对象。整数控制参数必须为 JSON 整数、可无损转换到 Java `int`，再满足业务范围；不接受字符串、小数或溢出整数。
- `revision`、`version`、`expectedRevision` 为可无损表示的整数版本。草案 revision 可为 0；任务 version 从 1 开始。只在原有可选接口上允许省略，显式非法值不能触发默认版本。
- 确定性失败返回 `{code,message,recovery?}`。格式／绑定错误返回 400 `INVALID_REQUEST`；具体业务参数错误保留对应业务码；版本冲突为 409；未分类内部错误为 500 `INTERNAL`，不返回解析器、SQL 或上游秘密。
- 日期 `YYYY-MM-DD` 采用 `Asia/Shanghai` 业务日；需要时间点的参数使用带时区的 ISO 时间。数据库审计时间不决定用户确认的任务执行时间。
- 任务详情的阶段、报告及逐设备观察均按当前授权裁剪；只剩部分设备权限时，不能安全拆分的能耗汇总、原始 facts 和报告整块不公开，仍返回获授权设备的可分离观察，不伪造全量成功。

## 会话

前缀 `/api/agent/conversations`；返回字段及流事件参见基线 OpenAPI。

| 方法与路径 | 输入与效果 | 返回与失败 |
| --- | --- | --- |
| `POST /` | 可选对象正文 `{source?}`；建立当前身份来源下的会话 | 201，会话对象；非法 source 为参数错误，不是 500 |
| `GET /current` | 可选查询 `source` | 当前来源会话及恢复所需事实 |
| `DELETE /{id}` | 可选查询 `source`；清理该身份可访问的会话 | 清理结果；不允许跨主体清理 |
| `DELETE /{id}/turns/{turnId}` | 可选查询 `source`；撤回指定轮次 | 撤回结果，遵循在途运行约束 |
| `POST /{id}/messages` | 正文 `content`，可选 `focusRegionId`、对象 `reference`；`Idempotency-Key` 请求头 | `application/x-ndjson`；恢复、取消及终态遵循事件协议，HTTP 成功不代表控制成功 |
| `POST /{id}/runs/cancel` | 可选查询 `source` | 当前运行取消结果；不重放设备副作用 |

## 即时动作草案

前缀 `/api/agent/action-drafts`。读取返回草案视图；只有人工确认路径可以进入执行。草案版本变化后应重新读取，不用旧 revision 强行覆盖。

| 方法与路径 | 输入 | 返回与约束 |
| --- | --- | --- |
| `GET /{id}` | 可选查询 `source` | 当前草案视图；读取可体现真实过期／终态 |
| `GET /{id}/device-states` | 可选查询 `source` | 设备观察事实；未知状态不视为成功 |
| `GET /{id}/summary`、`POST /{id}/summary` | 可选查询 `source` | 该草案执行摘要，不重新下发设备指令 |
| `PATCH /{id}/selection` | `{revision,selectedActionIds:[string]}` | 更新后的草案；选择列表须完整通过类型检查 |
| `PATCH /{id}/parameters` | `{revision,changes:[object]}`；每项标识动作及其修改参数 | 更新后的草案；逐项校验，不能混入数字／字符串元素 |
| `POST /{id}/restore`、`POST /{id}/revalidate` | `{revision}` | 恢复／重核后的草案；重核不代表已执行 |
| `POST /{id}/confirm` | 可选查询 `source`；原契约允许省略 revision，提供时须有效 | **直接返回执行结果，不是草案包装**；拒绝／中断结果为 502，其余按真实结果返回，200 不等于物理执行已证实 |
| `POST /{id}/cancel` | 路径 ID | 取消后的草案；不可取消的终态按业务规则拒绝 |

控制 command 的统一规则：

| kind | 字段 | 合法值与组合 |
| --- | --- | --- |
| `lighting` | `closeOrOpen` | 仅整数 0 或 1，不接受其他字段 |
| `access` | `controlType` | 仅整数 2，不接受其他字段；网关受理不等于门已打开 |
| `ac` | `power`、`temperature`、`mode`、`fanSpeed` | 电源 on/off；温度 16–30℃整数；模式 cool/heat/dry/fan；风速 low/high。关闭空调不能同时设置其他参数；指令不能为空 |

动作提案、草案编辑、任务正规化与下发转换使用同一套数值规则。模型输入、前端输入和持久化指令均不能绕过范围校验。

## 任务、计划和通知

前缀 `/api/agent`。任务事实、阶段和通知由 Java 维护；创建确认单不等于创建任务。任务详情中的执行结果、报告生成与通知投递是不同事实。

| 方法与路径 | 输入 | 返回与约束 |
| --- | --- | --- |
| `POST /task-proposals` | `source?`、`conversationId`、`turnId`、`payload`、`idempotencyKey` | 待确认提案视图；控制参数同上，不执行设备操作 |
| `GET /task-proposals/{id}` | 可选查询 `source` | 提案视图 |
| `POST /task-proposals/{id}/confirm` | `{version}`，可选查询 source | 确认结果；版本与归属重新核验 |
| `POST /task-proposals/{id}/discard` | `{version}`，可选查询 source | `{taskId,status}`；放弃而非执行 |
| `POST /tasks/{id}/confirm`、`POST /tasks/{id}/discard` | 同对应提案接口 | Runtime 契约已有的客户端路径，不是绕过校验的入口 |
| `POST /task-changes`、`POST /task-cancellations` | 变更／取消提案对象，含会话关联与幂等键 | 待确认变更／取消视图 |
| `GET /task-changes/{id}`、`GET /task-cancellations/{id}` | 可选查询 source | 对应提案视图 |
| `POST /task-changes/{id}/confirm`、`POST /task-cancellations/{id}/confirm` | 可选 version；只有真正省略才保留旧契约默认值 | 确认结果；显式错误版本不回退为 1 |
| `POST /task-changes/{id}/discard`、`POST /task-cancellations/{id}/discard` | 同对应提案的版本要求 | 放弃结果 |
| `GET /tasks` | `source=office`，可选 `from`、`to` 时间点 | `{tasks:[...]}`；查询窗口最多 62 天，默认向前 31 天 |
| `GET /tasks/{id}` | 可选 source，默认 office | `{task:...}`，含当前可见阶段／结果；无告警时 latest/windowLatest 可以为 null |
| `GET /tasks/{id}/lighting-states` | source，默认 office | 授权照明观察快照，不重新执行任务 |
| `POST /tasks/{id}/cancel` | `{version}`；可选 source | `{taskId,status,version}`，以任务真实终态规则为准 |
| `GET /regions/{regionId}/timeline`、`GET /devices/{deviceId}/timeline` | source、date 或相应日期边界 | `{date,tasks}`；按当前授权裁剪 |
| `GET /regions/{regionId}/calendar`、`GET /devices/{deviceId}/calendar` | 必填 from/to 日期、source | `{from,to,days}`；to 为包含当天的日期，差值不得超过 62 天 |
| `GET /task-plans`、`GET /task-plans/{id}` | source，默认 office | `{plans}`／`{plan}` |
| `POST /task-plans/{id}/status` | `{version,operation,confirmed?}`，保留既有 status 字段输入 | 计划操作结果；业务状态和再次确认规则由服务端决定 |
| `POST /task-plans/{id}/cancel` | `{version,scope,date?}`；occurrence 范围需要 date | 计划／发生实例取消结果，不把删除计划等同于删除事实 |
| `GET /notifications` | source、limit（默认30）、可选 before | 当前身份可见通知；不能从任务执行成功推断投递成功 |
| `POST /notifications/{id}/seen`、`POST /notifications/seen-all` | source，默认 office | 当前身份的已读结果 |

## 独立管理端

前缀 `/api/admin`。管理身份独立于业务 Bearer；登录后使用 HttpOnly Cookie。所有非 GET 请求须有匹配配置的 `Origin`，除登录外还须发送会话提供的 `X-Admin-CSRF`。读取不回传模型密钥、桥接 Token、数据库口令或历史密文。

| 方法与路径 | 输入 | 返回与失败 |
| --- | --- | --- |
| `POST /login` | `{password:string}` | Cookie 与 `{csrfToken}`；凭据错误401、失败限流429、未配置凭据503 |
| `GET /session` | Cookie | `{csrfToken}`；无会话401 |
| `POST /logout` | Cookie、Origin、CSRF | `{ok:true}` 并使会话失效 |
| `GET /config` | Cookie | 下述配置视图 |
| `PATCH /config` | `{expectedRevision,changes:object}` | 新配置视图；参数400、版本冲突409；部署只读／锁定项按业务码拒绝 |
| `POST /restore` | `{expectedRevision,revision}` | 生成新修订后的配置视图，不覆盖历史，不回读历史秘密 |
| `POST /apply` | 无业务正文 | `{code,message}`，HTTP状态反映应用结果；接受调度不冒充所有消费者已采用 |
| `GET /changes` | 无查询参数 | `{changes:[{revision,at,actor,changedFields}]}`，仅字段名，不含秘密旧值 |
| `GET /prompts` | 无查询参数 | revision 与指引预览；保存后的指引以管理配置和 Java 指引模块为准 |
| `POST /test-model` | `{changes:object}`；与已保存配置合并生成连接测试候选 | 成功 `{ok:true,message}`；失败502 `CONFIG_TEST_FAILED`；不保存候选 |
| `GET /pi-session/health` | 无查询参数 | `{code,message,details?}`；details 为受限健康投影，不开放任意会话内容 |

配置视图：

- `revision`／`activeRevision`：保存与采用的修订信息；`pendingRestart`／`changedFields`／`applyEnabled`：差异及应用能力。
- `saved`／`active`：脱敏配置；`secrets`：秘密存在状态；`locked`：锁定字段。
- `fields`：字段名称、类型、合法范围、读写及生效元数据；`deployment`／`deploymentSettings`：只读部署信息。
- `runtime.observability`：Node真实上报与当前配置比对结果；不可由保存修订号替代采用证明。
- `/password` 不存在。管理员部署凭据不通过网页修改。

### 手机号名单

名单只维护手机号与可选备注，**不控制业务准入、设备权限或任务取消**。

| 方法与路径 | 输入 | 返回 |
| --- | --- | --- |
| `GET /allowlist` | 无参数 | `{revision,users:[{mobileNumber,name,addedBy,addedAt}]}`；name 可为 null |
| `POST /allowlist` | `{expectedRevision,mobileNumber,name?}` | `{user}`；手机号／备注由服务端校验 |
| `POST /allowlist/remove` | `{expectedRevision,mobileNumber}` | `{ok:true}` |

非法输入为400 `ALLOWLIST_INVALID`，冲突409 `ALLOWLIST_VERSION_CONFLICT`，存储不可用503 `ALLOWLIST_UNAVAILABLE`。手机号始终是字符串，不是整数。

### 成员业务轨迹

前缀 `/api/admin/audit`。查询是只读审计投影，不暴露任意SQL、Pi隐藏思维或完整工具原始内容；身份不明时显示未识别主体，不借用名单备注充当成员姓名。

| 方法与路径 | 输入 | 返回 |
| --- | --- | --- |
| `GET /tenants` | 无参数 | `{tenants:[string]}` |
| `GET /event-types` | 无参数 | `{eventTypes:[{type,label}]}`；是实际事件词汇，不代表所有业务已验收 |
| `GET /members` | 必填 tenantCode；可选 identityRealm/principalId；limit默认50，范围1–200；offset默认0，非负 | `{members,total,offset,limit}`；成员含稳定身份、identityResolved、displayName、eventCount、sources、firstAt/lastAt |
| `GET /` | 必填 tenantCode；可选 principalId、identityRealm、source、eventType、unresolved、from/to、result、cursor；limit默认50，范围1–200 | `{events,nextCursor,limit}`；事件含 eventId、tenantCode、eventType、at、result及存在的关联字段 |
| `GET /{eventId}` | 字符串事件ID | `{eventId,tenantCode,eventType,createdAt,payload}`；payload 为字段白名单和脱敏投影；不存在404 `AUDIT_NOT_FOUND` |

筛选时间使用 ISO 时间点；下一页原样传回 nextCursor。审计存储故障返回 `AUDIT_UNAVAILABLE`，不伪装为空列表成功。

## 维护入口

- 对齐业务、NDJSON与错误体时先核对 Runtime 基线，再核对本文的 Java 扩展和严格校验约束；不修改可靠生产 Runtime 来迎合 Java 实现。
- 修改控制参数时从统一控制规则入口入手，动作、任务与网关不能各自定义范围。
- 修改持久结构时同步规范建表资源和旧库迁移；数据库代理主键不得替代业务唯一约束。
- 工程回归及隔离程序验证不替代真实微信业务验收；本次改动不包含生产部署约束或在线库切换。
