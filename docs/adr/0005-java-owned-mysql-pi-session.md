# 0005 — Java 统一管理 Pi Session 的 MySQL 持久化

Status: accepted · Date: 2026-11-02 · Implementation: Java/MySQL已实现并在隔离实例切换；业务验收未全部完成（2026-11-03）

客户以 Java 团队长期维护，且已运行 MySQL。Pi Session 继续使用本地 SQLite 会额外引入文件备份、写租约和恢复流程。因此决定由 Java 的独立会话持久化模块通过 MySQL 保存 Pi Session；Node 只保留 Pi 的通用执行、上下文构建与薄存储适配，不直连 MySQL、不持有数据库凭据，也不依赖可写本地持久卷。保留 Pi 0.84.4 的 SessionRepo / SessionStorage 语义，以官方一致性测试验证实际 Node→Java→MySQL 链路，不用前端聊天记录代替引擎上下文。

本决策取代ADR0001、0003、0004中“Pi Session保留SQLite”的交付选择，不撤销Java拥有业务、Node运行Pi的分工。用户明确取消旧会话导入，采用直接切换：先备份，一次性结束旧公开会话；原业务事实不删除，旧SQLite留离线、不读取、不回退。后续启动不重复清空新MySQL会话。Redis不是会话权威。

## 取舍

- 不采用 Node 直连 MySQL：虽少一次私有调用，但数据库迁移、账户和故障处理仍分散在 Node。
- 不采用 Pi Durable 1.x 的 PostgreSQL 社区包：版本和数据库不匹配；参考其契约与恢复经验，不同时升级引擎或引入 Kafka。
- 不自行重写 Agent loop 或上下文算法；Java 只实现存储语义和服务端访问约束。
- 不承诺数据库替换即自动消除并发故障；入场所有权、陈旧写拒绝、提交不确定和工具不重放必须单独验证。
- 使用公开Pi0.84.4 `JsonlSessionRepo`加远程虚拟文件系统，不写本地JSONL；Java以范围事务、追加块、版本、提交回执与writer epoch实现持久化。
- 官方30条一致性通过只证明工程语义。真实业务草案、任务、报告、节点替换与独立库恢复仍以开发者工具截图逐项判断，缺证据不闭票。

规格、迁移范围和票据：[Pi Session MySQL](../../.scratch/pi-session-mysql/README.md)。社区证据：[核查记录](../../.scratch/agent-business-recovery/research-pi-mysql-session.md)。
