# 物联 Agent 软件说明书验收集（2026-11-02）

**状态：规划/票据阶段。全表 156 条（151 条说明书业务用例 + 5 条工程门槛）初始均为「未验收」；本分支当前没有任何新的业务通过证据，0 条通过。** 本文件只发布可执行验收矩阵与证据规范，不代表业务实现、部署、生产执行或说明书条目已经达成。`未验收（上游缺口）` 只表示该条要求与上游事实的边界尚待诚实验证，不是通过。

- 验收源：`/Users/qianchen/Downloads/新发展/软件说明书.md`，314行；已逐字冻结为[软件说明书-2026-11-02](../../docs/reference/软件说明书-2026-11-02.md)，SHA-256 `bc7d7601bcb5a8bb385a90623d09977e5c3f89365c78212b3bf5a8f8cade4e22`。行号以该原文为准。
- 旧 24 个 CASE-ID 全部保留并重新落到说明书子条款；新增 132 条补足第 1 章、逐条附录、边界、失败、恢复、缓存和工程门槛。旧矩阵只是历史输入，旧 `待验/未验收` 状态不带入通过。
- 负责票据均为本命名空间新票，链接相对本文件：`issues/NN-*.md`。旧 31/46 票、旧证据、旧 Runtime 截图只作线索，不在本分支，不能作为当前 worktree 的通过证据。
- 执行前置、安全停点、数据来源标签、证据模板见第 0 节；逐条用例见第 1–6 节；工程门槛见第 7 节；说明书覆盖索引见第 8 节；缺口与完成定义见第 9 节。

---

## 0. 执行约定（所有用例共同前提）

### 0.1 基线环境与账号

- **基线 B0（主验收）**：同一真实账号；微信开发者工具加载与本 worktree 后端匹配的真实 mp 构建；链路为 `mp → 新 Java 后端 → 真实 Node sidecar → 真实授权只读上游 + 真实模型`。记录各段构建 ID/commit/端口与启动时间。不得注入 token、消息、卡片或伪造 HTTP/NDJSON 响应；不得用测试、HTTP 200、事件终态、fixture 代替前端业务结果。
- 主验收全程使用同一真实账号，不切换、不清除其登录。除 `ID-05A/ID-05B` 的账号切换专项外，其他用例不得更换身份。
- 账号切换专项只允许在**独立隔离实例**中使用合法的第二授权身份；不登出/不清除原登录，不伪造凭据。第二身份必须具备真实的公司或办公室权限来源。
- 若第二身份、真实摄像头、上游历史数据或报告样例不可得，对应用例保持「未验收（上游缺口）」，不得用合成数据冒充业务通过。

### 0.2 安全停点分类

| 标记 | 含义 | 允许动作 | 禁止动作 |
| --- | --- | --- | --- |
| `R` | 只读 | 查询、查看、滚动、打开详情、下载本地报告 | 任何设备/任务/通知/配置写入 |
| `D` | 草案/确认单 | 生成草案、编辑、搜索、排除、取消、等待过期 | 点击「执行操作/创建任务」触达生产；对生产设备下发 |
| `L` | 本地隔离执行 | 在独立事实库/生产形态适配器上执行，观察本地状态迁移 | 写生产设备、生产任务、生产通知、生产配置；把本地结果写成客户业务通过 |
| `V` | 视频 | 真实授权摄像头直播/回放；保存两帧或录像 | 使用样例视频冒充；落 token、流凭证、带签名短 URL |
| `A` | 账号切换 | 独立隔离实例中的第二合法身份 | 清除原登录；复用原实例会话；伪造身份 |

未标注的动作默认 `R`。所有「执行操作/创建任务/发送通知」若会触达客户生产，一律停在确认单；需要观察执行后语义时使用 `L`，并在证据中标明本地范围。

### 0.3 数据来源标签（证据必须逐条标注）

| 标签 | 含义 | 能证明什么 |
| --- | --- | --- |
| `S-REAL` | 当前真实账号经授权只读访问的客户上游实时数据 | 当前读取/展示链路可用；不含写入 |
| `S-HIST` | 客户真实历史记录（任务、告警、能耗、执行记录） | 历史展示与回查；必须记录来源、租户/区域、原始时间 |
| `S-LOCAL` | 独立本地业务事实库中的可追溯真实历史副本，或真实业务实现结合真实模型/授权只读数据生成的本地事实 | 本地管理状态迁移、展示与恢复；**不能**证明客户设备真实执行；合成fixture另标`S-SYN` |
| `S-SYN` | 纯合成测试数据 | 只能证明界面/算法边界；不得写成业务通过 |
| `S-VIDEO` | 真实授权摄像头实时流或真实录像 | 视频链路与真实动帧；不含样例视频 |

- 历史事实与 `S-LOCAL` 证据必须写明范围：是客户真实历史只读，还是本地副本/隔离事实库；不得把两者混写。
- 对「执行后」场景，观察方式固定为：先在真实历史 `S-HIST` 中定位同租户/区域/时间/设备的记录；或在 `S-LOCAL` 中导入可追溯历史后，观察前端列表、详情、通知、任务状态和本地事实库记录的前后变化。`S-LOCAL` 的变化只标「本地」，不标「客户设备已生效」。
- 完整实际执行实现（生产形态适配器、调度、条件检查、报告生成）必须另有工程证明，见第 7 节 ENG 门槛；「本轮允许不创建生产任务」不等于实现可以缺失。
- **执行后场景的观察方法**（`S-HIST`）：按租户/来源/区域/设备/时间定位真实历史记录，先保存来源查询（脱敏）与原始时间，再在前端任务列表、详情、通知中对照同一记录；只读，不改历史。
- **执行后场景的观察方法**（`S-LOCAL`）：保存导入清单（来源、范围、时间、行数），执行前记录本地事实库与前端状态，执行后再记录任务/通知/列表/详情变化；证据标「本地隔离」，不得写成客户设备已生效。
- 水表与人员统计：说明书明确「甲方未提供接口」（L122、L179）。只验证「如实说明/不伪造」，不标为已实现能力。

| 执行后场景 | 首选来源 | 管理变化如何观察 |
| --- | --- | --- |
| 草案逐台结果 ANY-CTL-03/06/07/09 | `S-HIST` 优先；无则 `S-LOCAL` | 前端逐台标签对照真实来源记录；回读值必须有实测来源，不能用目标填充，数值恰好等于目标不构成错误 |
| 过期与重核 ANY-CTL-04/11、AUD-02 | `S-LOCAL` 短 TTL；或真实过期记录 | 过期前后确认单状态、可执行性、任务数量；重核生成新单且旧单仍失效 |
| 记录留存 ANY-CTL-10、AUD-01 | `S-HIST/S-LOCAL` | 对话、确认单、执行记录三处 ↔ 本地事实库行/历史记录；刷新后仍在 |
| 任务管理 ANY-TASK-04/05/14/16/21/23 | `S-LOCAL` | 列表/日历/详情/通知的前后状态；暂停/跳过/取消/已读；重启后无重复任务 |
| 条件检查 COMP-TASK-06/07/08 | `S-LOCAL` | 检查点表与任务详情：条件真/假/缺数、动作次数、报告/估算依据 |
| 报告 COMP-ENE-18、ANY-TASK-17 | `S-LOCAL` | 报告文件、通知、任务详情三处一致；重启后可查/可下载；不重复生成 |
| 缓存与恢复 SYS-01/02、RES-03 | `S-REAL` 时间戳变更；或 `S-LOCAL` | 变更前后页面值、手动刷新时间、状态灯/通知；本地补数标本地 |

### 0.4 证据目录模板

每条用例固定：`.scratch/agent-business-recovery/evidence/<CASE-ID>/`。表中「证据」列的 `evidence/<CASE-ID>/` 均指此目录。

```
evidence/<CASE-ID>/
  README.md                  # 必填，字段见下
  screenshots/               # 按 01-、02- 顺序，含 UI 结果与时间
  frames/                    # 视频：两个不同时刻的真实帧；或 recording.mp4
  diagnostics/               # 脱敏诊断、链路日志、查询记录、票据链接
```

`README.md` 必填字段：

1. `case_id`、说明书条款与行号、负责票据链接、执行人、执行时间（Asia/Shanghai）。
2. 构建与链路：分支/commit、小程序构建 ID、Java 构建 ID、sidecar 构建 ID、各段地址（脱敏）、启动时间。
3. 身份：脱敏账号标识、租户、角色/来源、关注区域；第二身份单独标注「隔离实例」。
4. 数据出处：`S-REAL/S-HIST/S-LOCAL/S-SYN/S-VIDEO`，原始时间范围、数据表/接口/导入清单摘要。
5. 真实 UI 步骤与输入逐字记录；实际可观察结果；失败时的错误文案/错误码。
6. 限制与豁免：生产写入是否停止、上游缺口、已知未覆盖项；不得把限制写成通过。
7. 诊断链接：前端截图、脱敏日志、后端/票据相对链接。
8. 脱敏检查：不得出现 token、cookie、Authorization、内部密钥、完整手机号/OpenID、带签名的流地址或流凭证。

视频证据必须是同一真实流的**两个不同时刻帧**（可见时间/画面变化）或一段实际录像；两帧相同、暂停帧、样例视频、封面图均不算。截图必须有 UI 上下文和时间；旧失败截图、旧 Runtime 截图只作为历史线索，必须在新证据中注明「线索，不计通过」。

### 0.5 「已下发」术语冲突（必须原样保留的事实）

- §2.5 L106：`已下发` = 指令已被受理，平台暂未返回实测数据，**以现场实际状态为准**。
- 附录 B L305：写成「指令已受理**并执行**，由于部分设备操作缺少回读，具体情况以现场实际状态为准」。
- 附录 C L311：`已下发` = 指令已被平台受理，尚未返回实测数据。

L305 的「并执行」超出了 L106/L311 可证明的范围。`ANY-CTL-08` 及附录 B/C 的映射行只验证前端确实显示「已下发」及无回读说明；**不得据「并执行」宣称设备物理生效，也不得把「已下发」改写成「已完成」**。若产品文案要统一，需另行走文档/产品修正，不在本矩阵内擅自改说明书。

### 0.6 旧证据与旧声明边界

- 原 worktree 的 24 条旧矩阵、旧票据 31/46、旧 `evidence/OFF-CTL-01` 失败截图、旧 Runtime/旧缓存声明，只可作定位线索。旧矩阵实际只有 `OFF-CTL-01` 留有真实失败截图；其余多为接口/待验记录，均不构成通过。
- 原票据 46 的「修复后真实前端复验」未完成，其补丁未进入本分支；不得写成已修复或已通过。
- 旧 `COMP-ENE-*`、`COMP-QRY-02` 的接口结果不是业务验收；旧 `resolved-local`、`ready`、构建/单测通过均不是本矩阵的通过。
- 本 worktree 没有新的业务通过证据。任何「已通过数」必须来自本目录下的新证据并逐条复核；当前为 0。

---

## 1. 第 1 章 产品概述与使用入口

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ENTRY-01 | §1.2 L27-L28 | 开发者工具打开真实小程序 → 完成现有登录 → 首页菜单点「物联助手」 | 进入总览页，不出现空白页/初始化失败；保留登录态 | 基线 B0；主账号；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/ENTRY-01/ | 未验收 |
| ENTRY-02 | §1.2 L29 | 总览页点右下角圆形按钮 | 展开公司助手对话面板；面板身份/范围为公司 | 基线 B0；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/ENTRY-02/ | 未验收 |
| ENTRY-03 | §1.2 L30 | 总览页点顶部标题栏「办公室 ›」 | 进入办公室 Agent；显示办公室区域语境，不误开公司面板 | 主账号具备办公室权限来源；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/ENTRY-03/ | 未验收 |
| ENTRY-04 | §1.1 L13-L20、L22-L23 | 分别从公司圆钮和办公室入口进入；各发一条设备查询 | 两个助手身份/可用范围与说明书表格一致；公司需大致位置，办公室自带区域；仅显示各自授权设备 | 两个权限来源均存在（智慧xx→角色配置；我的办公室→地址元素→新增人员）；`R` | [14](issues/14-identity-references-recovery.md)、[16](issues/16-manual-acceptance-readiness.md) | evidence/ENTRY-04/ | 未验收 |
| DASH-01 | §1.3 L38 | 打开总览，观察顶部状态灯；在有异常和无异常两个时刻各截一次 | 绿色=正常；感叹号=需关注；灯下有文字说明；两种状态均来自真实数据 | 真实上游可提供正常/关注两类状态；无关注数据时记录「无法验证」，不得造 `!`；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/DASH-01/ | 未验收 |
| DASH-02 | §1.3 L39 | 依次点击环境、用电、安全、任务、设备、通知六个功能块 | 六块均存在；每块一行状态+一行数据；点击进入对应详情且不串页 | 真实授权数据；缺数时允许 `—`/`暂不可用`，不得补零；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/DASH-02/ | 未验收 |
| DASH-03 | §1.3 L40 | 点右上角「精简 / 详细」往返切换 | 默认精简；详细展示更多信息板块及设备类别；切换后不丢数据/不重置身份 | 基线 B0；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/DASH-03/ | 未验收 |
| DASH-04 | §1.3 L41 | 点总览底部「直接问助手」 | 打开公司助手对话面板，与圆钮入口身份一致 | 基线 B0；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/DASH-04/ | 未验收 |

---

## 2. 第 2 章 设备检索与控制

### 2.1 设备检索

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-QRY-01 | §2.1 L55 | 公司面板输入「26楼运营办公区的灯现在什么状态」→ 发送 | 返回设备清单与当前开/关状态；无该区域/设备时如实说明，不虚构 | 真实授权 26F 运营办公区；`S-REAL/S-HIST`；`R` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-QRY-01/ | 未验收 |
| COMP-QRY-02 | §2.1 L51-L64、§3.1 L120 | 公司面板输入「查询公司授权范围内的电表」→ 发送 | 返回全授权范围电表清单，含名称/状态/读数；数量与权限范围一致 | 真实授权电表；旧记录只有接口结果，本次必须 UI；`R` | [03](issues/03-company-catalog-mixed-scope.md)、[05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-QRY-02/ | 未验收 |
| COMP-QRY-03 | §2.1 L62 | 输入存在多台匹配的「26楼的灯」或「会议室空调」 | 列出候选设备供选择，显示位置/名称；不自行猜测；选择后继续 | 上游确有 ≥2 台匹配；`R` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-QRY-03/ | 未验收 |
| COMP-QRY-04 | §2.1 L56 | 输入「26楼会议室空调开没开」 | 返回对应空调清单及开/关/在线状态；歧义时列候选 | 真实授权空调；`R` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-QRY-04/ | 未验收 |
| COMP-QRY-05 | §2.1 L64 | 查询「26楼设备」，逐项展开结果 | 每台显示名称、位置、开/关/在线/读数；状态不是模板全绿；读数缺失显式标注 | 真实设备含在线/离线/读数差异；`R` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-QRY-05/ | 未验收 |
| COMP-QRY-06 | §2.1 L51-L64、附录 A L294 | 输入附录原句「三楼东侧的灯现在什么状态」 | 返回三楼东侧授权灯清单与状态；无该位置则如实说明，不跨楼层猜测 | 真实三楼东侧设备或可验证无匹配；`R` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-QRY-06/ | 未验收 |
| COMP-QRY-07 | §1.1 L22-L23 | 公司面板先输入「会议室的灯」，再按追问补充楼层/位置 | 公司助手先追问大致位置，不直接猜设备；补充后精确定位；办公室侧同句不需要位置 | 公司无区域分类、办公室有当前区域；`R` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-QRY-07/ | 未验收 |
| OFF-QRY-01 | §2.1 L60 | 办公室面板输入「办公室有几盏灯，哪几个开着」 | 返回数量与逐灯开/关状态；范围限本人办公区域 | 真实办公室区域授权照明；`R` | [02](issues/02-office-lighting-draft.md) | evidence/OFF-QRY-01/ | 未验收 |

### 2.2 空调控制

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-CTL-01 | §2.2 L73 | 公司面板输入「关掉26楼会议室空调」→ 打开返回的草案 | 草案逐台列出空调、当前状态与预期调整；含拟执行/排除/无需操作数；未下发 | 真实授权空调；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-CTL-01/ | 未验收 |
| COMP-CTL-03 | §2.2 L72、附录 A L295 | 输入「把26楼会议室空调开到 24 度制冷」；再以附录原句「把会议室空调开到 24 度」复跑一次 | 两次目标温度均为24；只有明确指定制冷的指令改变模式，未指定模式时不得擅加制冷；当前值和目标分列，未下发 | 真实授权空调；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-CTL-03/ | 未验收 |
| COMP-CTL-04 | §2.2 L79 | 在草案中长按某台空调卡片，打开自定义调整；依次改电源、模式、风速、温度并保存 | 四个参数均可编辑；保存后卡片/预览反映新目标；无静默重置 | 已有 AC 草案；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-CTL-04/ | 未验收 |
| COMP-CTL-05A | §2.2 L79 | 温度输入 15.9℃（低于 16）并尝试提交 | 无法提交；提示允许范围 16–30℃；草案目标不变；无效指令不下发 | 已有可编辑 AC 草案；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-CTL-05A/ | 未验收 |
| COMP-CTL-05B | §2.2 L79 | 温度输入精确 16℃ 并提交到草案 | 16℃ 被接受并显示为目标值；仍不下发 | 同上；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-CTL-05B/ | 未验收 |
| COMP-CTL-06A | §2.2 L79 | 温度输入精确 30℃ 并提交到草案 | 30℃ 被接受；边界值不被误拒；仍不下发 | 同上；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-CTL-06A/ | 未验收 |
| COMP-CTL-06B | §2.2 L79 | 温度输入 30.1℃（高于 30）并尝试提交 | 无法提交并提示范围；草案保持原目标；不下发 | 同上；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-CTL-06B/ | 未验收 |
| COMP-CTL-07 | §2.2 L81 | 对 ≥2 台同类空调先统一设 25℃/低风，再逐台把其中一台改为 24℃/高风 | 批量修改应用到同类全部；单台覆盖只改该台；其余保持批量值；未下发 | 同类 ≥2 台草案；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/COMP-CTL-07/ | 未验收 |
| OFF-CTL-02 | §2.2 L77 | 办公室面板输入「关下空调」 | 生成当前办公室区域空调草案；逐台目标为关；未下发 | 办公室授权空调；`D` | [04](issues/04-ac-parameter-drafts.md) | evidence/OFF-CTL-02/ | 未验收 |

### 2.3 照明与门禁控制

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-CTL-02 | §2.3 L85 | 输入「关闭这些26楼的各办公区的照明和空调，保留过道区域照明」 | 草案同时含照明+空调；过道照明在排除区；拟执行/排除台数正确；未下发 | 26F 有办公区与过道照明、空调；`D` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-CTL-02/ | 未验收 |
| OFF-CTL-01 | §2.1 L60、§2.3 L83-L87 | 办公室页面输入「把当前位置的灯打开」→ 等待草案 | 显示真实设备/目标状态/确认入口；不出现空「操作范围」或「无法生成控制草案」；未下发 | 原票据 46 的 4 路灯场景可复现；旧失败截图只作线索；`D` | [02](issues/02-office-lighting-draft.md) | evidence/OFF-CTL-01/ | 未验收 |
| COMP-CTL-08 | §2.3 L87 | 公司面板输入「开一下26楼大门」→ 打开确认界面，核对设备清单；不点执行 | 门禁设备与位置在确认单中；未确认前无任何下发；按钮语义为「执行操作」 | 真实门禁授权；`D`；禁止对生产点执行 | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-CTL-08/ | 未验收 |

### 2.4 批量操作与执行确认

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-CTL-09 | §2.4 L91 | 公司面板输入「关掉三楼整层照明」→ 打开预览 | 预览列明拟执行/排除/已符合目标数；按楼层位置分组；未下发 | 三楼整层真实授权照明；`D` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-CTL-09/ | 未验收 |
| COMP-CTL-10 | §2.4 L91 | 输入「这层除了会议室都关灯」 | 会议室出现在排除区且不计入执行；其余本层灯计入；台数一致 | 本层含会议室及非会议室灯；`D` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-CTL-10/ | 未验收 |
| COMP-CTL-11 | §2.4 L93 | 任一批量草案中，逐项核对三个计数与清单 | 拟执行台数、排除台数、无需操作台数均有显示；与实际勾选/排除/已符合集合一致 | 有已开/已关混合设备；`D` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-CTL-11/ | 未验收 |
| COMP-CTL-12 | §2.4 L94 | 在预览中按楼层展开、搜索某设备、勾选/取消、排除一台 | 分组可见；搜索过滤正确；勾选影响执行集合；排除项移出执行计数且不可误执行 | 批量草案 ≥2 楼层；`D` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-CTL-12/ | 未验收 |
| COMP-CTL-13 | §2.4 L95 | 生成草案后点「取消」 | 返回对话；无执行结果、无任务、无下发记录；设备状态不变 | 任一批量草案；可安全点击取消；`D` | [10](issues/10-once-interval-task-execution.md) | evidence/COMP-CTL-13/ | 未验收 |
| COMP-CTL-14 | §2.4 L91、附录 A L295 | 输入附录原句「关掉本层照明」 | 「本层」解析为当前楼层/区域；预览台数与范围正确；未下发 | 当前楼层授权照明；`D` | [03](issues/03-company-catalog-mixed-scope.md) | evidence/COMP-CTL-14/ | 未验收 |

### 2.5 执行结果说明与过期重核

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ANY-CTL-03 | §2.5 L101-L108 | 打开可追溯真实历史执行记录或其本地副本，逐台查看各结果 | 已完成/回读、已下发、部分完成/失败、无需操作分别与真实来源事实一致；缺少某类事实则该分支未验 | `S-HIST`或其`S-LOCAL`副本；`R`；执行内核另验ENG-01 | [10](issues/10-once-interval-task-execution.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ANY-CTL-03/ | 未验收 |
| ANY-CTL-04 | §2.4 L97、附录 B L307 | 生成草案后等待有效期结束（生产 TTL 过长则在隔离环境用短 TTL）→ 再点执行 | 界面显示「核验已过期」；执行被阻止；设备无下发 | `D` 或 `L`；不得为等过期改生产配置 | [10](issues/10-once-interval-task-execution.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ANY-CTL-04/ | 未验收 |
| ANY-CTL-05 | §2.4 L95 | 观察草案从生成到确认的状态；在确认前检查无执行；隔离环境点「执行操作」 | 未确认无任何下发；确认动作是唯一执行入口；确认后进入执行结果 | 生产只到确认前；执行仅 `L` | [10](issues/10-once-interval-task-execution.md) | evidence/ANY-CTL-05/ | 未验收 |
| ANY-CTL-06 | §2.5 L108 | 对已处于目标状态的设备发起同类操作，观察结果与计数 | 显示「无需操作」；未重复下发；预览中无需操作数包含该设备 | `S-LOCAL` 或真实历史已符合状态；`L` | [10](issues/10-once-interval-task-execution.md) | evidence/ANY-CTL-06/ | 未验收 |
| ANY-CTL-07 | §2.5 L107 | 打开真实失败/部分失败历史或其可追溯副本，点击「查看原因」 | 部分完成/失败和逐台原因正确，成功与失败不混写；无真实失败记录则未验收 | `S-HIST`或其`S-LOCAL`副本；`R`；故障注入只计ENG-01工程证据 | [10](issues/10-once-interval-task-execution.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ANY-CTL-07/ | 未验收 |
| ANY-CTL-08 | §2.5 L106、附录 B L305、附录 C L311 | 找到真实「已下发」历史或其可追溯副本，展开说明并核对文案 | 显示已下发且未取得实测回读，不冒充已完成；记录B/C措辞冲突 | `S-HIST`或其`S-LOCAL`副本；`R`；不得制造受理结果 | [10](issues/10-once-interval-task-execution.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ANY-CTL-08/ | 未验收 |
| ANY-CTL-09 | §2.5 L105 | 打开有真实实测回读来源的历史记录或其可追溯副本，查看结果和读数 | 显示已完成/平台回读X℃；X可追溯至实测，不由目标填充；缺实测事实则未验收 | `S-HIST`或其`S-LOCAL`副本；`R`；不为取证下发生产控制 | [10](issues/10-once-interval-task-execution.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ANY-CTL-09/ | 未验收 |
| ANY-CTL-10 | §2.5 L110、§6.2 L279 | 执行后在任务记录/详情回查对话、确认清单、逐台结果与时间 | 三类记录均可回查；包含身份、区域、设备、时间；内容与执行时一致 | `S-HIST` 或 `S-LOCAL`；`R/L` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-CTL-10/ | 未验收 |
| ANY-CTL-11 | §2.4 L97、附录 B L307 | 过期后重新核验（或重新要求助手给草案），再执行 | 生成新的有效期确认单；旧单仍不可执行；无重复任务/重复下发 | `D/L`；生产不执行 | [10](issues/10-once-interval-task-execution.md) | evidence/ANY-CTL-11/ | 未验收 |

---

## 3. 第 3 章 能耗查询与分析

### 3.1 能耗数据

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-ENE-02 | §3.1 L120、§3.2 L126、附录 A L296 | 公司面板输入「本月用电多少」；对照总览/能耗面板月总量 | 单位为「度」；月总量等于按 08:00 计量日切分的日值之和；无单位混用 | 真实电表月数据；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-02/ | 未验收 |
| COMP-ENE-05 | §3.1 L120 | 选择 08:00 边界前后的两个时刻（如 07:59 与 08:00）查询当日/昨日值 | 08:00 前计入前一计量日，08:00 起进入新计量日；界面日期与值同步变化 | 上游有边界附近采样；记录时钟与查询时间；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-05/ | 未验收 |
| COMP-ENE-06 | §3.1 L122 | 询问「水表/用水量」或水表清单 | 如实说明甲方接口未提供水表；不伪造水表、用水量或 0 值 | 上游无接口是已知事实；`R`；不标为已实现 | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-06/ | 未验收（上游缺口） |

### 3.2 能耗监测

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-ENE-07 | §3.2 L126 | 打开总览，查看「用电」块；点击进入能耗面板 | 显示最近一日用电量与环比涨跌幅；点击进入面板后保留同一口径 | 真实最近一日数据；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-07/ | 未验收 |
| COMP-ENE-08 | §3.2 L128 | 能耗面板按日选择任意历史日期，查看当日构成 | 显示当日总量；各电表用电量与占比；占比合计与总量一致（允许显示舍入） | 选定日有分表数据；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-08/ | 未验收 |
| COMP-ENE-09 | §3.2 L129 | 选择已知用电偏高日/触发关注条件，观察总览「用电」块和状态灯 | 块与状态灯显示「注意」，文字说明与耗电事实一致；非全站常亮 | 有真实偏高日或阈值配置；无则记录无法验证；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-09/ | 未验收 |
| COMP-ENE-10 | §3.2 L130、附录 B L306 | 选择已知采样缺失的日期/时段查看总量、构成和状态 | 显示「—」「暂不可用」或「不可计算」；不以 0 或旧值替代；说明原因 | 上游确有缺失窗口；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-10/ | 未验收 |

### 3.3 能耗统计与对比

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-ENE-03 | §3.3 L139 | 选择两期覆盖表数不同的对比，展开逐表与总量 | 按有效表归一/单台平均；缺数显式；不把不可比总量直接相减 | 两期覆盖确有差异；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-03/ | 未验收 |
| COMP-ENE-11A | §3.3 L136 | 能耗面板 →「时间对比」→「本月」 | 本期=本月，对照期按可比区间；显示总量、增减、逐表对比；口径标签明确 | 真实月数据；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-11A/ | 未验收 |
| COMP-ENE-11B | §3.3 L136 | 同入口选「近 30 天」 | 30 天窗口与对照期正确；不缩成 7 天；日期边界可见 | 近 30 天数据完整度已知；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-11B/ | 未验收 |
| COMP-ENE-11C | §3.3 L136 | 同入口选「近 7 天」 | 7 天窗口、对照期和逐表增量正确；不是 30 天截断 | 近 7 天数据；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-11C/ | 未验收 |
| COMP-ENE-11D | §3.3 L136 | 同入口选「单日」，指定日期 | 单日总量与对照日对比；构成/增量可见；日期不偏移 | 选定日与对照日有数据；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-11D/ | 未验收 |
| COMP-ENE-11E | §3.3 L136 | 同入口选「自定义」，填写起止日期 | 自定义区间按用户输入生效；起止边界/包含关系明确；不静默改 30 天 | 区间内有数据；记录输入原文；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-11E/ | 未验收 |
| COMP-ENE-12 | §3.3 L134 | 对同一周期分别用界面「时间对比」和对话查询 | 两条路径 totals、增减幅度、逐表数据一致；scope/time 相同 | 同一账号/同一数据源；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-12/ | 未验收 |
| COMP-ENE-13 | §3.3 L137 | 输入「分析一下2026年8月至9月的几周的公司能耗，并给出节能计划，以及需要注意的部门」 | 返回按周/期拆分、节能计划、需注意部门；部门/结论有数据依据，不虚构 | 2026-08 至 09 真实历史；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-13/ | 未验收 |
| COMP-ENE-14 | §3.3 L139 | 打开任一对比结果，核对本期/对照期总量、增减幅度、逐表排序 | 本期与对照期总量、增减幅度均显示；逐表按增量排序；顺序可核对 | 至少 3 块表有差异；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-14/ | 未验收 |
| COMP-ENE-15 | §3.3 L139 | 选择两期覆盖表数/层级不同的对比 | 自动按单台平均计算；覆盖差异显式；不直接比较不可比的总量 | 两期表数/层级确有差异；`R` | [06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-15/ | 未验收 |

### 3.4 能耗报表

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-ENE-04 | §3.4 L143 | 输入「公司本月的能耗报告」→ 报告卡片点「下载 PDF / 导出表格」 | 报告卡片生成；PDF 与表格两个入口均可用；文件实际生成 | 真实月数据；`R`；下载到本地，不写生产 | [07](issues/07-evidenced-reports-exports.md) | evidence/COMP-ENE-04/ | 未验收 |
| COMP-ENE-16A | §3.4 L143 | 在报告卡片点击「下载 PDF」并打开文件 | PDF 可打开、非空白、含期间/总量/分表；页数与内容可读 | 同上；`R` | [07](issues/07-evidenced-reports-exports.md) | evidence/COMP-ENE-16A/ | 未验收 |
| COMP-ENE-16B | §3.4 L143 | 点击「导出表格」并用表格软件打开 | XLSX/表格可打开；列含表名/期间/用量/口径；数据与卡片一致 | 同上；`R` | [07](issues/07-evidenced-reports-exports.md) | evidence/COMP-ENE-16B/ | 未验收 |
| COMP-ENE-17 | §3.4 L145 | 打开报告正文，核对口径、各表依据与覆盖 | 含统计口径说明、各电表数据依据、覆盖情况；可存档/分发（下载后仍可读） | 真实数据；`R` | [07](issues/07-evidenced-reports-exports.md) | evidence/COMP-ENE-17/ | 未验收 |
| COMP-ENE-18 | §3.4 L146 | 用日报/月报周期任务生成一次（隔离调度），在通知中心查收 | 到期生成报告；通知可见；打开后可查看/下载；周期规则与报告对应 | `L` 本地隔离调度；不创建生产任务/通知 | [07](issues/07-evidenced-reports-exports.md)、[11](issues/11-recurring-plans-reports.md)、[13](issues/13-task-management-results-notifications.md) | evidence/COMP-ENE-18/ | 未验收 |
| COMP-ENE-19 | §3.4 L147 | 公司任务中要求「报表附件」；办公室同请求复跑 | 公司任务生成报告附件；办公室不可用/明确拒绝；不跨助手伪造 | `R/L`；不写生产 | [07](issues/07-evidenced-reports-exports.md) | evidence/COMP-ENE-19/ | 未验收 |

### 3.5 对话查询能耗

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-ENE-01 | §3.5 L153 | 输入「统计一下最近能耗情况」，再追问「最近一周」 | 首轮返回数据卡/图表；追问沿用同一 scope 并切到最近一周，不重置成别的区域/月 | 真实数据；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-01/ | 未验收 |
| COMP-ENE-20 | §3.5 L156 | 点击能耗数据卡片/图表，查看明细；再追问一个相关问题 | 卡片可交互、明细可打开；追问保留上下文；不出现死卡片/空白图表 | 真实数据；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-20/ | 未验收 |
| COMP-ENE-21 | §3.5 L154 | 输入「分析本月公司夜间用电，找出夜间用电较多的部门和位置，给出节能建议」 | 明确夜间 20:00–08:00；给出部门/位置排序与建议；不把夜间偷换为全天 | 真实月数据含分表/部门映射；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-21/ | 未验收 |
| COMP-ENE-22 | §3.3 L136-L139 | 自定义任意区间并指定粒度（日/周/月），如「2026年8月至9月按周」 | 区间与粒度按输入生效；结果标明粒度；不只支持 30 天/不静默改口径 | 区间历史数据；`R` | [05](issues/05-energy-statistics-night-analysis.md)、[06](issues/06-energy-comparisons.md) | evidence/COMP-ENE-22/ | 未验收 |
| COMP-ENE-23 | §3.5 L154 | 查询夜间边界：19:59、20:00、07:59、08:00 附近的归属 | 夜间窗口固定 20:00–08:00；边界归属可解释；夜间结果不等于全天结果 | 边界附近有采样；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-23/ | 未验收 |
| COMP-ENE-24 | §3.3 L139、附录 A L296 | 输入附录原句「上周哪天用电最多」 | 返回上周用电最高日的日期与数值；排序依据可见；不虚构日期 | 真实上周数据；`R` | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-24/ | 未验收 |
| COMP-ENE-25 | §3.2 L130、附录 B L306 | 对已知缺失数据先观察「—」，待上游恢复/本地补数后手动刷新 | 恢复后显示真实值；不保留 0/旧值；刷新前后差异有来源和时间 | `S-REAL` 恢复或 `S-LOCAL` 补数；`R`；标范围 | [05](issues/05-energy-statistics-night-analysis.md) | evidence/COMP-ENE-25/ | 未验收 |

---

## 4. 第 4 章 视频监控与安全告警

### 4.1 实时视频查看

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-VID-01 | §4.1 L166、附录 A L297 | 公司面板输入「看看大堂摄像头」 | 返回大堂摄像头直播卡片；点击后即点即开；显示真实画面；无此设备/无权限时如实说明 | 真实授权大堂摄像头；`V`；两帧/录像 | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-01/ | 未验收 |
| COMP-VID-04 | §4.1 L166 | 在设备清单/台账选择监控设备 → 视频块点「看直播」 | 从非对话入口也能打开同一摄像头直播；身份/权限一致 | 真实授权摄像头；`V` | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-04/ | 未验收 |
| COMP-VID-05 | §4.1 L168 | 选择一个离线/状态未知的摄像头打开直播 | 如实显示离线/未知；不无限转圈、不展示旧帧冒充直播 | 上游确有离线设备；`V` | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-05/ | 未验收 |
| COMP-VID-06 | §4.1 L169 | 主账号查询/尝试打开权限范围外摄像头；确需第二身份时另按 ID-05A 的隔离实例单列 | 设备不出现或被明确拒绝；不返回可播放流/短 URL | 存在越权设备；`R`；主账号不登出；第二身份不入主验收 | [09](issues/09-real-video-live-playback.md)、[14](issues/14-identity-references-recovery.md) | evidence/COMP-VID-06/ | 未验收 |

### 4.2 录像回放

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-VID-02 | §4.2 L173 | 输入「回放昨天下午两点前后26楼运营办公区的录像」→ 卡片点「查看这段录像」→ 填起止时间 → 播放 | 表单回填合理窗口；播放真实录像；时区为 Asia/Shanghai；超保留期拒绝（旧案要求超三天）；响应 no-store | 真实授权录像；`V`；两帧或录像 | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-02/ | 未验收 |
| COMP-VID-03 | §4.2 L173、附录 A L297 | 输入附录原句「回放昨天 14 点东侧的录像」→ 选候选/打开卡片 → 填起止 → 播放 | 解析昨天 14 点与东侧；多摄像头时列候选；播放窗口正确 | 真实东侧录像；`V` | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-03/ | 未验收 |
| COMP-VID-07 | §4.2 L173 | 在回放表单分别输入 start>end、超保留期、跨时区/错误时区时间 | 非法区间被拒绝并提示；合法时间按 Asia/Shanghai 解释；不静默改时间 | 真实录像；`V` | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-07/ | 未验收 |
| COMP-VID-08 | §4.1 L168、§4.2 L173 | 打开直播/回放后返回列表，再重新进入；检查地址栏/DOM/网络中的流地址 | 短 URL/流凭证不长期持久化；离开后释放或过期；再进重新获取；证据不含凭证 | 真实摄像头；`V`；禁落 token/签名 URL | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-08/ | 未验收 |
| COMP-VID-09 | §4.1 L168、§4.2 L173 | 对同一真实流间隔 ≥5 秒截两帧，或录一段连续视频 | 两帧画面/时间明显不同，证明真实动帧；不是暂停帧/封面/样例视频 | 真实授权摄像头；`S-VIDEO`；`V` | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-09/ | 未验收 |
| COMP-VID-10 | §4.1 L168 | 直播/回放中短暂断网再恢复 | 自动重连或明确报错并可重试；不把冻结旧帧当直播；无凭证泄露 | 真实摄像头；`V` | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-10/ | 未验收 |

### 4.3 人员统计

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-VID-11 | §4.3 L179 | 询问人员统计/人数，或寻找相关入口 | 如实说明甲方未提供接口；不展示人数、不伪造统计图 | 上游无接口是事实；`R`；不标为已实现 | [09](issues/09-real-video-live-playback.md) | evidence/COMP-VID-11/ | 未验收（上游缺口） |

### 4.4 安全告警台账

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-SAFE-01 | §4.4 L185-L187、附录 A L298 | 输入「最近有哪些消防告警」 | 返回告警记录，含时间与设备名称；类别/来源可辨；无告警时如实为空 | 真实授权告警历史；`R` | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-01/ | 未验收 |
| COMP-SAFE-03 | §4.4 L187、附录 A L298 | 输入「整理一下最近一周的告警并生成报告」 | 生成最近一周汇总与报告附件；统计口径/覆盖清晰；不声称已处置 | `S-HIST`；`R`；不写生产通知 | [08](issues/08-safety-ledger-event-reference.md)、[07](issues/07-evidenced-reports-exports.md) | evidence/COMP-SAFE-03/ | 未验收 |
| COMP-SAFE-04A | §4.4 L185 | 台账筛选「烟感」+ 日期区间 | 仅显示烟感；区间边界正确；时间/设备名完整 | 真实烟感告警；`R` | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-04A/ | 未验收 |
| COMP-SAFE-04B | §4.4 L185 | 台账筛选「燃气」+ 日期区间 | 仅显示燃气；不混入烟感/水浸 | 真实燃气告警；`R` | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-04B/ | 未验收 |
| COMP-SAFE-04C | §4.4 L185 | 台账筛选「水浸」+ 日期区间 | 仅显示水浸；若历史无记录则如实空 | 真实水浸告警或可验证空集；`R` | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-04C/ | 未验收 |
| COMP-SAFE-05 | §4.4 L183 | 总览「安全」块查看最近告警 → 点进完整台账 → 设日期区间 | 总览显示最近记录；台账完整；筛选前后计数/列表变化正确 | 真实告警；`R` | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-05/ | 未验收 |
| COMP-SAFE-06 | §4.4 L186 | 打开一条告警详情，核对事件来源、当前设备名称、安装位置 | 三项齐全；未核验信息明确标注；位置/名称来自事件或设备事实，不编造 | `S-HIST`；`R` | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-06/ | 未验收 |

### 4.5 告警关联监控查询

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-SAFE-02 | §4.5 L191-L193 | 打开告警详情→点「按当前位置查找监控」 | 同地点/同楼层候选真实且授权；展示关联直播和原告警±15分钟回放卡片，无候选诚实说明 | `S-HIST`告警+真实摄像头；`R`；播放与09组合验证 | [08](issues/08-safety-ledger-event-reference.md)、[09](issues/09-real-video-live-playback.md) | evidence/COMP-SAFE-02/ | 未验收 |
| COMP-SAFE-07 | §4.5 L191-L194 | 告警详情点「引用到对话」→编写消息→发送 | 带入告警上下文、检索摄像头，提供关联直播/回放入口和处理建议，不声称已通知 | `S-HIST`；`R` | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-07/ | 未验收 |
| COMP-SAFE-08 | §4.5 L191-L193 | 核对同一告警录像窗口，分别打开关联直播与回放入口 | 回放窗口为原事件±15分钟；两个入口播放真实画面，或将相应无源分支明确记为缺口，不以卡片替代播放 | 真实告警与摄像头/录像；`V`；无源保持未验 | [08](issues/08-safety-ledger-event-reference.md)、[09](issues/09-real-video-live-playback.md) | evidence/COMP-SAFE-08/ | 未验收 |

### 4.6 安全约束

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-SAFE-09 | §4.6 L198 | 在告警详情/视频页寻找控制入口，尝试触发设备写入 | 无写入入口；告警/视频只读；数据与设备状态不被修改 | 真实告警/视频；`R`；不得发送生产写请求 | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-09/ | 未验收 |
| COMP-SAFE-10 | §4.6 L199 | 问「已经通知物业了吗/处理了吗」，观察答复 | 只给核实与跟进建议；明确未通知/未处置；不编造工单/通知状态 | 任意告警上下文；`R` | [08](issues/08-safety-ledger-event-reference.md) | evidence/COMP-SAFE-10/ | 未验收 |

---

## 5. 第 5 章 定时与自动化任务

### 5.1 设备清单

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| DEV-01 | §5.1 L208-L211 | 总览点「设备」→ 查看全量清单；切详细模式，依次点环境/能耗/安全/空调/照明/门禁/视频 | 全量清单可见；按类型与安装位置分组；名称/位置/可执行操作统一展示；类别筛选正确 | 真实授权设备；`R` | [03](issues/03-company-catalog-mixed-scope.md)、[16](issues/16-manual-acceptance-readiness.md) | evidence/DEV-01/ | 未验收 |

### 5.2 一次性定时任务

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| OFF-TASK-01 | §5.2 L217 | 办公室面板输入「明天 18:00 关闭本层照明」→ 打开确认单 | 确认单列明明天 18:00、本层照明范围、关闭动作；确认前无设备动作、未创建 | 真实办公室授权照明；`D` | [10](issues/10-once-interval-task-execution.md) | evidence/OFF-TASK-01/ | 未验收 |
| OFF-TASK-04 | §5.2 L218 | 输入「今天下午到明早，每隔半小时查看一次机房温度」 | 生成一次性区间任务确认单：起止、30 分钟间隔、查看动作；不误建周期计划 | 机房温度设备授权；`D` | [10](issues/10-once-interval-task-execution.md) | evidence/OFF-TASK-04/ | 未验收 |
| OFF-TASK-05 | §5.2 L220 | 在确认单点「放弃」；返回任务列表核对 | 不创建任务；任务列表数量/内容不变；无通知 | 任一任务确认单；`D` | [10](issues/10-once-interval-task-execution.md) | evidence/OFF-TASK-05/ | 未验收 |
| OFF-TASK-06 | §5.2 L220 | 核对确认单时间/范围/动作 → 在隔离环境点「创建任务」→ 回任务管理取消 | 确认单字段完整；创建后任务可见、可取消；取消后不再执行 | `L` 本地隔离；生产不创建 | [10](issues/10-once-interval-task-execution.md)、[13](issues/13-task-management-results-notifications.md) | evidence/OFF-TASK-06/ | 未验收 |

### 5.3 周期计划

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| OFF-TASK-02 | §5.3 L224 | 输入「每个工作日 17:30 关闭26楼运营办公区的灯」→ 打开周期确认单 | 规则=工作日 17:30；范围=26F 运营办公区灯；动作=关；显示下一次执行 | 真实 26F 照明；`D` | [11](issues/11-recurring-plans-reports.md) | evidence/OFF-TASK-02/ | 未验收 |
| OFF-TASK-03 | §5.3 L224、附录 A L299 | 输入附录原句「每天 18:00 关本层照明」 | 识别为每日周期（非仅一次）；规则/下次执行/范围正确；未创建 | 当前层授权照明；`D` | [11](issues/11-recurring-plans-reports.md) | evidence/OFF-TASK-03/ | 未验收 |
| OFF-TASK-07 | §5.3 L224、§3.4 L146 | 输入「每天上午 9 点生成公司昨日能耗简报」→ 打开确认单 | 规则=每日 09:00；产物=昨日能耗简报；下一次执行正确；报告入口指向通知/报告 | 真实能耗数据；`D`；本地生成用 `L` | [11](issues/11-recurring-plans-reports.md)、[07](issues/07-evidenced-reports-exports.md) | evidence/OFF-TASK-07/ | 未验收 |
| ANY-TASK-08 | §5.3 L228 | 在周期安排对某计划点「启用/关闭」各一次 | 状态切换持久；关闭后不再生成；重新启用后下次执行恢复 | `S-LOCAL` 计划；`L` | [11](issues/11-recurring-plans-reports.md) | evidence/ANY-TASK-08/ | 未验收 |
| ANY-TASK-09 | §5.3 L228 | 对周期计划当前展示的下一次发生点「跳过本次」 | 只跳过该次且不会执行，计划仍启用；显示的下一次实际执行后移到后续有效发生，其他规则与历史不变 | `S-LOCAL`；`L` | [11](issues/11-recurring-plans-reports.md) | evidence/ANY-TASK-09/ | 未验收 |
| ANY-TASK-10 | §5.3 L228 | 对某周期点「取消全部」并确认 | 计划删除；无后续生成；已执行结果仍保留可查 | `S-LOCAL`；`L` | [11](issues/11-recurring-plans-reports.md) | evidence/ANY-TASK-10/ | 未验收 |
| ANY-TASK-07 | §5.3 L228、§5.5 L246、附录 A L300 | 输入「暂停每天的巡检计划」→ 在周期安排定位该计划 → 暂停 | 仅暂停目标巡检计划；后续不再生成；其他计划不受影响；可恢复启用 | `S-LOCAL` 计划；`L`；不暂停真实计划 | [11](issues/11-recurring-plans-reports.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-07/ | 未验收 |

### 5.4 条件触发任务

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| COMP-TASK-03 | §5.4 L234 | 输入「每 30 分钟检查26楼会议室空调，低于26℃ 就调到 26℃」→观察追问→补充未来起止时间→打开确认单 | 未给窗口先澄清；补齐后按30分钟展开，显示窗口、检查点、条件及动作，未创建 | 真实会议室空调；`D`；不自行发明结束时间 | [12](issues/12-conditional-ac-inspections.md) | evidence/COMP-TASK-03/ | 未验收 |
| COMP-TASK-04 | §5.4 L232-L236、附录 A L299 | 输入附录原句「每半小时检查机房空调」 | 助手澄清是仅检查/告警还是条件动作；不擅自生成控制；间隔识别为 30 分钟 | 机房空调；`D`；不得猜动作 | [12](issues/12-conditional-ac-inspections.md) | evidence/COMP-TASK-04/ | 未验收 |
| COMP-TASK-05A | §5.4 L236 | 条件任务选 15 分钟间隔 | 检查点每 15 分钟展开；数量/起止正确；不按 30/60 分钟 | 隔离计划；`D/L` | [12](issues/12-conditional-ac-inspections.md) | evidence/COMP-TASK-05A/ | 未验收 |
| COMP-TASK-05B | §5.4 L236 | 条件任务选 60 分钟间隔 | 检查点每 60 分钟展开；数量/起止正确 | 隔离计划；`D/L` | [12](issues/12-conditional-ac-inspections.md) | evidence/COMP-TASK-05B/ | 未验收 |
| COMP-TASK-06 | §5.4 L236 | 打开有真实条件真/假检查点的历史巡检记录或其可追溯副本，查看逐点结果 | 条件、实测和结果一致；满足才动作，不满足记未触发；缺相应事实的分支未验 | `S-HIST`或其`S-LOCAL`副本；`R`；合成真/假仅作ENG-03 | [12](issues/12-conditional-ac-inspections.md) | evidence/COMP-TASK-06/ | 未验收 |
| COMP-TASK-07 | §5.4 L236 | 打开存在真实缺读数/读取失败检查点的历史或其可追溯副本 | 缺数/无法判断和原因可见，不把缺失当0或满足条件；无对应事实则未验收 | `S-HIST`或其`S-LOCAL`副本；`R`；注入缺数只计ENG-03 | [12](issues/12-conditional-ac-inspections.md) | evidence/COMP-TASK-07/ | 未验收 |
| COMP-TASK-08 | §5.4 L238 | 对空调巡检任务生成检查报告与节能估算，展开计算依据 | 报告与估算可见；估算附计算依据/假设；无依据不编造数字 | `S-LOCAL` 执行结果；`L` | [12](issues/12-conditional-ac-inspections.md)、[13](issues/13-task-management-results-notifications.md) | evidence/COMP-TASK-08/ | 未验收 |

### 5.5 任务查看与管理

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| OFF-TASK-08 | §5.5 L248 | 办公室端进入「区域工作台」，查看当日时间轴 | 按区域展示；含任务时间、安排人、执行状态；按时间排序 | 真实/本地当日任务；`R` | [13](issues/13-task-management-results-notifications.md) | evidence/OFF-TASK-08/ | 未验收 |
| OFF-TASK-09 | §5.5 L263 | 在区域工作台查看异常任务标记 | 异常任务区域标记为黄点；与任务状态一致；点击可定位 | 本地构造/历史异常任务；`R` | [13](issues/13-task-management-results-notifications.md) | evidence/OFF-TASK-09/ | 未验收 |
| ANY-TASK-11 | §5.5 L244 | 任务中心 →「全部任务」，跨多日查看 | 按日期分组；待执行与执行结果分列；无遗漏/重复 | `S-HIST` 或 `S-LOCAL`；`R` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-11/ | 未验收 |
| ANY-TASK-12 | §5.5 L245 | 任务中心 →「日历」月视图；找异常日并点击 | 月视图完整；异常任务橙点；点击日期显示当日任务 | 有异常任务；`R` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-12/ | 未验收 |
| ANY-TASK-13 | §5.3 L227、§5.5 L246 | 任务中心 →「周期安排」 | 列出规则、下一次执行、最近结果与启停操作；字段非空/未执行说明 | `S-LOCAL` 计划；`R` | [11](issues/11-recurring-plans-reports.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-13/ | 未验收 |
| ANY-TASK-14 | §5.5 L250 | 打开某任务详情 → 点取消并确认 | 任务取消；后续不再执行；已执行结果不受影响 | `S-LOCAL`；`L` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-14/ | 未验收 |
| ANY-TASK-15 | §5.5 L250 | 在任务详情点引用追问，输入问题并发送 | 对话带入任务上下文；回答基于该任务范围/结果；不丢任务 ID/时间 | `S-LOCAL/S-HIST`；`R` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-15/ | 未验收 |
| ANY-TASK-05 | §5.5 L250 | 打开有执行记录的任务详情，展开阶段、逐设备结果、操作记录、报告 | 四类信息均可查看；字段完整；与通知/报告一致 | `S-HIST` 或 `S-LOCAL`；`R` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-05/ | 未验收 |
| ANY-TASK-04 | §5.5 L242-L250、§5.6 L254 | 在隔离环境完成一轮：任务列表 → 详情 → 暂停 → 跳过 → 取消 → 通知未读/已读 | 六项操作均生效且状态一致；取消不撤销已执行；通知打开后已读；无重复任务 | `S-LOCAL` 执行与管理；`L`；生产不写入 | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-04/ | 未验收 |
| ANY-TASK-06 | §5.5 L250、附录 A L300 | 输入「取消明天 18 点的关灯任务」→ 在任务中心定位该任务 → 确认取消 | 只命中明天 18:00 的关灯任务；其他任务不变；取消后不再执行；已执行结果保留 | `S-LOCAL` 任务；`L`；不取消真实历史任务 | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-06/ | 未验收 |

### 5.6 任务结果与报告

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ANY-TASK-16 | §5.6 L254 | 隔离执行一个任务完成 → 打开通知中心 → 点开结果通知 | 结果消息进入通知；未读标识存在；打开后自动已读；内容对应该任务 | `S-LOCAL` 执行；`L` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-16/ | 未验收 |
| ANY-TASK-17 | §5.6 L255 | 打开历史任务报告，刷新/重启后再次进入；下载 PDF、导出表格、引用追问 | 报告长期保存；两种下载可用；引用追问保留报告上下文 | `S-HIST/S-LOCAL`；`R` | [13](issues/13-task-management-results-notifications.md)、[07](issues/07-evidenced-reports-exports.md) | evidence/ANY-TASK-17/ | 未验收 |
| ANY-TASK-18 | §5.6 L256 | 对照第 2 章结果，检查任务详情中的状态词与原因 | 使用已完成/已下发/部分完成/失败并附原因；不混用成功/完成；已下发不写成生效 | `S-HIST/S-LOCAL`；`R` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-18/ | 未验收 |

### 5.7 自动化任务的安全约束

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ANY-TASK-19 | §5.7 L260、§4.6 L200 | 对一次性/周期/条件各发起一次创建，观察是否必须先出确认单 | 三类任务创建前均有确认单；无确认不能创建；确认是唯一入口 | 生产停在确认；`D` | [10](issues/10-once-interval-task-execution.md)、[11](issues/11-recurring-plans-reports.md)、[12](issues/12-conditional-ac-inspections.md) | evidence/ANY-TASK-19/ | 未验收 |
| ANY-TASK-20 | §5.7 L261 | 构造含权限外设备的任务范围，观察候选/确认单 | 范围外设备被排除或拒绝；任务仅覆盖授权设备/动作；无静默越权 | 存在越权设备；`D`；不创建生产任务 | [14](issues/14-identity-references-recovery.md) | evidence/ANY-TASK-20/ | 未验收 |
| ANY-TASK-21 | §5.7 L262 | 在隔离执行中对运行中/已建立任务分别暂停、跳过、取消 | 三种动作有效；取消后不再执行；已执行结果保留；无重复执行 | `S-LOCAL`；`L` | [10](issues/10-once-interval-task-execution.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-21/ | 未验收 |
| ANY-TASK-22 | §5.7 L263 | 构造一条异常任务，检查日历橙点、区域黄点、通知 | 三处提示均出现且指向同一任务；状态一致；可人工处理 | `S-LOCAL` 异常任务；`R` | [13](issues/13-task-management-results-notifications.md) | evidence/ANY-TASK-22/ | 未验收 |
| ANY-TASK-23 | §5.3 L226 | 隔离调度到点，观察当日执行任务生成；重启后再次观察 | 到点生成当天任务；下一次时间更新；恢复后不重复生成 | `S-LOCAL` 调度；`L`；工程细节见 ENG-02 | [11](issues/11-recurring-plans-reports.md) | evidence/ANY-TASK-23/ | 未验收 |

---

## 6. 第 6 章 身份、权限、审计与交付

### 6.1 身份与权限

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ID-01 | §6.1 L273-L274 | 用当前身份查看/查询一个权限外任务详情、设备或历史 | 按当前授权裁剪；不泄露其他用户/隐藏设备/任务内容；无越权卡片 | 存在越权对象；`R` | [14](issues/14-identity-references-recovery.md) | evidence/ID-01/ | 未验收 |
| ID-02 | §6.1 L274 | 在同一 Tenantcode 下用第二合法用户（独立实例）查询事实 | 两个用户事实隔离；互不相见对话/设备/任务；无串租户 | `A`；第二身份独立实例；不登出原账号 | [14](issues/14-identity-references-recovery.md) | evidence/ID-02/ | 未验收 |
| ID-03 | §6.1 L275 | 同一账号分别进出公司/办公室，查看设备、任务、历史 | Office/Company 来源隔离；同名入口不混数据；切换后来源标签正确 | 同一账号有两入口权限；`R` | [14](issues/14-identity-references-recovery.md) | evidence/ID-03/ | 未验收 |
| ID-04 | §6.1 L273 | 对照小程序原有设备清单与 Agent 可见清单；尝试查询越权设备 | Agent 不新增用户原本没有的设备权限；可见集合≤原权限；越权拒绝 | 原小程序清单可对照；`R` | [14](issues/14-identity-references-recovery.md) | evidence/ID-04/ | 未验收 |
| ID-05A | §6.1 L275 | 在独立隔离实例登录第二合法身份，按说明书要求切换账号并观察 | 对话记录、设备清单、任务、未读通知按新身份重新加载；原登录不被清除 | `A`；不登出/不清原实例；不伪造凭据 | [14](issues/14-identity-references-recovery.md) | evidence/ID-05A/ | 未验收 |
| ID-05B | §6.1 L275 | 第二身份进入后查看历史对话、设备、任务、未读通知 | 看不到第一身份的对话/设备/任务/未读；不出现旧缓存串数据 | `A`；第二身份真实授权；`R` | [14](issues/14-identity-references-recovery.md) | evidence/ID-05B/ | 未验收 |
| ID-06 | §6.1 L275、附录 B L304 | 刚切换账号/区域后立即查设备清单，再查一个旧位置设备 | 清单按新身份/区域更新；找不到时说明权限/刚切换/命名原因；不展示旧清单 | `A`；`R` | [14](issues/14-identity-references-recovery.md) | evidence/ID-06/ | 未验收 |
| ID-07 | §6.1 L273、附录 B L304 | 分别验证「越权设备」「命名不符」「刚切换未刷新」三种找不到设备情形 | 三种解释可区分且真实；不把越权说成设备故障；不虚构设备 | `S-REAL/S-LOCAL`；`R` | [14](issues/14-identity-references-recovery.md) | evidence/ID-07/ | 未验收 |
| ID-08 | §6.1 L274 | 对两个不同身份发送同一句设备查询/控制草案 | 设备集合按身份不同，各自只含本人权限；无并集/串设备 | `A`；两个合法身份；`R/D` | [14](issues/14-identity-references-recovery.md) | evidence/ID-08/ | 未验收 |
| ID-09 | §1.1 L20、§6.1 L273 | 核对公司角色配置与办公室地址人员两个权限来源对应的可见范围 | 两入口权限来源不同；范围与来源一致；Agent 不自行扩大 | 两个来源均配置；`R` | [14](issues/14-identity-references-recovery.md) | evidence/ID-09/ | 未验收 |

### 6.2 安全审计与风险控制

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| AUD-01 | §6.2 L279 | 完成一次只读查询/草案/隔离执行后，刷新并回查对话、确认单、执行记录 | 三类记录留存；含身份、时间、范围、动作；刷新/重启后仍可查 | `S-HIST/S-LOCAL`；`R/L` | [13](issues/13-task-management-results-notifications.md) | evidence/AUD-01/ | 未验收 |
| AUD-02 | §6.2 L280、§2.4 L97 | 持过期确认单尝试执行；再重新核验 | 过期单不可执行；无绕过入口；重核后新单可执行；无重复 | `D/L`；生产不执行 | [10](issues/10-once-interval-task-execution.md)、[13](issues/13-task-management-results-notifications.md) | evidence/AUD-02/ | 未验收 |

### 6.3 使用入口与交付、缓存刷新、恢复

| CASE-ID | 说明书 | 真实输入与 UI 步骤 | 可观察结果 | 数据前提 / 安全停点 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| ENTRY-05 | §6.3 L284 | 仅通过小程序内「物联助手」入口使用；确认无需安装独立应用 | 入口内嵌可用；无独立安装步骤；退出重进仍可用 | 基线 B0；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/ENTRY-05/ | 未验收 |
| SYS-01 | 附录 C L314 | 页面列表在 1 分钟内重复进入，观察缓存值；再手动刷新；用带时间戳的上游/本地变更核对 | 约 1 分钟内可返回缓存；手动刷新后显示最新；刷新前后可解释；不永久陈旧 | `S-REAL` 或 `S-LOCAL` 时间戳变更；`R`；外部 cache#50 验收不代替本条 | [16](issues/16-manual-acceptance-readiness.md) | evidence/SYS-01/ | 未验收 |
| SYS-02 | §3.2 L130、附录 B L306 | 选择缺数页面确认「—」；上游/本地补数后手动刷新 | 缺数时显式；恢复后显示真实值；不以 0/旧值填充；状态灯恢复 | `S-REAL` 恢复或 `S-LOCAL` 补数；`R`；标范围 | [05](issues/05-energy-statistics-night-analysis.md)、[16](issues/16-manual-acceptance-readiness.md) | evidence/SYS-02/ | 未验收 |
| RES-01 | §6.2 L279、§6.1 L275 | 对话/草案进行中断开连接，重进页面；必要时取消进行中请求 | 历史可回放；无重复消息/草案/任务；取消生效；不产生半条执行 | `R/D`；不得在生产执行中制造中断 | [14](issues/14-identity-references-recovery.md) | evidence/RES-01/ | 未验收 |
| RES-02 | §6.2 L279、§5.7 L262 | 在隔离环境让 sidecar/后端在草案阶段失败，恢复后重试 | 保留原 scope/时间/动作；明确报错不假装成功；重试不重复下发/建任务 | `S-LOCAL`；`L`；不故障注入生产 | [14](issues/14-identity-references-recovery.md) | evidence/RES-02/ | 未验收 |
| RES-03 | §3.2 L129-L130 | 制造/等待总览短暂不可用，恢复后手动刷新 | 不可用时明确提示，不显示旧值冒充当前；恢复后数据/状态灯更新 | `S-REAL/S-LOCAL`；`R` | [16](issues/16-manual-acceptance-readiness.md) | evidence/RES-03/ | 未验收 |

---

## 7. 工程门槛（不是业务验收，不得计入通过数）

这些用例证明「完整实际执行实现」存在且可隔离验证，防止以「允许不创建生产任务」为由交付 fixture/failclosed-only。它们可以在隔离环境执行，不要求生产写入；结果只标「工程门槛通过/失败」，不能替代第 1–6 节的真实前端业务证据。

| CASE-ID | 目标 | 最小验证 | 安全边界 | 票据 | 证据 | 初始状态 |
| --- | --- | --- | --- | --- | --- | --- |
| ENG-01 | Java生产形态执行适配器，而非仅Fixture/FailClosed | 隔离工程验证逐设备结果、失败/部分失败注入、原因及重复提交幂等；故障夹具标`S-SYN`不计业务通过 | 无生产控制出口；`L` | [10](issues/10-once-interval-task-execution.md) | evidence/ENG-01/ | 未验收（工程门槛） |
| ENG-02 | 调度生成/恢复/不重复 | 周期到点生成当日任务；重启/中断后恢复；同一计划同一时点仅一条 | `S-LOCAL`；`L` | [10](issues/10-once-interval-task-execution.md)、[11](issues/11-recurring-plans-reports.md) | evidence/ENG-02/ | 未验收（工程门槛） |
| ENG-03 | 条件检查点引擎 | 15/30/60 分钟展开；条件真/假/缺数；动作仅在满足点；重启无重复 | `S-LOCAL`；`L` | [12](issues/12-conditional-ac-inspections.md) | evidence/ENG-03/ | 未验收（工程门槛） |
| ENG-04 | 报告事实持久化与导出 | 报告生成后重启仍可查；PDF/XLSX 可打开；同一请求不重复生成 | `S-LOCAL`；`R/L` | [07](issues/07-evidenced-reports-exports.md)、[13](issues/13-task-management-results-notifications.md) | evidence/ENG-04/ | 未验收（工程门槛） |
| ENG-05 | Java-only 业务能力/Node 源码与构建不改 | 在 Java 增加/变更一个真实业务定义，Node 源码与构建产物不变，真实小程序可观察 | 不改 Runtime/Node；`R` | [01](issues/01-java-business-generic-engine.md) | evidence/ENG-05/ | 未验收（工程门槛） |

---

## 8. 说明书覆盖索引（逐节逐条）

### 8.1 章节与小节

| 说明书位置 | 条款 | 对应用例 |
| --- | --- | --- |
| §1.1 L9-L23 | 产品定位、两助手范围、权限来源、公司/办公室定位差异 | ENTRY-04、COMP-QRY-07、ID-03、ID-09 |
| §1.2 L25-L32 | 登录、首页菜单、公司圆钮、办公室标题入口 | ENTRY-01、ENTRY-02、ENTRY-03 |
| §1.3 L34-L43 | 精简默认、状态灯、六功能块、精简/详细、底部直接问 | DASH-01、DASH-02、DASH-03、DASH-04 |
| §2.1 L49-L64 | 检索示例、授权范围、歧义候选、状态展示 | COMP-QRY-01、COMP-QRY-02、COMP-QRY-03、COMP-QRY-04、COMP-QRY-05、COMP-QRY-06、COMP-QRY-07、OFF-QRY-01 |
| §2.2 L66-L81 | 空调示例、逐台草案、长按四参数、16–30 边界、批量/逐台 | COMP-CTL-01、COMP-CTL-03、COMP-CTL-04、COMP-CTL-05A、COMP-CTL-05B、COMP-CTL-06A、COMP-CTL-06B、COMP-CTL-07、OFF-CTL-02 |
| §2.3 L83-L87 | 照明/门禁示例、混合保留、门禁确认后下发 | COMP-CTL-02、COMP-CTL-08、OFF-CTL-01 |
| §2.4 L89-L97 | 批量示例、三类计数、分组/勾选/搜索/排除、执行/取消、过期 | COMP-CTL-09、COMP-CTL-10、COMP-CTL-11、COMP-CTL-12、COMP-CTL-13、COMP-CTL-14、ANY-CTL-04、ANY-CTL-05、ANY-CTL-11 |
| §2.5 L99-L112 | 四种结果、记录留存 | ANY-CTL-03、ANY-CTL-06、ANY-CTL-07、ANY-CTL-08、ANY-CTL-09、ANY-CTL-10 |
| §3.1 L118-L122 | 度、08:00 边界、无水表接口 | COMP-ENE-02、COMP-ENE-05、COMP-ENE-06 |
| §3.2 L124-L130 | 最近一日/环比、任意日构成、偏高注意、缺数显式 | COMP-ENE-07、COMP-ENE-08、COMP-ENE-09、COMP-ENE-10、COMP-ENE-25、SYS-02 |
| §3.3 L132-L139 | 两种入口一致、五种时间选项、逐表排序、归一 | COMP-ENE-03、COMP-ENE-11A、COMP-ENE-11B、COMP-ENE-11C、COMP-ENE-11D、COMP-ENE-11E、COMP-ENE-12、COMP-ENE-13、COMP-ENE-14、COMP-ENE-15、COMP-ENE-22 |
| §3.4 L141-L147 | 报告/PDF/表格、口径依据覆盖、周期报表、仅公司附件 | COMP-ENE-04、COMP-ENE-16A、COMP-ENE-16B、COMP-ENE-17、COMP-ENE-18、COMP-ENE-19 |
| §3.5 L149-L158 | 同一对话入口、能耗卡/图、可交互/追问、夜间分析 | COMP-ENE-01、COMP-ENE-20、COMP-ENE-21、COMP-ENE-23 |
| §4.1 L164-L169 | 直播入口、即点即开、离线如实、权限范围 | COMP-VID-01、COMP-VID-04、COMP-VID-05、COMP-VID-06 |
| §4.2 L171-L175 | 回放描述、查看录像、起止时间、播放 | COMP-VID-02、COMP-VID-03、COMP-VID-07、COMP-VID-08、COMP-VID-09、COMP-VID-10 |
| §4.3 L177-L179 | 人员统计无接口 | COMP-VID-11 |
| §4.4 L181-L187 | 安全块、三类台账、日期筛选、详情字段、对话查询/报告 | COMP-SAFE-01、COMP-SAFE-03、COMP-SAFE-04A、COMP-SAFE-04B、COMP-SAFE-04C、COMP-SAFE-05、COMP-SAFE-06 |
| §4.5 L189-L194 | 同地点/楼层检索、±15分钟、关联直播/回放卡片及真实播放、引用对话 | COMP-SAFE-02、COMP-SAFE-07、COMP-SAFE-08 |
| §4.6 L196-L200 | 只读、不声称通知/处置、人工确认暂停取消 | COMP-SAFE-09、COMP-SAFE-10、ANY-TASK-19、ANY-TASK-21 |
| §5.1 L204-L211 | 全量设备、详细类别、分组、名称/位置/操作 | DEV-01 |
| §5.2 L213-L220 | 一次性示例、确认单、创建/放弃、创建后取消 | OFF-TASK-01、OFF-TASK-04、OFF-TASK-05、OFF-TASK-06 |
| §5.3 L222-L228 | 周期示例、自动生成、周期安排、启停/跳过/取消全部 | OFF-TASK-02、OFF-TASK-03、OFF-TASK-07、ANY-TASK-08、ANY-TASK-09、ANY-TASK-10、ANY-TASK-07、ANY-TASK-23 |
| §5.4 L230-L238 | 条件示例、15/30/60、仅满足执行、报告/估算依据 | COMP-TASK-03、COMP-TASK-04、COMP-TASK-05A、COMP-TASK-05B、COMP-TASK-06、COMP-TASK-07、COMP-TASK-08、ANY-TASK-23 |
| §5.5 L240-L250 | 全部任务、日历、周期安排、区域工作台、详情/取消/引用 | ANY-TASK-05、ANY-TASK-06、ANY-TASK-11、ANY-TASK-12、ANY-TASK-13、ANY-TASK-14、ANY-TASK-15、OFF-TASK-08、OFF-TASK-09 |
| §5.6 L252-L256 | 通知已读、报告持久/下载/引用、状态一致 | ANY-TASK-16、ANY-TASK-17、ANY-TASK-18、COMP-ENE-18 |
| §5.7 L258-L263 | 创建前确认、授权范围、暂停/跳过/取消、异常提示 | ANY-TASK-19、ANY-TASK-20、ANY-TASK-21、ANY-TASK-22 |
| §6.1 L269-L275 | 登录确权、不增权限、按用户隔离、切换重载 | ID-01、ID-02、ID-03、ID-04、ID-05A、ID-05B、ID-06、ID-07、ID-08、ID-09 |
| §6.2 L277-L280 | 对话/确认/执行留存、人工确认、过期不可执行 | AUD-01、AUD-02、ANY-CTL-10、RES-01、RES-02 |
| §6.3 L282-L284 | 小程序内嵌交付 | ENTRY-05 |
| §7 L398-L410（非说明书） | 工程门槛，不计业务通过 | ENG-01、ENG-02、ENG-03、ENG-04、ENG-05 |

### 8.2 附录 A 逐条指令

| 附录行 | 使用目的与指令原文 | 对应用例（输入逐字复跑） |
| --- | --- | --- |
| A L294 | 查询设备：「三楼东侧的灯现在什么状态」 | COMP-QRY-06 |
| A L295 | 控制设备：「把会议室空调开到 24 度」 | COMP-CTL-03（附录变体输入） |
| A L295 | 控制设备：「关掉本层照明」 | COMP-CTL-14 |
| A L296 | 查看能耗：「本月用电多少」 | COMP-ENE-02 |
| A L296 | 查看能耗：「上周哪天用电最多」 | COMP-ENE-24 |
| A L297 | 查看监控：「看看大堂摄像头」 | COMP-VID-01 |
| A L297 | 查看监控：「回放昨天 14 点东侧的录像」 | COMP-VID-03 |
| A L298 | 查询告警：「最近有哪些消防告警」 | COMP-SAFE-01 |
| A L298 | 查询告警：「整理最近一周的告警并生成报告」 | COMP-SAFE-03 |
| A L299 | 创建任务：「每天 18:00 关本层照明」 | OFF-TASK-03 |
| A L299 | 创建任务：「每半小时检查机房空调」 | COMP-TASK-04 |
| A L300 | 管理任务：「取消明天 18 点的关灯任务」 | ANY-TASK-06 |
| A L300 | 管理任务：「暂停每天的巡检计划」 | ANY-TASK-07 |

### 8.3 附录 B 逐条 FAQ

| 附录行 | 说明书表述 | 验证用例 |
| --- | --- | --- |
| B L304 | 查找不到设备：越权/刚切换/命名有误 | ID-06、ID-07、ID-04 |
| B L305 | 已下发但状态未变：已受理并执行、缺少回读、以现场为准 | ANY-CTL-08（保留「并执行」与 L106/L311 冲突，不宣称物理生效） |
| B L306 | 能耗「—/不可计算」：采样缺失或未加载 | COMP-ENE-10、COMP-ENE-25、SYS-02 |
| B L307 | 核验已过期：重核/重新要求智能体 | ANY-CTL-04、ANY-CTL-11、AUD-02 |

### 8.4 附录 C 逐条用语

| 附录行 | 术语 | 验证用例 |
| --- | --- | --- |
| C L311 | 「已下发」= 受理、无实测 | ANY-CTL-08 |
| C L312 | 「平台回读」= 实测数据 | ANY-CTL-09 |
| C L313 | 「确认单」= 执行/创建前清单，确认后才操作 | ANY-CTL-05、COMP-CTL-01、OFF-TASK-05、ANY-TASK-19 |
| C L314 | 页面列表约 1 分钟缓存，可手动刷新 | SYS-01、SYS-02、RES-03 |

### 8.5 旧 24 ID 对照

`OFF-QRY-01`、`COMP-QRY-01`、`COMP-QRY-02`、`COMP-CTL-01`、`OFF-CTL-01`、`COMP-CTL-02`、`ANY-CTL-03`、`ANY-CTL-04`、`COMP-ENE-01`–`04`、`COMP-VID-01`–`02`、`COMP-SAFE-01`–`02`、`OFF-TASK-01`–`02`、`COMP-TASK-03`、`ANY-TASK-04`–`05`、`ID-01`–`03` 全部保留在上述表中，初始状态均为未验收。旧 `COMP-QRY-01` 的「无该目录如实说明」、`COMP-ENE-01/02` 的「仅有接口结果」、`OFF-CTL-01` 的失败截图，分别按新说明书条款重写/重跑；历史结果不构成通过。

---

## 9. 缺口、冲突与完成定义

### 9.1 明确的未验收/上游缺口

- **水表**：说明书 §3.1 L122 明说甲方接口未见水表。`COMP-ENE-06` 只验「如实说明」；不得写成水表能力已实现
- **人员统计**：§4.3 L179 明说甲方暂未提供接口。`COMP-VID-11` 只验「如实说明」；不得展示伪造人数
- **生产物理执行**：本轮不执行客户设备/任务/通知写入；结果展示优先使用可追溯真实历史或本地副本，实际执行内核在ENG门槛隔离验证。两类证据均不声称本轮客户现场设备已生效。
- **第二身份**：`ID-02/05A/05B/08` 需要合法第二身份和独立隔离实例；不具备时保持未验收，不伪造账号，不清原登录
- **真实视频源**：`COMP-VID-*` 需要真实授权摄像头/录像；样例视频、封面、旧截图不算
- **历史数据**：`S-HIST` 需真实历史；`S-LOCAL` 需可追溯导入；两者不能混写，fixture 不能证明客户业务
- **报告周期生成**：需要隔离调度或真实历史；不创建生产任务/通知
- **维护配置演示**：说明书正文未直接写出维护台操作，但票 [15](issues/15-java-maintenance-config.md) 是票 16 的前置。业务指令/模型/预算/限额的保存、生效与忙碌/重启处理必须在票 15 的隔离演示中证明；本矩阵不新增说明书业务用例，也不以该演示替代任何前端业务条目。

### 9.2 「完成」定义

一条用例只有在以下全部满足时才可改为「通过」：

1. 按表中真实 UI 步骤和输入执行，使用基线 B0/声明身份；
2. 可观察结果符合说明书该行，且不是 fixture/接口 200/单测/旧截图；
3. 证据目录齐全，含构建/链路/时间/脱敏身份/source/区域/数据出处/限制；
4. 视频含两不同时刻真实帧或录像；控制/任务结果标明 `S-LOCAL` 或 `S-HIST` 范围；
5. 未发生生产写入；本轮没有任何生产写入许可。未来若用户另行要求，须另设任务和授权，不能用本矩阵作为放行依据。「已下发」不被写成物理生效。

全表通过数当前为 **0 / 156**。任何汇总数字必须由逐条证据复核生成，不得按章节估算。

### 9.3 后续执行顺序建议

1. 先跑第 1 章入口/总览与第 6 章身份隔离，确认基线、账号、权限来源；
2. 第 2 章只读检索 → 草案/边界/过期/确认单；执行结果只进隔离；
3. 第 3 章能耗（含 08:00/夜间/归一/报表）与第 4 章台账/视频；
4. 第 5 章任务创建停在确认单，管理与通知用隔离/历史；
5. 最后跑第 7 节工程门槛，并逐条回填证据与状态；未满足的缺口继续保留，不以 66/69 对照或构建通过替代。
