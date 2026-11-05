# Independent Pi Agent Sidecar

Node 22 / published Pi 0.84.4. Java owns business instructions, tool schemas, authorization, device/task/report behavior, model policy and durable Pi Session storage in MySQL. Node owns the generic Pi loop, streaming, tool transport and checkpoint/recovery mechanics. It has no runtime dependency on `../iot-agent-runtime`, no database credentials and no SQLite or local session volume.

> 配置迁移状态（2026-11-05）：显式外置 YAML 已是仓内唯一启动路径，仓库不再提供 `.env` 模板。固定实例由 `tools/start-fixed-backend.py` 以私密 `var/fixed-backend/launch.yaml` 启动（模板 [`fixed-backend.example.yaml`](../fixed-backend.example.yaml)）。改动前先读[统一治理规范](../docs/configuration-governance.md)和[盘点／交付入口](../.scratch/admin-console-governance/README.md)；不要绕过该入口重启现有实例。

## Build and run

```sh
npm ci
npm run build
```

The deployment process provides an explicit environment to the launcher, never an implicit dotenv file. The fixed deployment starts this component as the `node` entry of the private launch YAML through `tools/start-fixed-backend.py` (single foreground component: `tools/start-fixed-backend.py --run node`, started only after Java serves its configuration):

```yaml
node:
  executable: /opt/homebrew/opt/node@22/bin/node
  artifact: /Users/yourname/dev/iot-agent-backend/var/fixed-latest-YYYYMMDD-HHMMSS/agent-sidecar/dist/server.js
  working_directory: /Users/yourname/dev/iot-agent-backend/var/fixed-latest-YYYYMMDD-HHMMSS/agent-sidecar
  environment:
    SIDECAR_HOST: '127.0.0.1'               # private bridge; the frontend never calls Node
    SIDECAR_PORT: '3382'
    AGENT_BRIDGE_TOKEN: ''                   # same random local-only token as the java entry
    JAVA_AGENT_TOOLS_URL: 'http://127.0.0.1:3381/internal/agent/tools'
    JAVA_AGENT_TOOLS_TOKEN: ''               # must equal the java entry's AGENT_BRIDGE_TOKEN
    JAVA_AGENT_SESSIONS_URL: 'http://127.0.0.1:3381/internal/agent/sessions'
```

Empty secrets fail closed until the private copy is filled; the node entry receives no database, Redis, admin, model or Langfuse bootstrap values. `tools/start-sidecar.py` remains the allowlist exec wrapper for starting a standalone release with an explicit environment.

The wrapper never reads `.env` or another implicit dotenv file and passes only the sidecar environment allowlist. An optional directory argument selects an immutable release containing `dist/`, `package.json` and installed dependencies; the application directory may be read-only and session data is not written there.


## Configuration

- `SIDECAR_HOST` / `SIDECAR_PORT`: default `127.0.0.1:3382`; private bridge only. Java's fixed frontend-facing port is `3381`; the frontend never calls Node directly.
- `AGENT_BRIDGE_TOKEN`: Java → Node bearer authentication.
- `JAVA_AGENT_TOOLS_URL` / `JAVA_AGENT_TOOLS_TOKEN`: private Java tool callback. The callback token must equal Java's `AGENT_BRIDGE_TOKEN`; an independently generated value is rejected before tools or Pi Session access.
- `JAVA_AGENT_SESSIONS_URL`: private Java `/internal/agent/sessions`; defaults to the tools URL's origin plus that path. Uses the same private callback token.
- Model and observability settings are no longer environment inputs. Every `/run` request must carry a complete `definition.model` snapshot; when the Java admin console has not configured a model yet, runs fail explicitly with a clear error while the sidecar itself stays up for configuration.
- Observability (Langfuse) is fetched once at process start from Java's private `GET /internal/agent/observability` (same callback token) and never swapped at runtime: one process owns one OTel SDK, and adopting new saved values requires the supervised restart path. `/healthz` reports the adopted `observability` state (`enabled`, `revision`, `baseUrl`, `credentialsDigest`) without secrets.
- `SIDECAR_RUN_TIMEOUT_MS`, `JAVA_AGENT_TOOL_TIMEOUT_MS`, `SIDECAR_HEARTBEAT_MS`: optional runtime limits (`SIDECAR_HEARTBEAT_MS` is the bridge stream heartbeat, independent of Java's console-managed conversation heartbeat).

Java must have MySQL enabled and available. There is no SQLite fallback and no old-session import. The one-time Java cutover retires old public conversations while preserving historical business facts. New MySQL conversations survive subsequent Java/Node replacement within the application's conversation retention policy.

## Private HTTP contract

All Node routes require `Authorization: Bearer $AGENT_BRIDGE_TOKEN`.

- `POST /run`: versioned generic run request; NDJSON response.
- `POST /cancel/:runId`: cancel the matching in-flight run.
- `GET /healthz`: sanitized service status.

Run fields: `protocolVersion:1`, `runId`, `conversationId`, `turnId`, `toolContextId`, `content`, opaque `sessionScope`, and `definition`. Unknown fields are rejected.

`definition` contains Java-owned `instructions`, `tools` (`name`, `label`, `description`, JSON Schema `parameters`), `maxTurns`, optional `context`, `resume`, `maxToolCalls`, `runTimeoutMs`, `finalizationInstructions`, `compactionInstructions` and `model`. A model snapshot contains `modelId`, `modelBaseUrl`, `modelApiKey`, `contextWindow`, `maxOutputTokens`, `reasoning`, `thinkingLevel`, `supportsDeveloperRole`, `supportsReasoningEffort`, `supportsUsageInStreaming`, `maxTokensField`. Keys and private URLs are never emitted in public events or operational errors.

`context` is bounded, ephemeral invocation data. Node inserts it immediately before the current user input for inference, after loading or compacting durable history; it never appends that context to the session journal or a summary. Java supplies and interprets current-time, scope and reference facts. Node does not inspect their business fields. `instructions` remains the stable system policy; `compactionInstructions` governs durable-history summaries.

`resume:true` continues the existing turn after a Java-owned system decision. It requires invocation context, replaces the current turn's inference input with that decision, and does not journal a duplicate user request or carry the decision into a later run. It never grants task-confirmation authority.

Node enforces the supplied loop/tool/time budgets. Java may return `halt:true` on a successful or failed tool response. Optional Java-supplied finalization instructions permit a tool-free final answer; no business fallback is embedded in Node.

Tool callbacks include the four run-binding IDs, protocol, tool name/call ID, arguments and `sessionWriter` fencing proof. Java resolves tenant/owner/source from its active authenticated run and rejects stale writers before dispatch. The session callback carries the same run binding and opaque virtual filesystem operations; it accepts neither database credentials nor tenant/source claims.

Failed tool callbacks preserve Java's opaque `code` and `recovery` on the error. The model-visible message includes the whole recovery JSON only when its rendering fits within 2,000 characters; oversized metadata is omitted from that rendering, not truncated into invalid JSON, and remains attached to the error. Node does not interpret business recovery fields or automatically retry the tool.

## Persistence and recovery

The public Pi `JsonlSessionRepo` runs against a remote virtual filesystem backed by Java/MySQL append chunks. Its name does not imply local JSONL files. Native Pi retains record/entry, branch, compaction and open-operation semantics. Java provides scoped transactions, version checks, idempotent commit receipts and writer-epoch fencing.

Tool intent and outcomes are checkpointed. After interruption, a committed outcome is recovered; an uncertain outcome becomes an explicit interrupted tool result. Node does not automatically replay a business effect. A running model request is not promised seamless continuation after process replacement.

Before inference, native Pi compaction keeps the model context within its window. Java owns the optional compaction policy; Node stores its hash with the native compaction entry. A changed policy rebuilds from original journal messages rather than summarizing an already-lossy old summary. Original entries remain durable. Empty or length-truncated summaries fail explicitly rather than erasing history.

Native summaries truncate large individual tool results; they are not the business fact store. Java separately reprojects compact immutable report facts from this authenticated conversation, validating tenant, owner, source and conversation identity. These historical values and evidence restrictions do not grant current device authorization. Node has no report, device or task schema knowledge.

### Public Pi API to private storage mapping
| Public API | Owner and durable operation |
| --- | --- |
| `SessionRepo.create/open/list/delete/fork/close` | Thin opaque-ID wrapper delegates to Pi's native repository. Only physical filename IDs are JSON/base64url encoded; public IDs and parent IDs round-trip unchanged. Deleted/recreated handles are invalidated. |
| metadata, lanes, lane moves, append entry/record, entry/branch/record/log queries, open operations, name/label/stats | Pi `Session` delegates to native storage unchanged; every wrapper checks the live handle. Pi owns validation, shared sequence, immutable reads, branch and compaction semantics. |
| `absolutePath/joinPath` | Pure POSIX virtual-path normalization; never opens a local file. |
| `readTextFile/readTextLines/fileInfo/listDir/exists` | Authenticated Java `read/lines/info/list/exists`, restricted to `/sessions` in the Java-derived scope. |
| `writeFile/appendFile/createDir/renameFile/remove` | Java `write/append/mkdir/rename/remove`; short scope-locked transaction with expected version, writer epoch and commit ID. |
| writer admission / close / tool dispatch | Java `claim/release/check`; four active-run IDs resolve server identity, not caller-supplied tenant/source. |

File failures map to Pi's `aborted`, `not_found`, `permission_denied`, `not_directory`, `is_directory`, `invalid`, `not_supported`, or `unknown`. Authentication, stale writers and conflicts fail closed. A transport/body-read failure may retry **one identical storage mutation** with the same commit ID and content; it never retries a model run or business tool. Append chunks are checked for missing sequence numbers rather than silently truncating history.

Assistant text follows Runtime `app.ts`'s `toDurableMessage` on persistence and history loading: `<think>` / `<thinking>` spans, including tags split across text blocks, do not become answer text or later-turn facts. Existing journal records are not rewritten; their model-facing read projection receives the same normalization. Tool calls, explicit reasoning blocks, business errors and legitimate public text remain intact. Java owns compact business tool projections and deterministic proposal receipts; the generic engine does not inspect device or draft fields.


The former SQLite capacity, integrity, backup, restore and writer-lease HTTP operations are removed. Administration exposes only Java's `/api/admin/pi-session/health`; backups cover the authoritative MySQL database together with linked business facts and the cutover marker.

## Engineering verification versus acceptance

`npm test` exercises generic loop/transport/recovery behavior against the Java/MySQL session adapter; it also requires the separate local `PiSessionConformanceServer` and an isolated contract/test database. Use Node 22 and set `JAVA_SESSION_TEST_URL=http://127.0.0.1:3297/internal/agent/sessions` (and `JAVA_SESSION_TEST_TOKEN` if the fixture token differs) before running `npm test`. With the same fixture, run `npm run build` then `node test/mysql-session-contract.mjs` for the unchanged upstream SessionBackendConformance suite through Node → Java → real MySQL. No customer gateway or historical Runtime is contacted by those fixtures.

These are engineering checks, **not business acceptance**. Business acceptance requires real WeChat DevTools operations and screenshots, linked from `.scratch/pi-session-mysql/acceptance.md`. Never execute device control, create tasks or send production notifications during acceptance.
