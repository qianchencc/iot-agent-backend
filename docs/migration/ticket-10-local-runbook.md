# Ticket 10：本地迁移、Parity 与切换门禁

本目录中的门禁只处理本地契约、迁移和切换 evidence。它们**不会**启动 Java/Node、调用 HTTP、执行部署命令、连接云端或切换任何生产路由。命令输出是一行按键排序的 JSON evidence；返回码非零即为 fail-closed。

> 2026-11-06 退役：`tools/ticket10_local.py` 已随 `tools/` 目录删除，本文不再包含可运行的门禁命令；各节保留当时的门禁语义、证据契约与结果描述作迁移档案。本地契约验证的现行入口是 `mvn test`（含 `RuntimeContractGateTest` 行为代表夹具）。

工具 `tools/ticket10_local.py`（已删除）当时运行环境只需 Python 3 标准库。

## Contract / 认证 / NDJSON

从权威 Runtime OpenAPI 推导路径和 operation 数，不把 66/69 写死在实现里；默认仍把 66 paths / 69 operations 作为客户交付门槛。同时检查 security schemes（bearer、tenantcode、admin cookie、admin CSRF）、`ErrorResponse.message`、NDJSON 事件类型/sequence/终态，以及 Java 源码中认证 Header、`source`、统一错误体和 NDJSON media type 的证据。

`src/test/java/ltd/qianc/iotagent/RuntimeContractGateTest.java` 另提供一个不联网的代表性行为夹具：MockMvc 经过实际 Java controller/filter/facade，使用内存存储与 fixture bridge，验证认证缺失、source、状态码、错误码和 NDJSON 序列/终态。它不是 66/69 全量 parity 结果；全量 route inventory 仍是静态证据。

（历史门禁名为 `contract`；命令已随脚本删除。）
当前 Java 工作树仍有任务/计划/通知等未完成路由，因此这条门禁应当明确输出 `status=failed` 和缺失路径；这不是“通过”的假证据。

## MySQL 权威备份与恢复

Java/MySQL 是业务事实与 Pi Session 的唯一在线持久权威。备份必须由 MySQL 运维工具完成，覆盖业务表、Pi Session 表、`storage_migrations` 与一次性 cutover marker；恢复必须先落到独立数据库，再以真实开发者工具验证业务，不能用 HTTP 状态或 SQL 行数替代验收。

旧 Runtime 的 SQLite 文件只作为离线历史证据保留。新后端不读取、不导入、不恢复 SQLite，也不提供 SQLite 容量、备份、恢复或 writer-lease 运维入口。

## 业务事实迁移校验

迁移器不猜表结构，也不伪造 MySQL 已完成。先由 Runtime/Java 各自导出 JSONL，再比较四类不变量：业务身份、记录顺序、版本字段和终态字段。默认字段为 `id`、`sequence`、`version`、`status`；没有任何必需字段、重复身份、记录数差异、身份/顺序变化、版本变化或既有终态变化均失败。

（历史门禁名为 `migration`，输入为 Runtime／Java 导出的 JSONL 对；命令已随脚本删除。）

导出必须覆盖会话事件、Action Draft、Task/Plan/Notification、Report 和 Audit。迁移只允许 append/转换已在映射表中批准的字段；不得用 Redis 或 Pi session 代替业务事实证据。源和目标文件都保持不变。当前仓库没有已约定的 Runtime/Java 导出对，因此实际 import 尚未实现，也不应声称完成。

## 只读 shadow 与禁止双跑

Shadow 请求记录必须是 `GET`/`HEAD`/`OPTIONS`，并拒绝 confirm/cancel/apply/restore/revalidate/selection/parameters/seen/messages 等路径。它不执行控制、scanner 或 notification。

（历史门禁名为 `shadow` 与 `execution-boundary`，输入为已记录的请求／调用 JSONL；命令已随脚本删除。）

当时该工具只验证已记录的本地请求/调用证据；没有 live dispatcher 或 Runtime response comparator，不得声称完成 shadow parity。

## 单向切换与回滚

切换是一次性人工/部署动作，不由本仓库执行。发布人员必须先独立准备并归档以下 JSON evidence，且每份均为 `{"status":"passed"}`：

- `approval`：人类审批，`approved=true`，含 operator；
- `health`：Java、Node、MySQL、Redis 的健康检查，`components` 中四项均为 `ok`/`healthy`/`passed`；
- `backup`：Pi SQLite 和业务事实备份完成且可读；
- `migration`：事实迁移校验通过；
- `shadow`：只读 shadow 差异在批准阈值内；
- `execution`：控制、scanner、notification 单运行门通过；
- `logs`：日志采集/脱敏/留存检查，含 `coverage`；
- `config`：配置快照和必需项检查，含 `configuration`；
- `rollback`：旧 Runtime 可恢复且无双跑，`oldRuntimeRestorable=true`、`doubleRunPrevented=true`。

（历史门禁名为 `cutover-gate`，输入为上述十类 JSON evidence 及人工 operator／window；命令已随脚本删除。）

缺任一文件、任一 evidence 不是 `status=passed`、缺人工 operator/window 或审批不是 `approved=true` 时，门禁拒绝。通过只表示“具备切换资格”，输出仍明确 `gate-only`，不会切流、重启、部署或发送云命令。

获批切换顺序：冻结旧 Runtime 的 control/scanner/notification 写入口 → 最后一次备份 → 确认旧 Runtime 仍是可回滚权威 → 部署并健康检查 Java/Node/MySQL/Redis → 只把外部路由一次性指向 Java → 观察既定窗口。失败顺序：停止新写入、保留 Java evidence、恢复旧 Runtime 路由为唯一权威、仅从备份恢复损坏的本地 Pi SQLite、重放/补偿前先按 `executionId` 去重；禁止新旧两端同时执行 control、scanner 或 notification。任何未知设备结果必须人工核对，不能自动重试。

当前 Ticket 10 仍不能标记为 resolved：任务/计划/通知、只读上游 payload、真实 MySQL/Redis 连接和客户部署审批尚未提供。上述门禁已就绪，但不得据此声称生产 parity、迁移或切换完成。Ticket 21 局部行为夹具与残余缺口见 [`ticket-21-local-evidence.md`](ticket-21-local-evidence.md)。
