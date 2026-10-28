# Ticket 10：本地迁移、Parity 与切换门禁

本目录中的门禁只处理本地文件和本地 SQLite。它们**不会**启动 Java/Node、调用 HTTP、执行部署命令、连接云端或切换任何生产路由。命令输出是一行按键排序的 JSON evidence；返回码非零即为 fail-closed。

工具：[`tools/ticket10_local.py`](../../tools/ticket10_local.py)。运行环境只需要 Python 3 标准库。

## Contract / 认证 / NDJSON

从权威 Runtime OpenAPI 推导路径和 operation 数，不把 66/69 写死在实现里；默认仍把 66 paths / 69 operations 作为客户交付门槛。同时检查 security schemes（bearer、tenantcode、admin cookie、admin CSRF）、`ErrorResponse.message`、NDJSON 事件类型/sequence/终态，以及 Java 源码中认证 Header、`source`、统一错误体和 NDJSON media type 的证据。

```sh
python3 tools/ticket10_local.py contract \
  --runtime-root ../iot-agent-runtime \
  --backend-root .
```

当前 Java 工作树仍有任务/计划/通知等未完成路由，因此这条门禁应当明确输出 `status=failed` 和缺失路径；这不是“通过”的假证据。

## Pi Session SQLite 备份、恢复、容量

`sqlite-disk` 以 SQLite `PRAGMA integrity_check` 和文件系统剩余空间为依据。`sqlite-backup` 从只读源做备份，目标已存在时必须显式 `--force`；备份完成后再次做完整性检查。恢复默认只验证临时副本，只有明确提供 `--apply` 才原子替换目标。

```sh
python3 tools/ticket10_local.py sqlite-disk \
  --path /local/var/pi-session.sqlite \
  --min-free-bytes 1073741824

python3 tools/ticket10_local.py sqlite-backup \
  --source /local/var/pi-session.sqlite \
  --destination /local/backup/pi-session.sqlite \
  --min-free-bytes 1073741824

# 先验证，不改目标；确认人工恢复窗口后才允许 --apply。
python3 tools/ticket10_local.py sqlite-restore \
  --backup /local/backup/pi-session.sqlite \
  --destination /local/var/pi-session-restored.sqlite
python3 tools/ticket10_local.py sqlite-restore \
  --backup /local/backup/pi-session.sqlite \
  --destination /local/var/pi-session-restored.sqlite \
  --apply
```

目标目录和数据库均必须是本地路径。备份 evidence 要进入发布归档；没有可读源、完整性错误或磁盘空间不足时不得继续。

## 业务事实迁移校验

迁移器不猜表结构，也不伪造 MySQL 已完成。先由 Runtime/Java 各自导出 JSONL，再比较四类不变量：业务身份、记录顺序、版本字段和终态字段。默认字段为 `id`、`sequence`、`version`、`status`；没有任何必需字段、重复身份、记录数差异、身份/顺序变化、版本变化或既有终态变化均失败。

```sh
python3 tools/ticket10_local.py migration \
  --source evidence/runtime-facts.jsonl \
  --target evidence/java-facts.jsonl
```

导出必须覆盖会话事件、Action Draft、Task/Plan/Notification、Report 和 Audit。迁移只允许 append/转换已在映射表中批准的字段；不得用 Redis 或 Pi session 代替业务事实证据。源和目标文件都保持不变。

## 只读 shadow 与禁止双跑

Shadow 请求记录必须是 `GET`/`HEAD`/`OPTIONS`，并拒绝 confirm/cancel/apply/restore/revalidate/selection/parameters/seen/messages 等路径。它不执行控制、scanner 或 notification。

```sh
python3 tools/ticket10_local.py shadow \
  --records evidence/shadow-requests.jsonl

python3 tools/ticket10_local.py execution-boundary \
  --records evidence/execution-invocations.jsonl
```

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

```sh
python3 tools/ticket10_local.py cutover-gate \
  --approval evidence/human-approval.json \
  --operator operator-id \
  --window change-window-id \
  --health evidence/health.json \
  --backup evidence/backup.json \
  --migration evidence/migration.json \
  --shadow evidence/shadow.json \
  --execution evidence/execution.json \
  --logs evidence/logs.json \
  --config evidence/config.json \
  --rollback evidence/rollback.json
```

缺任一文件、任一 evidence 不是 `status=passed`、缺人工 operator/window 或审批不是 `approved=true` 时，门禁拒绝。通过只表示“具备切换资格”，输出仍明确 `gate-only`，不会切流、重启、部署或发送云命令。

获批切换顺序：冻结旧 Runtime 的 control/scanner/notification 写入口 → 最后一次备份 → 确认旧 Runtime 仍是可回滚权威 → 部署并健康检查 Java/Node/MySQL/Redis → 只把外部路由一次性指向 Java → 观察既定窗口。失败顺序：停止新写入、保留 Java evidence、恢复旧 Runtime 路由为唯一权威、仅从备份恢复损坏的本地 Pi SQLite、重放/补偿前先按 `executionId` 去重；禁止新旧两端同时执行 control、scanner 或 notification。任何未知设备结果必须人工核对，不能自动重试。


当前 Ticket 10 仍不能标记为 resolved：任务/计划/通知、只读上游 payload、真实 MySQL/Redis 连接和客户部署审批尚未提供。上述门禁已就绪，但不得据此声称生产 parity、迁移或切换完成。
