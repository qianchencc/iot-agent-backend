# 独立开发者工具验收环境

> 固定开发后端的最新入口为 Java `3381` / Node `3382` / 控制台 `5185`；2026-11-06 起以常规命令启动（Java `java -jar … --spring.config.additional-location`、Node `node dist/server.js --config`、`web/admin` 内 `npm run dev`），见[仓根常规本地启动](../../README.md#常规本地启动)；Python／tmux 统一启动器与 launch-YAML 已随 `tools/` 退役。下文3217、13215等端口及 `tools/start-sidecar.py` 等命令为历史独立验收环境记录，不应据此启动或覆盖当前固定后端。

> 2026-11-10 15:08：固定Java`3381`已一次切换到共享设备事实版本，PID`24643`，继续监听`0.0.0.0`；开发版地址仍为`http://192.168.102.15:3381/api`。冻结包`var/shared-observations-20261110-145106/backend.jar`，SHA256=`a2305fa3d82051086482f1c07f350b659affe4dffafc477208db544b83848be3`。以上一条报告导出包为基线，仅替换本轮24个类／资源条目，其余816项字节保持；Node3382、控制台及私有配置不变。旧实例优雅退出后保存六表一致私有备份，旧包与配对备份在同目录保留；已登记共享事实及列注释两项迁移。本机／局域网健康正常。755项工程回归726通过、29跳过，另排除既有单项回放失败；隔离真实MySQL证明100个同权限新账号复用1次当前采集、1次环境历史全量，迁移失败回滚／重复启动、备份恢复后断源读取及电表失败去重均通过。本人授权仍独立准备；不据此承诺公司规模容量或真实新账号界面验收。官方9501自动化连接不可用，未注入身份或伪造首访通过。设计与保留约束见[ADR0009](../../docs/adr/0009-shared-device-facts-independent-grants.md)，工程与运行明细位于私有发布目录的`engineering-evidence.json`。

> 2026-11-10 14:56：固定Java`3381`已采用报告导出改进，PID`19319`，冻结包`var/report-export-20261110-activation/backend-final.jar`，SHA256=`22622552970c8ae22bc1c0aaf14bd9cdf82d9c14842340601be293537969677a`。仅替换／新增7个报告渲染类，831项原有内容保持，Node3382／PID4208及外置配置不变；切换前无在途模型运行／执行阶段／待执行阶段，私有一致备份与上一包均保留。报告等业务数量不变，同一报告保存事实哈希不变；同一冻结包重导出43台逐表对比、完整采样与8项指标，PDF逐页与XLSX原生预览核对。35项报告／边界回归通过、1项跳过；微信内重新下载因automator超时未复验，不扩展为全业务验收。[证据](evidence/REPORT-EXPORT-20261110/result.json)。


> 2026-11-10 14:19：固定Java`3381`已更新电表定时报告智能解读暂停改动（源码`5bfa3c5`），PID`5806`，继续监听`0.0.0.0`。冻结包`var/meter-report-no-interpretation-20261110-141810/backend.jar`，SHA256=`6267789a2bde2bb15b591bf4e09b015afbc4ba75903f9a845bcf6b65051789c1`；以原运行包为基线，仅替换检查点实现及内部类4项，其余831项内容保持。发布包字节码隔离执行确认报告生成不访问模型配置；重启前无在途模型调用或执行阶段，重启后局域网／本机健康、MySQL与Redis正常，任务13／阶段19／计划6／报告12／通知10数量不变。Node3382／PID4208与外置配置不动；回退包为下条新账号首访修复包。真实定时触发及微信报告展示仍未验收，未创建验收任务或通知。[更新证据](evidence/METER-REPORT-NO-INTERPRETATION/deployment.json)。

> 2026-11-10 13:44：固定Java`3381`已更新新账号首访修复，PID`93530`，监听`0.0.0.0`，开发版继续使用`http://192.168.102.15:3381/api`。冻结包`var/cold-account-bootstrap-20261110-1343/backend.jar`，SHA256=`a05462a608f1b36a7eb59d9114748470ff74c42a6176fa67b2aae4a77365e978`；仅替换目录同步器及其内部类3项，其他832项字节保持。回退包`var/plan-local-authorization-20261110-1128/backend.jar`保留；Node3382、管理台5185及私有配置不变。742项工程回归中713通过、29跳过，排除既有单项视频回放失败；隔离实际调度的新账号授权约5秒、0次新增历史采集。指定测试账号个人数据已准确匹配并备份后清理，备份位于私有`var/cold-account-bootstrap-20261110-133612/requested-user-backup.json.gz`（0600）。局域网健康正常，不代替真实开发版首访验收。

> 2026-11-10 清理：删除13个停用Java包、10个旧局域网镜像导出及本轮临时身份检查／smoke文件，释放约1.68GiB。两份数据库备份以0600权限无损压缩，解压SHA256一致后才移除原始副本；当前包、上一稳定回退包和指定账号清理前的私有备份保留。无残留测试JVM，当前Java／Node／控制台及开发者工具不因清理重启。

> 2026-11-08 23:28 视频接入修复：保留同一冻结视频状态 jar（SHA256 不变），仅把外置 `agent.video.allowed-origin` 改为客户播放页的精确来源 `https://ncim.577119.com`，优雅重启 Java `3381` 为 PID `21777`；Node `3382`／PID `32963` 不动。小程序客户播放页的取流 API 已改回本地 `http://127.0.0.1:3381/api`，没有新建公网隧道。真实微信同账号、同摄像头、同期间已到达本地取流链路，但客户上游录像查询返回 `code:200, content:{result:true,url:null}`，无录像首帧，业务仍未验收；不推断录像不存在，不换窗口冒充通过。详见[视频票09](issues/09-real-video-live-playback.md)。

> 2026-11-08 23:12：固定Java`3381`更新视频候选卡本地在线状态及真实采样时间修复，冻结产物`var/fixed-video-state-20261108-231135/backend.jar`，SHA256=`46816a507a345a7981713f9e6e8350c85b2e27368254a719c8c38f9054b996f3`。23:28 原PID`15433`收到SIGTERM优雅退出（非崩溃），外部守护/手动于23:28:38重启同jar为PID`21777`，3381健康恢复；Node`3382`/PID`32963`及原配置、表结构、业务记录数保持；回退产物为下条环境完整窗口jar。66项定向通过；真实13台本地状态只读投影通过，未申请视频流，微信/播放未验收。[工程与发布证据](../local-data-sync/evidence/video-candidate-state-20261108.json)。

> 2026-11-08 22:41：按用户要求将固定Java`3381`启动到最新工作区构建，PID`4647`，冻结产物`var/fixed-environment-window-20261108-224008/backend.jar`，SHA256=`12bd73ec5cc59375555dec15c426b4b131a504e41bdba5635aed3503a69542d9`，含环境趋势完整窗口读取。Node`3382`保持原PID`32963`；原外置配置、MySQL/Redis及业务记录数保持，切换前无在途对话/执行阶段，数据库备份与核验记录位于同一私有发布目录。Java健康与Node鉴权健康通过；不据此宣称微信业务验收。旧Java回退产物`var/fixed-alert-note-20261108-2037/backend.jar`保留。全量测试的既有安全录像失败见环境历史06票，未扩展修改。

> 2026-11-06 原生启动已实际切换：固定 Java `3381`／Node `3382` 运行 `var/fixed-native-20261106-014739/` 冻结产物，控制台 `5185` 通过 `npm run dev` 启动；助手仅在既有 tmux 窗口逐条执行常规命令，无 Python／启动脚本。私密配置统一为 `var/config/application.yaml`、`agent-engine.yaml` 与 `shared/agent.bridge.token`，配置修订9及原业务事实／Pi fencing 均保留，原 MySQL／Redis 未重启。640项 Java、34项 Node、8项启动安全、进程／重启契约、30/30 Pi 契约及真实配置页核对见[当前运行证据](../technical-spec-compliance/evidence/native-startup-20261106.json)。这是工程启动验证，不追加真实微信或设备执行验收。

> 历史：2026-11-05 固定实例曾按当时源码更新，Java `3381`／Node `3382` 使用 `var/fixed-latest-20261105-181818/` 的冻结产物，637 项 Java、34 项 Node 与 30/30 Pi Session 工程契约通过。固定业务库先备份／独立恢复预演再升级，原配置修订8、候选集及 Pi 会话保留；控制台 `5185`、MySQL、Redis 未重启。实际 PID、产物哈希、只读健康与事实核对见[当轮运行证据](../technical-spec-compliance/evidence/fixed-latest-20261105.json)，已由上述原生启动版本替代。

> 2026-11-05 草案排除已对齐原Runtime：补查设备短引用来源、候选集按当前对话查找、顶层／照明按真实ID合并。固定Java3381已更新并保留此前其他业务产物；516项隔离回归、4种参数实跑通过，真实小程序短引用提案首次成功并取消，未确认下发。见[设计、失败留档与界面证据](../draft-exclusion-alignment/evidence/DRAFT-EXCLUSION-REF/README.md)。

> 2026-11-04 任务中心与通知中心加载已修复：授权目录后台更新、本地事实直接读取，真实开发者工具两条记录分别由 5355/5332 ms 降至 26/80 ms；最终集成保留另线程生命周期字节码，464 项隔离回归通过。见[加载诊断、部署与界面证据](evidence/CENTER-LOAD-01/README.md)。

> 2026-11-04 区域异常提示已恢复原Runtime语义：真实异常结果＋当前读取权限＋查看者已读时间；目录继续后台同步，异常／已读每次读取本地事实，不再把“从未查看”当异常或缓存其布尔值。固定Java3381已更新，471项工程回归通过；真实办公室入口无误报角标，选择器原生条件均false，但弹层截图未取得，实际异常流转／真机仍未验收。见[对齐、实证与限制](evidence/REGION-ATTENTION-01/README.md)。

## 本地视频 SDK 部署补充（2026-11-04）

- 当前 `9419` 静态根目录为 `/Users/qianchen/works/新发展/iot-agent-video-player/public`。SDK 主脚本缺失曾导致“播放器组件未加载”，不是模型配置错误或无录像。
- 播放器仓库故意忽略 SDK 二进制；复制页面源码不等于完成部署。启动静态页前须按该仓库 `sdk-manifest.json` 单独部署并校验五项资源：`h5player.min.js`，以及 `playctrl1/`、`playctrl1simd/` 各自的 `Decoder.js`、`DecodeWorker.js`；路径均在 `public/owner/static/h5player/` 下。
- 本轮补齐固定版本资源，无需重启静态服务，未改播放器源码。普通浏览器使用小程序当前账号生成的原录像 URL，真实出画且画面时间推进；重新播放和返回释放通过。仅记本地 H5 已验证，小程序 WebView 复验及真机仍未验收。
- [资源哈希及前端验证记录](evidence/VIDEO-SDK-LOCAL/result.json)。办公区截图仅保留在记录指向的 `/private/tmp/`，不入库；记录不含身份令牌、录像流 URL 或客户控制写入。

## 当前入口（2026-11-03）

|用途|位置 / 地址|
|---|---|
|后端源码|`/Users/qianchen/works/新发展/iot-agent-backend`，`main`；本轮分支已合入，旧mysql-final工作树不再作为入口|
|主线前端源码 / 生成目录|`/Users/qianchen/works/新发展/mp`，`main` / `mp/unpackage/dist/dev/mp-weixin`；主线develop.local为3217|
|Java / Node主合并验证|`127.0.0.1:3217` / `127.0.0.1:3237`；主worktree构建，保持不变|
|实际冻结项目|`/Users/qianchen/works/新发展/iot-agent-backend-business/var/business-preview/mp-mysql-v17-node10`；目录名不是当前版本号|
|冻结窗口 / 自动化|`MySQL Final - Java 13215` / `ws://127.0.0.1:9444`|
|冻结Agent / 视频API|`http://127.0.0.1:13215/api`；不与3217或另一3215实例混用|
|冻结Java / Pi sidecar|13215 / 13235；原Java24、Node12不可变产物迁至主worktree `var/mysql-final-preview/var/business-preview/`，不是主线最新jar的验收证明|
|只读客户上游代理|`127.0.0.1:3245`；真实授权上游，不指向生产Runtime|
|冻结MySQL / Redis|3338的独立库 `iot_agent_mysql_final` / 6398的DB2；不覆盖主线使用的 `iot_agent_business`、Redis DB0|
|视频静态页|`http://127.0.0.1:9419/owner/h5player/index.html`|

主线3217/3237和另一3215/3235、原business工作树、9441及缓存3207/3223/9431保留不动。本轮只将13215/13235的进程运行路径迁出待删除工作树，端口、数据库、身份与冻结程序保持不变；不导入旧会话，不覆盖主worktree根`.env`或管理配置。独立私密配置位于`var/mysql-final-preview/.env`。

主线此前已记录构建及Office路径，历史证据在`.scratch/iot-agent-backend-refactor/evidence/MAIN-JAVA-3217/`；该轮最后答复也被蓝牙弹层遮挡，不能当作无遮挡通过。旧5185管理预览已由其拥有者停止，不把它误称当前管理面。

## 本轮结果与限制

真实业务证据和未验收项统一见[验收账本](../pi-session-mysql/acceptance.md)及[最终构建记录](../pi-session-mysql/evidence/final-validation-20261103.json)。F01–F21为迭代Java20–23的21条实际业务路径，不声称同一最终Java24从头重跑全部样本。Java24仅进一步补齐数据库审计字段；升级前后保留的记录数量是工程诊断，不是业务验收。

当前窗口又出现蓝牙权限弹层，需要在真实开发者工具中点击“拒绝”；SDK取消未生效、系统辅助访问被拒绝。F22带弹层的截图不计通过。不裁图、不伪造权限、不注入token/卡片/消息。只沿真实登录、园区、Office/Company入口操作。

验收可以生成草案、任务确认单和报告，但不点击执行、不创建任务、不发送通知。报告模拟补数/采样边界必须与界面及助手解读一致。录像卡不证明录像存在或播放成功。

## 构建与重启

在主worktree构建；冻结预览则加载专用`var/mysql-final-preview/.env`，不覆盖或混用根`.env`。私密配置权限0600，不打印或提交。先确认端口归属，已运行则复用；不得重建Docker卷或清库。

```sh
# 工程构建；不是业务验收。
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home mvn -q package
npm --prefix agent-sidecar run build

# Java：仅在本轮13215实例确需重启时执行，不覆盖不可变发布jar。
set -a; . ./var/mysql-final-preview/.env; set +a
/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java -Dagent.video.allowed-origin=http://127.0.0.1:9419 -jar var/mysql-final-preview/var/business-preview/iot-agent-mysql-session-preview-v24-20261103.jar

# Node：另一个终端；启动器仅传通用引擎白名单，不传DB/Redis/Admin凭据。
set -a; . ./var/mysql-final-preview/.env; set +a
PATH=/opt/homebrew/opt/node@22/bin:$PATH /Users/qianchen/anaconda3/bin/python tools/start-sidecar.py var/mysql-final-preview/var/business-preview/sidecar-mysql-ro-v12
```

当前Java与Node回调、会话私有协议均使用13215/13235。不要额外添加旧3235启动覆盖项。需要重新构建发布时创建新产物并记录SHA256，不能用编译成功代替业务重验。

现有DevTools9444使用上表冻结项目。前端源码重新编译可能恢复其另一线程的3217配置，**不可直接把新dist当作本轮13215项目**；先核对该项目 `config/settings.js` 的 `develop.local.agentBaseUrl`、`videoApiBaseUrl` 和实际请求归属，不改trial/release、不复制登录存储。不要关闭原开发者工具或清应用数据来规避竞态。

## 恢复、运维与安全边界

- Java管理业务事实和Pi上下文的MySQL持久化；Node仅是通用循环、原生压缩、流和受控检查点适配，无业务分支和数据库凭据。六小时无活动会话沿用既有过期策略，不承诺永久恢复。
- 直接切换不导入旧SQLite，旧文件只作离线历史；运行无双写、SQLite回退或本地会话卷。一次性cutover标记不得在每次启动清新历史。
- MySQL备份需包含业务事实、Pi表及迁移标记的一致恢复点，恢复到另一个独立库，不覆盖在线库。O02是v17真实恢复后继续业务的历史证据，不能冒充v24同版演练。
- Node/Java中断后只恢复已提交事实；未确认副作用视为未知，不自动重发。Redis不可用不能用清空历史掩盖。
- 上游代理仅放行明确只读路由及已授权读取动作；不能因HTTP动词为POST就认定控制，也不能用通配放行解决拒绝。未知控制、任务、通知、配置写入拒绝；不向生产试下发来证明禁写。
- 当前客户播放页来源精确允许 `https://ncim.577119.com`，只覆盖视频取流GET/OPTIONS；实际GET仍需真实身份。历史本地 `9419` 播放页不再是当前来源。预检200、静态页200、URL或录像卡不是播放验收。
- 自动化须等实际控件/报告加载完成；Markdown正文不一定出现在`page.text()`。禁用值可能是字符串`"false"`，不能按truthiness误判；截图保留失败与修复后版本。
- 不运行或重启可靠原Runtime；只读其源码作为业务逻辑对照。原业务父票不因存储迁移自动关闭，未抽取场景标未验收，不追加149条硬门禁。

## 任务迁移独立验证（2026-11-04）

- 主开发入口仍为 Java `3381` / Node `3382`，不修改主前端固定地址。任务验证另用 Java `3441` / Node `3442`、只读代理 `3445`、独立 MySQL 和开发者工具 `9461`；私有参数保存在 Git 忽略的 `var/task-parity-20261104-113400/launch.yaml`。
- Java 必须运行 Maven `target/` 之外的冻结 jar。先完成构建，再复制为独立产物并记录 SHA256；运行中的 jar 不得被下一次 `package` 覆盖。
- Node 的 `JAVA_AGENT_TOOLS_TOKEN` 必须与 Java 的 `AGENT_BRIDGE_TOKEN` 相同；工具和 Pi Session 回调共用该认证。只比较是否一致，不输出密钥。
- 隔离小程序通过官方 uniapp-cli-vite 在插件目录编译，显式指定 `UNI_INPUT_DIR` / `UNI_OUTPUT_DIR`，并加 `build -p mp-weixin --minify false`。本轮已在微信实际转换缓存定位到 production 压缩短名被 async/regenerator 再转换提升为局部变量，遮住 `agent-session` 导入并报 `waitForAgentSession` undefined；不要因此修改身份模块、增加延迟或清登录记录。
- 办公室与公司均沿用真实账号和既有权限。当前本地公司白名单为空，账号被 `COMPANY_AGENT_NOT_ALLOWED` 拒绝；该分支必须记未验收，不能为取得截图擅自添加授权。
- 真实前端卡片、详情、管理、通知与只读上游隔离执行分别取证；测试、脚本构造的任务/结果和 HTTP 成功只能记录为工程证据，不能制造历史业务通过截图。

## 主工作区固定入口交付（2026-11-04）

- 按用户要求，本轮任务迁移与主前端 taskId/planId 修正已收口到两个 `main` 工作区；Java 固定 `3381` 在原 tmux `fixed-backend` 的 `java` 窗口更新，Node `3382` 保持现有通用引擎与进程，主小程序地址不改。
- 运行产物为 `var/task-main-20261104-143307/backend.jar`，不运行可被 Maven 覆盖的 `target/` jar。主工作区源代码（含并发审计改动）完整构建 444 项、0失败/错误/跳过；测试夹具冲突通过排除 `@TestConfiguration` 修正，不改白名单业务。
- 私有 `var/fixed-backend/launch.yaml` 已补齐独立 `TASK_CREDENTIAL_KEY`，只在确认没有已加密任务身份后生成；原配置副本留在该运行版本目录，密钥不入仓库、不传给 Node。原客户网关和 `real` 配置保持不变。
- HBuilderX 主项目开发编译成功；真实开发者工具 `9451` 的办公室页已连接 `3381`、恢复会话并显示输入框。公司页当前由既有白名单拒绝，未擅自添加权限。截图与构建记录见 `evidence/OFF-TASK-01/20261104-main-runtime.json`。
- 用户自行继续测试任务创建、执行、详情/日历、计划管理和通知。此次启动/界面连接不是上述业务的通过证据；未点击生产确认、创建生产任务或发送生产通知。

## 白名单接入撤回（2026-11-04 后续更新）

- 上节公司入口被拒绝是当时状态，现已按用户要求撤回未定案白名单接入；不得再让用户通过添加白名单来解决该问题。
- 固定 Java `3381` 已更新为 `var/allowlist-withdraw-20261104-151828/backend-complete.jar`，Node `3382` 和前端地址不变。原有登录、官方身份核验、设备授权与任务安全配置保留；没有新增旁路开关或默认授权；遗留准入API与旧拒绝测试已删除。
- 最终444项回归通过；真实小程序公司总览显示设备目录334台，公司与办公室会话读取均成功，无登录身份的Agent请求仍返回401。未添加白名单记录、执行设备控制、创建任务或发送通知。
- 原白名单 CRUD 和历史数据保留但不参与业务准入；账号选择与内部ID绑定先做设计，见[账号选择研究](../admin-console-governance/account-admission-research.md)及[真实前端恢复证据](../admin-console-governance/evidence/allowlist-withdrawal/result.json)。
