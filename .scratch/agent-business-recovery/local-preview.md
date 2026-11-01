# 独立开发者工具验收环境

日期：2026-11-02。状态：独立Java/Node/数据与开发者工具已启动；真实Office只读单例有界面结果。不是完整说明书验收，不关闭业务票，也不证明Java/Node职责切换已完成。

## 1. 固定工作区和链路

|用途|本轮位置/地址|
|---|---|
|后端源码|`/Users/qianchen/works/新发展/iot-agent-backend-business`；`refactor/java-agent-business`|
|前端源码|`/Users/qianchen/works/新发展/mp-agent-business`；`refactor/java-agent-business-preview`|
|前端生成目录|`mp-agent-business/unpackage/dist/dev/mp-weixin`|
|可见新窗口|`Agent Business WT - Java 3215`；原窗口不关闭、不清缓存|
|自动化|`ws://127.0.0.1:9441`；原9431不动|
|Java|`127.0.0.1:3215`，从本worktree重新构建的jar启动|
|Pi sidecar|`127.0.0.1:3235`，从本worktree的agent-sidecar重新构建启动|
|只读上游代理|`127.0.0.1:3245`→客户真实物联上游；不指向生产Runtime|
|MySQL|独立容器、`127.0.0.1:3338/iot_agent_business`|
|Redis|独立容器、`127.0.0.1:6398/0`，不共享原实例|
|Pi Session/Admin|本worktree `var/business-preview/`下独立路径，私密配置不提交|
|本地视频静态页|`http://127.0.0.1:9419/owner/h5player/index.html`|
|develop Agent/视频API|均为`http://127.0.0.1:3215/api`；trial/release不变|

主链路：小程序3215→本地Java→3235真实Pi/模型→Java工具→3245只读上游。业务事实只落本轮独立库，真实用户权限由Java及客户上游核验。模型供应商配置复用合法本机配置，但bridge、管理员和数据库密钥重新生成，不复制客户身份。

本轮实际服务名：`business-preview-java-3215`、`business-preview-sidecar-3235`、`business-preview-upstream-3245-v3`、`business-preview-player-9419-v2`。Docker项目为`iot-agent-business-preview`，命名卷`business-mysql`/`business-redis`由该项目独占。不要运行原compose或删除任何持久卷。

## 2. 重启/恢复前检查

1. 确认仍在上述两个worktree。原缓存工作区不修改；不整包复制其未提交代码、`.env`、`var/`、target或dist。
2. 本地`.env`为0600，包含独立端口、库、bridge及模型配置；不要打印、提交或贴入日志。若丢失，重新生成独立数据库/bridge/Admin密钥，模型配置只取明确白名单，不复制原运行文件。
3. 确认端口归属；已是本轮服务则复用，不启动重复实例。不终止占用原3207/3223/9431的缓存链路。
4. 原miniapp启动缺陷只精确迁入必要修复：普通无token登录、token反序列化配对、首次园区选择；原缓存整改未整包带入。前端所有业务数据仍来自真实应用流程。

## 3. 构建与启动

以下为不同终端分别执行的命令；环境值只从本worktree忽略文件加载。不要把密钥写到命令参数或产物中。

```sh
# 在后端worktree；只使用专用compose，绝不使用旧compose.yaml启动本环境。
docker compose --env-file .env -f compose.business-preview.yaml up -d --wait

# 本轮启动构建确实使用了此命令；没有运行测试，不能算工程门禁通过。
mvn -q -Dmaven.test.skip=true clean package
npm --prefix agent-sidecar run build

# 终端A：上游只读出口
set -a; . ./.env; set +a
/opt/homebrew/opt/node@22/bin/node tools/business-preview-upstream.mjs

# 终端B：Java（从后端worktree运行）
set -a; . ./.env; set +a
/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java -jar target/iot-agent-backend-0.1.0.jar

# 终端C：Pi（从agent-sidecar目录运行）
set -a; . ../.env; set +a
/opt/homebrew/opt/node@22/bin/node dist/server.js

# 终端D：从后端worktree启动独立静态页。禁止默认http.server记录带token的URL。
python3 tools/business-preview-player.py --port 9419 --directory '/Users/qianchen/works/新发展/iot-agent-video-player/public'
```

本轮没有宣称测试通过。每个业务票仍必须完成约定工程回归及双轴审查；`maven.test.skip=true`只是本次环境应用构建的事实，不是后续闭票方法。

前端仅编译独立项目，不触发上传或发布：

```sh
/Applications/HBuilderX.app/Contents/MacOS/cli project open --path '/Users/qianchen/works/新发展/mp-agent-business'
/Applications/HBuilderX.app/Contents/MacOS/cli launch mp-weixin --project '/Users/qianchen/works/新发展/mp-agent-business' --compile true
/Applications/wechatwebdevtools.app/Contents/MacOS/cli open --project '/Users/qianchen/works/新发展/mp-agent-business/unpackage/dist/dev/mp-weixin'
```

生成配置中的projectname可设为`Agent Business WT - Java 3215`用于区分窗口，不改AppID或注入业务数据。启动自动化使用已安装的`miniprogram-automator`，projectPath明确指向上述生成目录、端口9441、trustProject=true；已有9441时用connect，不重复launch或关闭原IDE。CLI auto实际使用`auto --project <生成目录> --auto-port 9441 --trust-project`。自动化包当前在`/tmp/mp-automator/node_modules`，若目录被系统清理，在新的本地工具目录正常安装同版本依赖，不修改程序业务依赖。

## 4. 真正的环境/路由核实

仅启动成功或healthz不够。每次切换构建后：

1. 在新窗口沿真实客户登录及组织/园区链路到首页，检查有物联助手菜单；不复制或注入token，不清原窗口登录。
2. 真实点击物联助手进入仪表盘，再从办公室入口进入Office。等页面路由和可交互控件完成，不在tap后立即对旧page调用waitFor。
3. 在当前应用上下文只读检查编译配置的Agent地址为3215，结合本worktree的脱敏Tomcat访问记录证明实际请求到达。不要读取或保存Authorization等凭据。
4. 真实输入说明书只读问题并发送，观察界面结果、截图；同时确认请求经历本轮Java、Pi和授权上游。接口日志只用于定位链路，业务判定仍看界面。
5. 记录Java jar、sidecar构建文件和miniapp生成文件SHA256以及来源commit/未提交变更，避免旧产物截图被当新构建。

本轮已走完的只读单例：[OFF-QRY-01基线证据](evidence/OFF-QRY-01/README.md)。界面实际显示4盏灯且均为关，没有执行控制或创建任务。该证据必须在最终集成构建重验；当前Node仍有旧业务定义，不能因此关闭01。

## 5. 自动化与截图陷阱

- `launch()`刚返回时，`currentPage()`可能遇到页面元数据尚未建立；先等应用页面栈稳定，再获取page。不清应用数据来解决自动化竞态。
- `property('disabled')`本版本实测返回字符串`"false"`，不能按JS truthiness误判为禁用；只把布尔true或字符串`"true"`视为禁用。
- `page.text()`不一定包含Markdown自定义组件正文；本轮plain text只返回处理记录，但真实截图已有完整灯清单。必须看实际截图，不用DOM摘要下错误结论。
- SDK截图要等渲染完成。需要完整IDE窗口证据时，按窗口标题查询CoreGraphics window ID，再用`screencapture -x -l <window-id>`；不要按屏幕矩形抓图，用户切换前台会抓到其他程序。
- 无关蓝牙授权可拒绝，不操作真实BLE设备；遇到原生弹层先正常关闭，再验证截图。不得裁掉失败弹层冒充成功。
- 工具连接失败、导航竞态、按钮读取类型错误是工具诊断，不等于后端业务失败；相反，页面实际失败不能被200或终态覆盖。

## 6. 生产禁写与证据边界

代理只允许源码白名单内的身份/目录/现态/历史GET。`interactive/.../down`不能仅按HTTP动词判断：只允许同一Bearer+tenant先前真实授权目录中被识别为`realTimeData/acDetail/view`的读取动作（短期授权），及Runtime明确的录像读取形状；其余控制、任务、通知、配置写入拒绝。授权取流是读取，不是设备控制。新增读路由必须核对Runtime语义，不能用通配放行解决403。

代理本轮用独立假上游做过工程烟测：真实转发读取、未知query拒绝、读action与控制action区分、身份隔离、控制/任务/通知拒绝。该夹具不是业务证据。真实环境未进行控制写入测试；物理禁写由代理出口及操作范围共同保证，不通过生产试下发来验证。

当前前端其他客户菜单仍属于原小程序，不在本轮Agent验收范围，不从这些菜单进行设备控制。新环境也没有生产任务/通知事实；需要历史管理证据按票13取得合法来源并导入独立库，不能把当前空列表认成已迁移历史。

视频静态页和API已本地分流，但真实视频播放、CORS/WebView、SDK加载和摄像头/录像源尚未验收，由09完成；不以静态页200或取流URL证明视频成功。

## 7. 关闭业务票的终点

先完成本票业务实现、代表路径与风险验证，再按[共享CASE规则](README.md#共享case闭票与并行交接)交接；票内UI步骤不另加全量客户验收。09必须等08，14必须等08/13；01以后可以并行调查，但共享文件/证据有唯一集成人。

最终16按用户最新口径采用约20条灵活代表业务路径，数量和清单不严格，不要求149条全过。候选库保留，未抽到项标未验收、不阻塞抽样收口；工程验证独立记录，不充业务数量或追加全量客户门禁。缓存集成后复验所选受影响路径，Java-only真实扩展与完整实现要求不变。主验收可不下发控制、不创建生产任务；新窗口、单例成功、请求200和测试全绿都不等于完整交付。
