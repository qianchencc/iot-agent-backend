# Node Agent Engine

边界见根 ADR0004／0005；修改启动、模型参数、观测或会话传输前先读 `docs/configuration-governance.md` 和 `.scratch/admin-console-governance/sidecar-startup-inventory.md`。

- Node 提供通用 Pi 循环、流、取消、工具传输和检查点；业务工具定义、指引、授权、报告、任务和持久化权威归 Java。
- 模型、思考能力和执行预算随 Java 的完整运行定义使用；普通运行与任务检查点使用同一明确契约。存量环境模型 fallback 尚待迁移，不能新增业务默认或把省略模型当静默成功。
- 目标启动使用显式配置文件，不使用 `.env`。Node 只获监听、私有回调与认证、通用资源和必要观测设置；不传 MySQL、Redis、管理员或配置加密根。现有启动器未切换前不擅自重启已有实例。
- Java→Node 与 Node→Java 的认证方向和键名分别登记；日志／健康接口不输出 Token。轮换必须确认双方采用，不能仅保存 Java 配置即报告成功。
- 运行快照整个轮次不变，取消／超时不代表已发出的设备副作用撤销；中断不自动重放未知工具。
- Pi Session 通过 Java 私有 API 持久化，不直连数据库，不重新引入 SQLite、本地JSONL卷或旧会话导入。私有日志不是控制台默认可浏览的业务审计。
- Langfuse 独立于业务审计；若由控制台管理，须有真实传输、显式启停与采用确认。没有链路时记录差距，不能把接收环境变量当成已应用。
