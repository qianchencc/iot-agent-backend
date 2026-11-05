# 固定后端一并启动控制台

2026-11-05。已在实际固定开发会话生效；不是生产发布或系统开机自启。

## 变更

- `tools/start-fixed-backend.py` 管理显式部署清单中的 Java、Node、console。默认私有清单已加入控制台；后端专用自定义清单不配置console时仍只运行Java／Node。
- 重复启动只补起缺失／退出的已配置窗口，保留活跃后端。用 `list-panes` 精确清点窗口；实测 tmux `display-message -t` 对缺失目标可能返回默认活动窗格，不能据其退出码或输出判断console存在。
- Vite开发服务监听 `0.0.0.0:5185`，`strictPort` 禁止静默漂移；npm开发脚本与统一入口使用同一配置。
- 仅代理 `/api/admin`；浏览器Origin与来访Host同源时才规范化到Java的管理源。外部Origin保持原值，Java的管理员会话、密码及CSRF逻辑未修改。Vite不加载 `.env`，不接收Java／Node的数据库、模型或桥接秘密。

操作方式及console清单见[仓根启动说明](../../README.md#固定开发后端tmux-常驻)，配置登记见[维护手册](../../web/admin/docs/maintenance.md)。部署YAML及含凭据的回退文件只在Git忽略的var内保留，0600，不提交。

## 实际验证

- 原来5185没有监听，Chrome打开登录入口得到连接拒绝；旧启动入口只管理两个窗口。
- 实际运行统一入口后新增console；再次运行保持三个PID不变，两次调用均未重启Java／Node。`lsof`观察到console为IPv4 `*:5185`。
- 真实Chrome打开本机登录页，并通过本机局域网地址完成管理员登录，看到实际配置及“无修改”。未保存配置、测试模型连接、创建任务或下发设备控制。
- 未登录 `/api/admin/session` 为401；外部Origin的退出请求为403；已登录LAN页面提交错误CSRF得到 `ADMIN_CSRF_REJECTED`，随后会话仍为200；Vite访问私有部署YAML为403。
- 隔离tmux／临时HTTP组件程序跑通冷启动、补缺console、重复不换PID、仅恢复已退出console、拒绝未知占用端口、组件环境隔离。隔离服务已关闭；该程序仅证明启动器行为，不冒充业务功能验收。
- `npm run build`通过，既有大bundle警告保留；无Java／Node业务实现变更，未重跑业务全套测试。

[真实LAN登录后配置页](lan-config.webp) · [脱敏结果与边界](result.json)

## 双轴审查

**Standards：**三文件固定快照审查发现新管理源配置缺少登记；维护手册已补类型、部署权威、默认值、与Java源的约束、进程加载及错误行为，旧代理路径和端口文档同步纠正。未发现鉴权被放宽的问题。

**Spec：**未发现用户要求缺失。窗口与pane0由入口管理；手动重组或并发启动不新增监督机制。端口预检查针对回环IPv4，LAN特定地址碰撞仍由Vite严格端口策略拒绝，不切换备用端口。

本次LAN地址访问由这台Mac上的Chrome完成，尚非另一台设备的跨机验收。该HTTP开发服务仅用于可信网络；生产仍需HTTPS静态服务与受控反向代理。tmux不提供操作系统重启自启或无人值守崩溃重启。
