# Node Agent Engine

边界见根 ADR0004／0005；修改启动、模型参数、观测或会话传输前先读 `docs/configuration-governance.md` 和 `.scratch/admin-console-governance/sidecar-startup-inventory.md`。

- Node 提供通用 Pi 循环、流、取消、工具传输和检查点；业务工具定义、指引、授权、报告、任务和持久化权威归 Java。
- 运行终态不得等待 Pi Session release；会话释放属于后台清理，必须有界，避免 Java 会话存储异常把 `run.completed` 或 `run.awaiting_confirmation` 卡在心跳流中。
- 启动为常规命令 `node dist/server.js --config <agent-engine.yaml>`（kebab-case YAML 键），不使用 `.env`，无启动器脚本。Node 只获监听、私有回调与认证、通用资源和必要观测设置；不传 MySQL、Redis、管理员或配置加密根。重启已有实例按部署所有者安排执行，不擅自重启。
- Java→Node 与 Node→Java 的认证方向和键名分别登记；日志／健康接口不输出 Token。轮换必须确认双方采用，不能仅保存 Java 配置即报告成功。
- 运行快照整个轮次不变，取消／超时不代表已发出的设备副作用撤销；中断不自动重放未知工具。
- Pi Session 通过 Java 私有 API 持久化，不直连数据库，不重新引入 SQLite、本地JSONL卷或旧会话导入。私有日志不是控制台默认可浏览的业务审计。
- Langfuse 独立于业务审计；若由控制台管理，须有真实传输、显式启停与采用确认。没有链路时记录差距，不能把接收环境变量当成已应用。
