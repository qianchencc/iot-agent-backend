# 独立开发者工具验收环境

日期：2026-11-03。隔离Java/Node/MySQL/Redis及开发者工具已启动，Pi Session已直接切换Java/MySQL，无旧会话导入。业务验收仍未全部完成；以[实时证据与缺口](../pi-session-mysql/acceptance.md)为准，不以启动、接口或测试结果闭票。

## 1. 固定工作区和链路

|用途|本轮位置/地址|
|---|---|
|后端源码|`/Users/qianchen/works/新发展/iot-agent-backend`；`main`；业务/MySQL实现已合入主worktree|
|前端源码|`/Users/qianchen/works/新发展/mp`；`main`；develop.local指向3217，冻结窗口继续通过3215验证|
|前端生成目录|`mp/unpackage/dist/dev/mp-weixin`|
|可见验收窗口|`MySQL Session Frozen - Java 3215`；旧9441/缓存9431不操作|
|自动化|`ws://127.0.0.1:9444`；旧9441及原9431不动|
|Java主合并验证|`127.0.0.1:3217`，主worktree `target/iot-agent-backend-0.1.0.jar`|
|Java冻结验收|`127.0.0.1:3215`，同一主worktree jar、独立业务MySQL|
|Pi sidecar主合并验证|`127.0.0.1:3237`，主worktree `agent-sidecar`|
|Pi sidecar冻结验收|`127.0.0.1:3235`，同一主worktree `agent-sidecar`|
|只读上游代理|`127.0.0.1:3245`→客户真实物联上游；不指向生产Runtime|
|MySQL|独立容器、`127.0.0.1:3338/iot_agent_business`|
|Redis|独立容器、`127.0.0.1:6398/0`，不共享原实例|
|Pi Session/Admin|Pi上下文与业务事实同在本轮MySQL；Node无本地会话卷，私密配置不提交|
|本地视频静态页|`http://127.0.0.1:9419/owner/h5player/index.html`|
|develop Agent/视频API|主合并验证为`http://127.0.0.1:3217/api`；冻结窗口为`http://127.0.0.1:3215/api`；trial/release不变|

主链路：小程序3215→主worktree Java→主worktree Node→Java工具→3245只读上游。业务事实只落本轮独立库，真实用户权限由Java及客户上游核验。3217是主worktree独立入口，3215供冻结窗口复验。

当前主合并验证没有复制旧worktree的jar或Node发布目录：Java直接运行主worktree `target/iot-agent-backend-0.1.0.jar`，Node直接由主worktree `tools/start-sidecar.py`启动；上游、播放器、Admin和独立Docker项目保持不变。
2026-11-03主worktree合并结果：Java主worktree全量`mvn test`通过（345 tests，5 skipped），Node sidecar `npm test`/`npm run build`通过，mp主worktree HBuilderX开发编译成功，真实Office Agent只读路径已在冻结窗口执行并保存`../iot-agent-backend/.scratch/iot-agent-backend-refactor/evidence/MAIN-JAVA-3217/03-office-read-result.png`。蓝牙原生权限弹层在本次重开后再次出现，遮挡了最后一轮答复；不把该轮标记为业务通过，需用户在9444窗口点击“拒绝”后继续只读验收。

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
# 视频页与Java不同源时，必须显式给出来源；未配置时跨域取流保持关闭。
set -a; . ./.env; set +a
/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java \
  -Dagent.video.allowed-origin=http://127.0.0.1:9419 \
  -jar target/iot-agent-backend-0.1.0.jar

# 终端C：从后端worktree启动Pi；白名单启动器不向Node传递DB/Redis/Admin凭据。
set -a; . ./.env; set +a
/Users/qianchen/anaconda3/bin/python tools/start-sidecar.py var/business-preview/sidecar-mysql-ro-v10

# 终端D：从后端worktree启动独立静态页。禁止默认http.server记录带token的URL。
python3 tools/business-preview-player.py --port 9419 --directory '/Users/qianchen/works/新发展/iot-agent-video-player/public'
```

工程回归与真实UI证据独立记录。MySQL官方一致性30/30、Node20条工程通过不等于业务验收；Java全套仍有已记录失败。不得用测试或HTTP状态替代选定真实业务路径。

前端仅编译独立项目，不触发上传或发布：

```sh
/Applications/HBuilderX.app/Contents/MacOS/cli project open --path '/Users/qianchen/works/新发展/mp-agent-business'
/Applications/HBuilderX.app/Contents/MacOS/cli launch mp-weixin --project '/Users/qianchen/works/新发展/mp-agent-business' --compile true
/Applications/wechatwebdevtools.app/Contents/MacOS/cli open --project '/Users/qianchen/works/新发展/mp-agent-business/unpackage/dist/dev/mp-weixin'
```

生成配置中的projectname可设为`Agent Business WT - Java 3215`用于区分窗口，不改AppID或注入业务数据。启动自动化使用已安装的`miniprogram-automator`，projectPath明确指向上述生成目录、端口9441、trustProject=true；已有9441时用connect，不重复launch或关闭原IDE。CLI auto实际使用`auto --project <生成目录> --auto-port 9441 --trust-project`。自动化包当前在`/tmp/mp-automator/node_modules`，若目录被系统清理，在新的本地工具目录正常安装同版本依赖，不修改程序业务依赖。

2026-11-03新增冻结项目：`iot-agent-backend-business/var/business-preview/mp-mysql-v17-node10`，窗口名`MySQL Session Frozen - Java 3215`，自动化9444。只复制编译源码，未复制或注入登录凭据；原9441和缓存9431不关闭。构建摘要见[记录](../pi-session-mysql/evidence/builds-20261103.json)。该窗口当前蓝牙权限弹层需真实点击“拒绝”；SDK取消未生效、系统辅助访问被拒绝，未取得无遮挡结果的路径保持未验收，不点“创建任务”。

## 4. 真正的环境/路由核实

仅启动成功或healthz不够。每次切换构建后：

1. 在新窗口沿真实客户登录及组织/园区链路到首页，检查有物联助手菜单；不复制或注入token，不清原窗口登录。
2. 真实点击物联助手进入仪表盘，再从办公室入口进入Office。等页面路由和可交互控件完成，不在tap后立即对旧page调用waitFor。
3. 在当前应用上下文只读检查编译配置的Agent地址为3215，结合本worktree的脱敏Tomcat访问记录证明实际请求到达。不要读取或保存Authorization等凭据。
4. 真实输入说明书只读问题并发送，观察界面结果、截图；同时确认请求经历本轮Java、Pi和授权上游。接口日志只用于定位链路，业务判定仍看界面。
5. 记录Java jar、sidecar构建文件和miniapp生成文件SHA256以及来源commit/未提交变更，避免旧产物截图被当新构建。

### 视频跨域配置与预检

播放页`http://127.0.0.1:9419`与Java`127.0.0.1:3215`不同源，浏览器的`OPTIONS`预检不会携带`Authorization`/`Tenantcode`，因此Java必须显式允许该播放页来源：

- 属性：`agent.video.allowed-origin`（环境变量`AGENT_VIDEO_ALLOWED_ORIGIN`），值为精确的`scheme://host[:port]`，默认空=跨域关闭；
- 仅覆盖`/api/agent/devices/{id}/video-stream`，只允许`GET`/`OPTIONS`和`Authorization`/`Tenantcode`（及播放器`no-store`取流可能产生的缓存提示头），不启用cookie、不覆盖`/internal`或`/api/admin`，也不接受通配符/带路径的来源（配置错误会启动失败）；
- 实际取流`GET`仍经过`AgentIdentityFilter`，缺少真实`Authorization`+`Tenantcode`仍是401；预检只绕过认证不返回业务数据。

重新构建并带该属性启动后，用不带凭据的本地预检确认工程链路（业务播放仍看前端真实画面）：

```sh
curl -sS -i -X OPTIONS \
  'http://127.0.0.1:3215/api/agent/devices/DEVICE_ID/video-stream?source=office&mode=live' \
  -H 'Origin: http://127.0.0.1:9419' \
  -H 'Access-Control-Request-Method: GET' \
  -H 'Access-Control-Request-Headers: authorization,tenantcode'
```

预期`200`且`Access-Control-Allow-Origin`精确等于播放页来源；同一地址的无凭据`GET`仍应返回`401 UNAUTHORIZED`。旧构建/未配置该属性时预检返回`401`且无CORS头，属预期失败，不能当作播放验收。

早期[OFF-QRY-01](evidence/OFF-QRY-01/README.md)是旧构建/旧时点4盏全关的历史证据。MySQL新会话在2026-11-02真实看到3盏、2开1关；之后重新核验状态又已变化。不得把历史清单与状态硬编码到模型或测试；当前结果见[Pi Session真实验收](../pi-session-mysql/acceptance.md)。

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

视频静态页和API已本地分流；Java已按上述显式来源属性支持该路由的跨域预检与响应头，但新构建的预检烟测、WebView/SDK加载、真实摄像头动帧和录像推进仍未验收，由09完成；不以静态页200、预检200或取流URL证明视频成功。

## 7. 关闭业务票的终点

先完成本票业务实现、代表路径与风险验证，再按[共享CASE规则](README.md#共享case闭票与并行交接)交接；票内UI步骤不另加全量客户验收。09必须等08，14必须等08/13；01以后可以并行调查，但共享文件/证据有唯一集成人。

最终16按用户最新口径采用约20条灵活代表业务路径，数量和清单不严格，不要求149条全过。候选库保留，未抽到项标未验收、不阻塞抽样收口；工程验证独立记录，不充业务数量或追加全量客户门禁。缓存集成后复验所选受影响路径，Java-only真实扩展与完整实现要求不变。主验收可不下发控制、不创建生产任务；新窗口、单例成功、请求200和测试全绿都不等于完整交付。

## 9. 当前已知业务修复预览（2026-11-02 13:49）

- Java 3215 当前进程：`business-preview-java-3215-business-v11`；运行 `var/business-preview/iot-agent-business-fixes-preview-v11-20261102.jar`，SHA256 `0fd0d0ac3f67b2d308f4f5f43280d3b0c767f77b1ad983e803cb3f57d20e8d7c`。
- 这是隔离业务修复预览，不是整个工作区最新源码的完整构建：以先前 v7 完整运行包为基线（SHA256 `9167dd874bf825a7cdf27dfa1e7d2a2ce3f3fa0f3b83784c33391b15546a0b01`），仅替换已独立编译/定向验证的能耗组合、报告模块注册、PDF/XLSX 投影、巡检结果类，并加入必要 PDFBox 依赖、静态字体及许可证。保留原包，未带入并行线程尚在推进的 Java/MySQL Pi Session 迁移，不应作为整体迁移发布包。
- 当前已验证报告真实前端路径及下载文件见 [COMP-ENE-04](evidence/COMP-ENE-04/README.md)。MySQL 会话迁移合并后的完整源构建与集成复验由后续集成收口，不能沿用此包宣称已切换 MySQL Pi Session。
- 用户已取消“逐类遍历全部卡片”的待办；本轮只收口已遇到问题。草案真实控制仍停在安全边界，不解除只读上游代理。
