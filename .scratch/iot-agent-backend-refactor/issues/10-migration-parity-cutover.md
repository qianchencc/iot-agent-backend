# 10: 数据迁移、Parity 与生产切换

**What to build:** 在 Java 版本完成外部契约和业务闭环 parity 后，完成 Runtime 业务事实迁移、只读 shadow 验证、发布回滚和单向生产切换。

**Blocked by:** 06: Action Draft 确认闭环；07: Task、Plan 与 Notification 闭环；08: Company / Office 只读业务闭环；09: Admin 与模型配置兼容

**Status:** implemented-locally (fail-closed tooling; migration and deployment prerequisites pending)

- [ ] Runtime 的会话事件、草案、任务、计划、通知、报告和审计迁移到 Java 事实模型，保留身份、顺序、版本和终态。
- [x] Pi sidecar SQLite session 有独立备份、恢复和磁盘容量检查（本地工具已实现；尚无客户数据库 evidence）。
- [x] 66 paths / 69 operations、认证 Header、错误体和 NDJSON 事件完成自动 parity 检查（从 Runtime OpenAPI/NDJSON contract 推导计数）。
- [x] 只读请求可 shadow 对照；控制、任务 scanner 和通知禁止双跑或双写（本地请求/执行 evidence 门禁）。
- [~] Java / Node / MySQL / Redis 的健康检查、日志、配置和回滚均可验证（cutover gate 只验证人工提供的 passed evidence，不连接或启动部署）。
- [~] 切换失败可以恢复旧 Runtime，且不丢失或重复执行设备控制（已形成单向切换/回滚 runbook 与 executionId 门禁；未获客户部署批准，未执行切换）。

## Comments

- 2026-10-29：新增 `tools/ticket10_local.py`，仅使用 Python 标准库执行本地 fail-closed 检查：Runtime OpenAPI/NDJSON 与 Java 路由/认证证据、Pi SQLite integrity/容量/备份/恢复、JSONL 事实迁移（identity/order/version/terminal）、只读 shadow 边界、control/scanner/notification 唯一执行 ID，以及人工审批/健康/备份/迁移/shadow/execution/logs/config/rollback evidence 的 `cutover-gate`。所有命令输出确定性 JSON；工具不启动进程、不发 HTTP、不运行部署命令。
- 2026-10-29：新增 `docs/migration/ticket-10-local-runbook.md`，记录证据格式、命令、单向切换和回滚顺序。当前 contract gate 应报告 Java 缺少尚未迁移的任务/计划/通知等 Runtime paths；不伪称 66/69 parity、MySQL/Redis、客户上游 payload 或生产切换已验收。
- 尚需前置：Ticket 06/07/08/09 中未完成的业务闭环与客户上游协议、Runtime/Java 全量事实导出和映射、真实 MySQL/Redis 连接及健康 evidence、部署 supervisor/路由审批、人工切换窗口和旧 Runtime 回滚权限。未取得前禁止运行生产切换。
