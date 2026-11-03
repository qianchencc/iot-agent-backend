# ENG-05 Admin configuration maintenance — real GUI evidence

Scope: engineering evidence that the existing admin console (Vite `http://127.0.0.1:5185` →
Java admin backend `127.0.0.1:3215`, profile `local`) performs a real, persisted

* optimistic-locking revision bump,
* next-round hot-field snapshot update,
* audit record containing field names only,
* conflict handling between two managed pages in one TaskSpace.

It is **not** a customer/business acceptance verdict, and it does not prove that a new
mini-program turn actually consumed the new budget (parent verifies that next turn).

## Environment

| item | value |
| --- | --- |
| browser | ego-browser, TaskSpace name `Java agent business maintenance` (space id 3), profile `Default` |
| page | `p1` → `http://127.0.0.1:5185/login` → `/` (left open for parent) |
| second page | `p2` (same space, agent-created for the conflict step, then closed) |
| auth | `ADMIN_PASSWORD` read **inside** the Node/ego process from the ignored `.env` and typed into the normal login form; never printed, logged, passed in argv or written to any file |
| deployment | local, `127.0.0.1:3215`, version 0.1.0, `Asia/Shanghai` |

## Baseline before the edit (revision #2)

* model id `GLM-5.3-Flash`, base URL `https://proxy.qianc.ltd/v1`
* model API-key replacement field: empty value, placeholder `已配置，留空保持当前密钥`, state `已配置`
* office instructions: revision-2 text preserved (97 chars, `你是面向当前账号的办公室物联助手…`)
* budget: `maxAgentTurns=6`, `maxToolCalls=10`, `runTimeoutMs=300000`
* state panel: 已保存修订 #2 / 下一轮生效修订 #2, 配置差异 无
* audit: #2 `办公助手角色与业务指令` (operator), #1 `初始配置` (bootstrap)

Evidence: `01a-before-limits.png`, `01b-before-state.png`, `01c-before-audit.png`.

## Exercised steps (all through the visible GUI)

1. Typed `12` into the `单轮最大工具调用次数` spinner (was `10`) and blurred the field.
   Footer switched to `未保存修改 1 项`, revision tag stayed `#2`.
   Evidence: `02a-draft-limits.png`.
2. Clicked `保存到下一轮`. Toast `配置已保存`; header tag `修订 #3`.
   * state panel: 已保存修订 `#3` / 下一轮生效修订 `#3`, 配置差异 `无`, budget `6 回合 / 12 次工具 / 300000 毫秒`
   * audit: new row `#3 / operator / 最大工具调用次数` (label only)
   Evidence: `03a-after-limits.png`, `03b-after-state.png`, `03c-after-audit.png`.
3. Reloaded `p1` (F5). Still `修订 #3`, spinner `12`, state consistent `#3/#3`.
   Model id, base URL, office instruction text and the empty secret field with its
   `已配置` placeholder were unchanged; deployment block unchanged.
   Evidence: `04a-reload-limits.png`, `04b-reload-state.png`.
4. Concurrency (optional step, exercised): `p2` in the same space saved
   `maxToolCalls=13` → revision `#4` (`05a-second-page-saved-13.png`). `p1`, still holding
   revision `#3` with an unsaved draft, clicked save and was rejected with
   `配置已被其他操作修改，保存已暂停；你的本地草稿仍然保留` (`05b-conflict-warning.png`),
   header `配置冲突：请先载入最新修订`. Rebase via `载入最新修订（保留草稿）` gave
   header `修订 #4 / 未保存 1`, spinner `11`, conflict banner gone (`05c-rebased-draft.png`).
   Then set `12` and saved → revision `#5` and closed `p2`.
   Evidence: `05d-restored-limits.png`, `05e-restored-state.png`, `05f-restored-audit.png`.
5. Final reload of `p1`: `修订 #5`, spinner `12`, state `#5/#5`, 配置差异 `无`,
   audit #5/#4/#3 all `最大工具调用次数`, #2 still `办公助手角色与业务指令`.
   Evidence: `06a-final-reload-limits.png`, and after the evidence copy an extra reload
   `06b-live-confirm-state.png` (header `修订 #5`, spinner `12`, state `#5/#5`, secret field empty)
   matching `GET /api/admin/config 200` at 09:45:29 in the same log.

## Real MySQL check (metadata only)

Read inside the business-preview MySQL container using credentials sourced from the
ignored `.env` into shell variables. Selected only `revision, changed_fields_json, actor,
changed_at` — never `settings_ciphertext`, never any key material:

```
5 | ["maxToolCalls"]     | operator  | 2026-11-02 09:39:53
4 | ["maxToolCalls"]     | operator  | 2026-11-02 09:39:38
3 | ["maxToolCalls"]     | operator  | 2026-11-02 09:38:56
2 | ["officeInstructions"] | operator | 2026-11-02 04:25:52
1 | []                  | bootstrap | 2026-11-02 01:55:56
```

This confirms the revisions shown by the UI are stored rows in
`admin_configuration_revisions` and that only field names are recorded.

## Proof the saves hit the actual 3215 process

Tomcat access log `var/business-preview/access/business-access.2026-11-02.log`
(pattern `%t %m %U %s`) for the same window:

```
[02/Nov/2026:09:38:57 +0800] PATCH /api/admin/config 200   <- 10 -> 12, revision #3
[02/Nov/2026:09:39:22 +0800] GET   /api/admin/config 200   <- p2 load
[02/Nov/2026:09:39:34 +0800] GET   /api/admin/config 200   <- p1 stale view
[02/Nov/2026:09:39:38 +0800] PATCH /api/admin/config 200   <- p2 saves 13, revision #4
[02/Nov/2026:09:39:41 +0800] PATCH /api/admin/config 409   <- p1 stale save rejected (conflict)
[02/Nov/2026:09:39:49 +0800] GET   /api/admin/config 200   <- rebase (load latest keeping draft)
[02/Nov/2026:09:39:53 +0800] PATCH /api/admin/config 200   <- restore 12, revision #5
[02/Nov/2026:09:40:04 +0800] GET   /api/admin/config 200   <- final p1 reload showing #5 / 12
```

The 409 at 09:39:41 is the recorded proof that the optimistic-lock collision
reached the Java backend, not just the browser.

## Absolute paths

Copied to the integration worktree; originals kept until checksums matched
(`shasum -a 256`, all 17 files identical):

* `/Users/qianchen/works/新发展/iot-agent-backend-business/.scratch/agent-business-recovery/evidence/ENG-05/admin/` (delivery copy)
* `/Users/qianchen/works/新发展/mp/.scratch/agent-business-recovery/evidence/ENG-05/admin/` (original screenshots of this session)


## Final settings after this session

* revision `#5`; next-round effective revision `#5`
* `maxToolCalls=12` (the only intended change; final value after the conflict round-trip)
* `maxAgentTurns=6`, `runTimeoutMs=300000` unchanged
* model id / base URL / office instruction (revision-2 content) / company and reply
  instruction fields unchanged; deployment fields unchanged
* model API key: untouched — field empty, `已配置` placeholder only; no key was read,
  rendered or written
* no process restart, no deploy, no model config change, no Java/Node source or build
  artifact edited, `p1` left open and logged in

## Limits of this evidence

* Budget effect was observed at the configuration layer only. Whether the **next**
  mini-program turn actually receives `maxToolCalls=12`, and whether the parent's
  in-flight Company/energy run keeps its start-of-run snapshot (`10`), was **not**
  observed here — the UI states the rule (`限额在一轮对话开始时读取…`), the parent verifies
  the real turn.
* The conflict step proves the optimistic-lock rejection and rebase flow for
  `maxToolCalls` between two pages of one space; it does not cover restore/rollback,
  password change, process-level (pending-restart) fields, or multi-operator races.
* Two extra revisions (#4 with `13`, #5 back to `12`) exist in audit history as a
  side effect of that conflict demonstration; payload/key fields were never changed.
