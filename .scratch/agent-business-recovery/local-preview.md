# 独立开发者工具验收环境

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
- 视频来源精确允许 `http://127.0.0.1:9419`，只覆盖视频取流GET/OPTIONS；实际GET仍需真实身份。预检200、静态页200、URL或录像卡不是播放验收。
- 自动化须等实际控件/报告加载完成；Markdown正文不一定出现在`page.text()`。禁用值可能是字符串`"false"`，不能按truthiness误判；截图保留失败与修复后版本。
- 不运行或重启可靠原Runtime；只读其源码作为业务逻辑对照。原业务父票不因存储迁移自动关闭，未抽取场景标未验收，不追加149条硬门禁。
