# 区域时间轴重复卡修复

日期：2026-11-09。范围：本地Java3381与主小程序；主代理实施，无子代理，不修改真实操作／任务事实或控制出口。

## 根因与实现

- 改前只读复现：区域逐日接口在11-07至11-12均返回11-09的两条即时操作，同两条记录在六天窗口变成12份。普通无interval任务的`overlaps`直接返回true，漏掉真实阶段时间；原Runtime按阶段MIN/MAX与窗口相交过滤，Java迁移未对齐。
- `JdbcTaskProjectionStore.regionTimeline`在取得真实阶段投影后复用`inWindow`；保留显式区间的廉价提前排除，并将其命名为`intervalMayOverlap`，不再把预筛选误当完整日期校验。`startOf/endOf`同时读取内部JsonNode与公开Map区间，真实跨日区间不会被当作单一阶段丢弃。不新增查询、缓存或数据结构。
- 前端`regionTimelineItems`先按具体记录id合并，保留较新version，再过滤取消项；旧日快照不恢复已经取消的记录。同一计划不同发生日的id不同，正常周期安排继续逐日展示，不按planId折叠。
- `TaskDetailProjector.labelOf`仅在读取即时历史时将旧“操作草案”后缀投影为“操作记录”；任务详情／列表／区域及设备历史共用。自定义名称、真实任务与待确认草案不改，原始持久标签不改写；移除原重复label读取实现。

## 回归及实跑

- 后端新增2项公开投影回归先红后绿：上海午夜边界、当天唯一归属、前后日不泄漏；多阶段跨度和显式区间仍覆盖中间日期，并在结束后退出。夹具包含真实写入历史时payload没有phases、实际阶段独立存储的形状。
- 前端新增2项回归先红后绿：跨日重复只留一张、较新版本优先、同计划不同日期不合并、较新取消不被旧快照复活，最终布局key唯一。全部新夹具使用合成身份与设备，不包含真实个人资料。
- 完整后端`mvn -q verify`：730项，700通过、1失败、29条件跳过；仍为既有`SafetyReviewToolContractTest.singleCandidatePreparesRealPlaybackCardAndProjectsVideoPlaybackBlock`第81行非空断言。未排除该失败。`mvn -q -DskipTests package`成功，不解释为全套通过。
- 最终前端368项全部通过，186源码文件边界和修改源码格式检查通过。第一轮全套曾撞上另一路文案改动及其旧断言；未覆盖该路源码／断言，等待其已恢复后运行最终全套。最初语法键恢复补丁因原文件已自行更新而拒绝，未应用。
- 实际MySQL会话强制并核对READ ONLY，运行公开TaskProjectionStore：11-07／08为0；11-09为4条，其中3条即时历史；11-10／11／12各2条正常未来安排，即时历史均为0。原两条操作仍存在；第三条是期间另外真实确认产生，并非修复生成。
- 实际前端映射和布局回放：冻结原问题响应只显示原两条即时记录，重复key为0；修复后实库响应显示3条不同即时记录，重复key也为0。未通过简单删除真实记录或合并不同发生日“消除”异常。

## 真实界面

开发者工具当前账号打开同一区域，真实界面显示“今天 4条记录”：原两条照明分别一张、名称为“照明操作记录”，另有正常周期任务与后来的一条真实操作。没有重复挤到凌晨。见[REGION-TIMELINE-01截图](evidence/REGION-TIMELINE-01/after.png)。界面读取、标签和重复卡消除已观察；未新建任务、确认草案或触发设备控制。

相邻日期自动化窗口连接被重开／中断，未留存次日真实页面截图；次日数据过滤与多天布局已由上述回归和只读程序验证，不能冒充次日界面验收。手机真机仍待复验。

## 本地部署及回退

- 本轮部署：`var/region-timeline-fix-20261109-155030/backend.jar`，当时PID58624，服务`RegionTimelineFixJavaNov09`，LAN `192.168.102.19:3381`。
- 本轮包SHA256：`b53a1e4c90db90436412fac1b3093cdbb5eb52a7b7581d36248434a53c91f1ac`。
- 交付末检查：另一线程已将3381更新为`var/task-creator-name-20261109-155111/backend.jar`／PID60375，SHA256 `47fc9ed6fdd6b24715d0f10d0c2b4b11330daaf27e0b97a5fea481cb70475c28`。新包的JdbcTaskProjectionStore、TaskDetailProjector与本轮修复类逐字节一致，LAN健康正常；没有覆盖或回退该后续更新。
- 单向基于`var/task-confirmation-fix-20261109-151329/backend.jar`，仅替换JdbcTaskProjectionStore、TaskDetailProjector两个class，其他JAR成员逐项零差异。上一包及既有一致数据库备份保留；无数据库迁移，不自动恢复事实库。
- 切换前Sidecar activeRuns为0，任务无EXECUTING、阶段无PENDING／EXECUTING；Node3382、模型、外置YAML、凭据与数据库结构不改。微信开发构建已编译／打开；uniCloud、旧页面sourcemap与鸿蒙告警保留。
- 临时只读Java烟测、原始截图副本及本轮安装的原生自动化SDK在验证后清理；真实证据图片、本地后端和开发者工具保留。未混入其他线程的源码、文案或个人临时文件。
