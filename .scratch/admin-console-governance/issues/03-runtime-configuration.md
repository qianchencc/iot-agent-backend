# 03: 后端参数配置闭环

**What to build:** 管理员在控制台读取、编辑、校验和保存实际可调整的后端参数，并能确认新运行真实采用配置，秘密不会回显。

**Blocked by:** 01 管理员登录与控制台壳

**Status:** completed（2026-11-05；隔离配置保存、冲突、历史恢复、跨页草稿及启动采用已验证）

- [x] 每个可管理字段有唯一默认值、单位、范围、来源和实际消费者 — `AdminSettingCatalog`（GET /config 内嵌 `fields`）是唯一字段描述源：live（含新增 heartbeatMs／scanIntervalMs 热字段）、restart（Langfuse 四字段）、readonly（draftTtlMs/readbackWaitMs/controlTimeoutMs，展示部署属性当前启动值并写明 Spring 键）
- [x] 页面只展示已接通的参数；部署连接和安全根显示为只读或配置文件入口 — 部署三项只读卡片 + 观测采用状态以 sidecar `/healthz` 上报为准（`runtime.observability.adopted`），保存/apply 不算已采用
- [x] 合法修改保存后被新运行采用；在途运行继续使用原快照 — 热字段走 `active()` 快照；心跳每轮读取；任务扫描每周期读取；观测仅 sidecar 启动时拉取一次，受监督重启后采用
- [x] 非法值、并发修订冲突和存储失败不发布，且保留管理员输入和恢复路径 — 冲突保留草稿已有；新增 `CONFIG_READONLY_DEPLOYMENT` 拒绝；种子放宽仅限空白模型三元组，其余非法种子仍 fail closed
- [x] 秘密仅显示已配置状态；替换、清空和留空语义明确，历史和错误不泄露内容 — modelApiKey 只替换不可清空；Langfuse 公钥/密钥留空保持、关闭后可清除；test-model 失败只回通用文案
- [x] Java到Node链路完成隔离验证：Node启动采用修订6，管理端确认真实采用；同一真实账号的办公室／公司只读运行产生实际观测span。随后关闭本地观测并保存修订7、重启采用，保留业务审计。Node34项和Java聚焦62项通过，完整回归的无关动作草案错误另记于05，不报全绿。

## 交付说明（2026-11-04）

- 管理员密码仅部署来源（`admin.password`/`ADMIN_PASSWORD`）；`GET/POST /api/admin/password` 与 `AdminConfigurationFile` 类整体移除，无 shim。
- `scanIntervalMs` 管理默认对齐真实消费者 1000ms（原 15000 无消费者）；库存量部署若已存 15000，接线后按 15000 生效（15s 轮询），需运维知悉。
- 检查点运行固定预算与 Langfuse 无 env 回退为既有事实，现于字段描述与 UI 明示，不再假称可调。
