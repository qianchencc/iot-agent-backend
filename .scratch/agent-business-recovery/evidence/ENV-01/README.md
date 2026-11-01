# ENV-01：独立开发者工具与业务基线环境

日期：2026-11-02。本目录是环境与基线观察证据，不是16张业务票完成证明。

## 已观察事实

- 后端worktree：`/Users/qianchen/works/新发展/iot-agent-backend-business`，业务分支；没有复制缓存分支未提交实现。
- 前端worktree：`/Users/qianchen/works/新发展/mp-agent-business`；独立可见窗口`Agent Business WT - Java 3215`，自动化端口9441，原窗口及9431实例保留。
- Java3215、sidecar3235、只读安全代理3245；独立MySQL3338和Redis6398；独立事实库/卷/Pi会话目录；本地播放器9419。
- 正常客户登录后的同一账号，未注入token/对话/卡片。实际进入菜单、业务总览和Office页面，Java本分支脱敏访问记录见[诊断](diagnostics/java-access.txt)。这些记录只证明路由，不证明业务通过。
- 实际输入「办公室有几盏灯，哪几个开着」并点击发送，界面显示4盏灯且全部为关；清晰结果见[OFF-QRY-01](../OFF-QRY-01/README.md)。没有点击执行/创建任务/发送通知。
- 视频路由调整后HBuilder再次编译成功；9441运行时读取到Java API3215、视频API3215、本地播放器9419。视频真实动帧尚未验收。
- 新Java包构建时跳过测试；sidecar TypeScript构建成功；本轮没有以构建结果或API事件当业务证明。

## 截图与诊断的解释

| 文件 | 真实含义 |
|---|---|
| `05-independent-window.png` | 独立窗口可见的干净首页 |
| `10-independent-window.png` | 独立窗口Office页面，输入真实问题，发送前 |
| `11-office-query-result.png` | 真实业务回答，4灯全关；正式单例证据另存OFF-QRY-01 |
| `01`—`04`截图 | 初始导航与蓝牙权限弹窗；仅环境排障，不作业务通过 |
| `06-visible-business-dashboard.png` | 文件名虽含dashboard，实际画面仍为首页，不作总览成功证据 |
| `07-office-baseline.png` | 自动截图转场滞后，实际仍是总览，不作Office成功证据 |
| `navigation.json` | 首次自动化导航在旧page对象上等待的错误；后续真实路由已观察到，不能归为业务失败 |
| `window-state.json` | 已观察的独立路由状态 |
| `office-read-query.json` | 真实输入、点击与结果时间；DOM文本不包含Markdown答案，答案以结果截图为准 |
| `readiness.json` | 视频路由重编译后实际运行配置与构建记录；不是业务通过证据 |

## 限制

Node现有业务定义仍待票01迁回Java。单例不能证明Node已通用化、全业务健康、视频播放或执行调度完整。本例构建哈希保存在OFF-QRY-01/result.json，后续前端视频路由重编译不能冒用旧证据；最终集成必须重新走案例并取证。

本地只读代理是验收安全工具，不是业务服务、不属于生产Node Agent引擎；不生成业务能力，不能用其替代Java实现。代理隔离smoke验证过查询授权和拒绝写入，但这类结果只计安全工程诊断。

复现、启动、端口和截图方法见[local-preview](../../local-preview.md)。私密.env、会话库、完整服务日志和编译产物不进入提交。
