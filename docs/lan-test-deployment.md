# 客户局域网测试后端

## 当前状态

2026-11-05：**构建与传包已完成，服务启动、局域网验证和更新入口仍受平台门禁阻断**。已验证SSH主机身份、账号权限、已有服务和容量；构建并上传清洁版本包与Linux amd64运行镜像。实测旧Docker默认seccomp不能正常运行本版本Node工作线程／Java启动，启用服务需先由客户决定平台处理方式。不能把这个状态写成部署成功或前端验收通过。

| 项目 | 已确认值 |
| --- | --- |
| 发布目标 | `192.168.101.182` |
| SSH账号 | `linglv`，使用已有SSH22入口 |
| 客户指定工作目录 | `/opt/linglv` |
| 本应用专用目录 | `/opt/linglv/iot-agent`，0700，不覆盖其他服务 |
| 源码基线 | `2939e46`：已提交业务主线，包含配套新Java／Node配置协议 |
| 首个候选版本 | `20261105-2939e46` |
| 已上传候选目录 | `/opt/linglv/iot-agent/releases/20261105-2939e46` |
| 计划业务入口 | `http://192.168.101.182:3381/api`，**尚未启用** |
| 宿主机 | CentOS7，Linux3.10.0-1160，Docker17.12.0-ce，无Compose插件 |
| 资源 | 4CPU、约16GB内存、工作卷约71GB可用（检查时） |

SSH主机密钥与本机已有known_hosts一致：`SHA256:ys6WeIi5z/Zo/7hsm1swRj84ZKSg1YIbmslTQwxcF8Q`。连接口令不进入Git、文档、发布包或命令行参数；需要认证时从会话／隐藏输入取得，不以关闭主机密钥校验代替认证。

后续后端发布目标以此服务器为准，不以更新Mac本地3381代替服务器发布。平台门禁解除前，候选目录只是准备产物，不是在线版本。

## 不动客户现有服务

当前运行的`gateway`、`nacos`、`redis`、`mysql8`均保留；没有停机、重启、升级宿主机软件，没有复用其数据卷或读取其数据库凭据。没有把本机MySQL业务事实、Redis数据、任务执行凭据、会话或管理员配置库复制到测试机。

本应用上线时使用独立容器网络和独立MySQL／Redis持久目录。只绑定局域网业务入口；Java私有回调、Node、MySQL和Redis不发布宿主机端口。业务网关必须核实为客户指定测试上游，不能凭部署机地址推断路径，也不能默认将测试部署连接到生产业务写入口。部署验证不确认设备控制、创建真实任务或发送通知。

## 实测兼容性门禁

- 所有运行镜像均选择linux/amd64的具体manifest摘要，不使用Mac的arm64镜像，不覆盖客户原有镜像标签。
- 镜像归属`iot-agent-lan/*`；已上传并以Docker17可读格式导入。现有Docker Hub连接失败、本机Docker daemon代理也指向旧地址，因此通过本机可达代理直接拉取校验后的registry内容，**没有改任何Docker daemon代理或重启daemon**。
- Redis7.4.12和Nginx版本检查通过。
- Node22.23.2的`--version`通过，但真正的crypto工作线程调用退出139，报告`uv_thread_create`断言失败；不能用版本输出冒充可运行。
- Java21默认启动超过40秒；仅用于定位的一次性无网络、无数据挂载、非root、去全部capability诊断中，关闭seccomp后Java21.0.13和Node工作线程均通过。诊断容器已经退出，**没有建立关闭seccomp的常驻服务**。
- CentOS7和Docker17均已停止维护。生产基线应使用仍受支持的Linux及Docker。升级现有环境涉及客户正在运行的服务，须获得明确许可和维护窗口；不得自动执行yum全量升级或关闭安全隔离来绕过门禁。

## 发布包与校验

首包包含后端JAR、配套Node的dist和精确package-lock、管理端静态产物、`release.json`及逐文件SHA256；不含工作树未提交实验、数据库、口令、密钥或本机工具状态。Node生产依赖须在目标Linux amd64环境按锁文件安装后才能激活，不能复制Mac的node_modules。

Maven资源打包已排除AGENTS.md／CLAUDE.md／CONTEXT.md，修正发现的`db/mysql/AGENTS.md`嵌入JAR问题。除删除该内部文档外，全部业务class及其余运行资源与596项全套检查构建逐项SHA256一致。

证据、首包摘要和镜像锁定信息见[LAN-DEPLOY-01](../.scratch/lan-test-deployment/evidence/LAN-DEPLOY-01/result.json)。镜像包和包含产物的候选目录保留在服务器应用专用目录，尚无current在线软链或本应用常驻容器。

## 后续发布与回退纪律

1. 确定已提交源码版本，构建同版本Java／Node／管理端；保留测试和产物摘要。未提交内容不得直接当发布基线。
2. 读取并保留服务器现有外置部署YAML，0600、与版本目录分离；确认加密根、数据库和主体域不被替换。模型业务配置以服务器MySQL为权威，不能每次推送都用Mac初始值覆盖。
3. 上传到新的候选目录，校验SHA256，在匹配平台完成Node生产依赖安装、配置和启动检查；不覆盖在线目录、持久数据或客户其他应用。
4. 停止旧版本前实查在途Pi运行、设备执行及临近任务；需要维护时先停止接入并等待在途工作结束，不强制中断设备下发。备份本应用完整MySQL事实及外置配置，备份0700／0600。
5. 配套切换Java和Node，验证实际采用配置版本、身份边界、业务读取和真实小程序界面；HTTP200、事件齐全或测试通过都不替代业务验收。
6. 启动失败时回到保留的上一个程序版本及其兼容配置；不能自动覆盖恢复数据库、重建业务库或删除新事实。涉及不向后兼容的schema／密钥变更另行确定回退方案。

不引入多节点、高压场景或自动扩缩容；目标是单机可追溯发布、数据保留和基本业务闭环。平台与安全策略确定后再落定常驻容器启动／更新入口，本文不把尚未执行的切换记成完成。

## 可复用镜像准备工具

`tools/fetch-container-image.py`支持无需Docker daemon联网的固定摘要镜像导出。它校验manifest、config、压缩层及解压diff ID，产生老Docker可读取的load归档，registry认证token只在内存中，错误不打印可能携带签名的重定向URL。

```sh
python tools/fetch-container-image.py \
  --repository library/node \
  --digest sha256:43aeff40f4afc22e83f7589a2f37e111cff5ca84529571f1c8415bcc5fcc21b2 \
  --tag iot-agent-lan/node:43aeff40f4af \
  --output var/lan-images/node-image.tar.gz
```

需要代理时，仅对这条命令设置实际可达的HTTPS_PROXY／HTTP_PROXY，不修改用户或daemon的全局代理。已用该路径完成五个真实镜像的下载、摘要验证与服务器Docker17导入；镜像导入成功不代表其中的程序已通过运行门禁。
