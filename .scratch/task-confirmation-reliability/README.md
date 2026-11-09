# 提案确认与放弃修复

日期：2026-11-09。主代理实施，无子代理。范围：本地Java3381及主小程序；不切换云端、不改Node／模型／身份或控制策略。

## 根因与最小修复

- `AuthorizedDevice.canonical`过去把实时`JsonNode`当普通字符串，本地数据库回读则为`Map/List`；同内容全目录绑定指纹不一致。现按对象键、数组及标量递归处理，保留原Map/List及实际绑定字段的校验。真实提案的334台冻结目录实跑证明：既有提案目录／绑定指纹保持不变，与同内容实时表示一致；不迁移历史或绕过校验。
- 前端`discardTask`原请求不带version，真实控制器返回400 `INVALID_VERSION`。现与confirm及其他提案管理接口一致，传当前卡片版本，不默认填1。
- 原提案已超过15分钟确认有效期；后端以前连放弃也执行过期拒绝。现允许当前所有者按版本放弃PENDING_CONFIRMATION或EXPIRED提案，保持CAS、来源与归属校验、重复放弃幂等；CONFIRMED仍拒绝，不能借此取消已创建任务。
- 确认和放弃失败保留错误对象送至现有统一toast入口，不先丢掉code。TASK_DEVICE_UNAVAILABLE与版本冲突／上游不可用分别展示；不新增错误状态或重试机制。

## 工程证据

- 新增真实公开TaskFacade／JDBC存储回归先红后绿：JsonNode存取后可确认周期计划、重复确认同计划、真实控制绑定变化拒绝且不落计划；过期未确认两种状态可放弃、旧版本拒绝、重复放弃安全。
- 后端完整`mvn -q verify`：728项，698通过、1失败、29条件跳过。失败仍为既有`SafetyReviewToolContractTest.singleCandidatePreparesRealPlaybackCardAndProjectsVideoPlaybackBlock`第81行非空断言，未排除或重钉。`mvn -q -DskipTests package`成功，不将其解释为全套通过。
- 前端完整365项通过；186源码文件边界通过；3个修改的Agent源码文件Prettier检查通过。微信开发构建成功；uniCloud未关联、旧页面sourcemap及鸿蒙兼容告警保留，未为此改无关代码。
- 独立程序加载实际前端`apis/agent/index.js`及会话操作模块，经真实控制器HTTP与临时MySQL运行：相同JSON确认恰好1个周期计划；过期版本7提案放弃持久为CANCELLED／版本8；重复确认、放弃幂等；真实绑定变化返回409且前端显示专门范围变化toast。最终计划1、单次任务0；全部为合成身份／设备，未访问客户上游、未启动执行扫描或下发控制。不是仅用HTTP200判断通过。
- 自审按已确认需求和仓库规范进行：没有改变JSON Map/List既有编码、放宽真实授权或版本校验、引入配置／数据库结构／缓存／队列，也没有将原有前端WIP算成本次修复。

## 当前本地部署与回退

- Java：`var/task-confirmation-fix-20261109-151329/backend.jar`，PID44876，LAN `192.168.102.19:3381`，服务`TaskConfirmationFixJavaNov09`持久运行。
- SHA256：`358dfd663186cd3e17453a794c0962d2bba191063e67b67d9d2c0e016c0c5718`。
- 基于上一可靠版本`var/energy-weekday-final-20261109-143028/backend.jar`单向覆盖AuthorizedDevice、TaskFacade两个class；其他JAR成员逐项零差异。TaskFacade运行字节码差异仅为放弃过期提案的条件。原JAR及既有一致数据库备份保留，不自动恢复数据。
- Node3382、外置YAML、数据库结构及运行凭据不改；切换前没有执行中任务／待执行阶段或近期在途对话。LAN及私有Sidecar健康正常，仅作运行入口证据。
- 前端先独立保存原有改动为`23541d9e462c` WIP，修复在其上单独提交；未跟踪的个人／临时文件未混入。

## 微信界面及未验边界

已重新编译并打开微信开发者工具，通过真实公司总览入口恢复原公司对话，界面显示服务在线及原提案“已过期”。见[UI-READ-01真实截图](evidence/UI-READ-01/restored-proposal.png)。这是读取／展示证据，不是创建或放弃成功证据。

原卡已过确认有效期，界面没有可点的放弃按钮。本轮没有生成／注入新真实消息或提案、没有点击创建、没有代用户放弃其他提案；真实微信新提案的创建／放弃点击及现场设备执行仍未验收。修复后的工程闭环由上述实际前端API→控制器→隔离MySQL证明，不冒充前端业务验收。

隔离库、独立Java／Node烟测程序、临时原字节码及临时自动化SDK均在验证后清理；开发者工具和本地后端保留供用户继续操作。
