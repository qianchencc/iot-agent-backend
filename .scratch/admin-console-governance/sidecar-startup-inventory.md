## Summary

The current sidecar is a generic Pi execution process, but its bootstrap still requires environment-provided model credentials and permits a model fallback when Java omits the model snapshot. Java normally constructs a per-run business definition from active admin configuration, including model policy and limits. Scheduled company checkpoints use the same model-snapshot helper but hard-code their own limits.

The largest configuration gaps are:

1. **No-file-bootstrap cutover is not implemented.** The launcher and current sidecar README expect environment injection; the README explicitly sources `.env`. Compose can also implicitly load `.env`.
2. **Admin Langfuse settings do not configure Node.** Node independently enables tracing when three environment values are present, regardless of `LANGFUSE_ENABLED`; saved Java values do not reach it.
3. **Active revision is not a complete runtime-application acknowledgement.** Live Java fields are overlaid from the latest saved revision, while restart-required fields use the process-start revision. A supervisor acknowledgement means restart *scheduled*, not proven applied; Node does not report an active configuration revision.
4. **Callback authentication needs explicit migration design.** Java’s outbound bridge reads `agent.bridge.token`; its internal callback filter reads the distinct property `agent.bridge-token`. Node uses `JAVA_AGENT_TOOLS_TOKEN` for both tool and session callbacks. The existing shared environment name can obscure the distinct YAML properties.
5. **MySQL is Java-owned**, including Pi session tables and the one-time conversation retirement. Node has no direct database connection. Existing historical runbooks contain SQLite and `.env` instructions that must be clearly superseded, not silently rewritten as though they never described earlier work.

This is a static, read-only audit of the named source, tools, Compose files and relevant Java seams. No real `.env` or secret file was read; no service, database, test, build, or linter was run. Third-party library environment variables were not exhaustively audited.

## Files and architecture

| File | Relevant responsibility |
|---|---|
| `agent-sidecar/src/config.ts:1-69` | Sidecar config shape, environment defaults and validation, test defaults. |
| `agent-sidecar/src/server.ts:1-15` | Loads config once, builds app, listens and handles shutdown. |
| `agent-sidecar/src/app.ts:45-156` | Authenticated private routes, run timeout, heartbeat, streaming and lifecycle. |
| `agent-sidecar/src/agent.ts:29-118`, `:192-267`, `:303-360`, `:422-484` | Model and run-definition contract, validation, fallback, per-run budgets and Java tool transport. |
| `agent-sidecar/src/observability.ts:29-143` | Separate environment-based optional Langfuse initialization and trace contents. |
| `agent-sidecar/src/session.ts:97-105`; `agent-sidecar/src/mysql-session-repository.ts:16-17`, `:97-106`, `:138-139` | Java-backed virtual session filesystem and callback transport. |
| `tools/start-sidecar.py:1-28` | Environment allowlist, Node selection, optional release directory and `execve`. |
| `tools/*.py`, `tools/*.mjs` | Preview, probe, smoke and offline evidence tools; individual options below. |
| `compose.yaml:1-33`; `compose.business-preview.yaml:1-37` | Local and preview MySQL/Redis provisioning and interpolation. |
| `src/main/java/ltd/qianc/iotagent/agent/internal/AgentBusinessRunPolicyProvider.java:107-154`, `:240-307` | Reads Java active settings and creates normal run definitions. |
| `src/main/java/ltd/qianc/iotagent/agent/internal/DefaultAgentCheckpointBridge.java:96-116` | Company checkpoint definition and separate hard-coded budgets. |
| `src/main/java/ltd/qianc/iotagent/admin/internal/AdminModuleConfiguration.java:34-57`, `:117-183` | Store choice, process apply seam, bootstrap defaults and environment locks. |
| `src/main/java/ltd/qianc/iotagent/admin/internal/JdbcAdminConfigurationStore.java:45-100`, `:150-205`; `LiveAgentSettings.java:7-24` | Encrypted revisions, startup active revision, live overlay and restart reporting. |
| `src/main/java/ltd/qianc/iotagent/admin/internal/SupervisedAdminApplyCoordinator.java:31-58` | Supervisor handoff is an accepted restart request, not completion proof. |
| `src/main/java/ltd/qianc/iotagent/storage/internal/StorageSchemaInitializer.java:17-38`; `src/main/java/ltd/qianc/iotagent/pisession/internal/JdbcPiSessionService.java:24-44` | Java schema initialization, direct-cutover marker and MySQL session tables. |
| `README.md:3-16`; `agent-sidecar/README.md:1-70`; `docs/local-backend-preparation.md:1-86`; `docs/migration/ticket-10-local-runbook.md:1-100` | Current ownership overview, sidecar runbook, historical preparation and historical migration gate. |

**Connection:** Java authenticates a user, derives office or company context, reads its active admin settings and sends Node an immutable run definition. Node runs Pi, calls Java’s private tool and session endpoints, and streams events back. Java owns business policy and MySQL persistence; Node’s session adapter is an HTTP-backed virtual filesystem, not a MySQL client or local SQLite store. `README.md:3-4`; `agent-sidecar/README.md:25-65`.

---

## Report: Node runtime configuration inventory

**Application time:** unless noted, values are read once by `loadConfig()` at process start and require Node replacement/restart to change. Existing in-flight runs retain their parsed run definition. “Secret” denotes credentials or security-sensitive material; URLs may themselves be sensitive deployment information.

| Key | Default and validation | Consumer / actual effect | Secret | Evidence |
|---|---|---|---|---|
| `SIDECAR_HOST` | `127.0.0.1`; trim, blank falls back; no explicit host validation. | Fastify listen host. | No | `config.ts:35-38`; `server.ts:15` |
| `SIDECAR_PORT` | `8787`; positive safe integer; no explicit upper port bound. | Fastify listen port. | No | `config.ts:23-26`, `:38`; `server.ts:15` |
| `AGENT_BRIDGE_TOKEN` | Required, trimmed nonblank. | Bearer authentication on all existing Node routes, including health. | Yes | `config.ts:17-20`, `:39`; `app.ts:14-24`, `:53-69` |
| `JAVA_AGENT_TOOLS_URL` | Required, trimmed nonblank. If sessions URL is absent, it must also be parseable by `new URL` to derive the session URL; otherwise no explicit startup URL validation. | Java tool callback destination; basis for session URL fallback. | Sensitive endpoint, not inherently a credential | `config.ts:40-41`; `agent.ts:475-484` |
| `JAVA_AGENT_SESSIONS_URL` | Optional; trimmed nonblank or origin of tools URL plus `/internal/agent/sessions`; explicit value is not URL-validated at startup. | Java session virtual-filesystem callback. | Sensitive endpoint | `config.ts:41`; `session.ts:97-100` |
| `JAVA_AGENT_TOOLS_TOKEN` | Required, trimmed nonblank. | Sent to both Java tool and session callbacks as `X-Agent-Bridge-Token`; tool callback also supplies bearer header. | Yes | `config.ts:42`; `agent.ts:478-484`; `mysql-session-repository.ts:104-106` |
| `MODEL_BASE_URL` | Required, trimmed nonblank; bootstrap value has no explicit URL validation in `loadConfig`. | Fallback model base URL if `definition.model` absent. Still required at startup even if every run supplies a Java model. | Sensitive endpoint | `config.ts:43`; `agent.ts:92-99`, `:303-306` |
| `MODEL_ID` | Required, trimmed nonblank. | Fallback model ID; still required with Java-supplied models. | No | `config.ts:44`; `agent.ts:92-95` |
| `MODEL_API_KEY` | Required, trimmed nonblank. | Fallback model key; still required with Java-supplied models. | Yes | `config.ts:45`; `agent.ts:303-306`, `:342` |
| `JAVA_AGENT_TOOL_TIMEOUT_MS` | `60000`; positive safe integer. | Both Java tool callback timeout and session callback timeout—not tools alone. | No | `config.ts:46`; `agent.ts:475-478`; `session.ts:97-100` |
| `SIDECAR_RUN_TIMEOUT_MS` | `120000`; positive safe integer. | Fallback only if run definition lacks `runTimeoutMs`; actual timer is selected at run admission. | No | `config.ts:47`; `app.ts:80-88` |
| `SIDECAR_HEARTBEAT_MS` | `15000`; positive safe integer. Zero cannot disable it. | Node NDJSON heartbeat timer for each run. | No | `config.ts:48`; `app.ts:119-120` |
| `AGENT_THINKING_LEVEL` | `low`; `off`, `low`, `medium`, `high` only. | Fallback when the Java model snapshot is absent; Java snapshot takes precedence. | No | `config.ts:28-32`, `:49`; `agent.ts:333-342` |
| `LANGFUSE_PUBLIC_KEY` | Trimmed; empty or missing disables observability along with any missing companion value. | `LangfuseSpanProcessor` initialization. | Treat as sensitive; Java masks it as a secret. | `observability.ts:36-43`; `JdbcAdminConfigurationStore.java:31` |
| `LANGFUSE_SECRET_KEY` | Same all-three-present condition. | Langfuse processor credential. | Yes | `observability.ts:36-43` |
| `LANGFUSE_BASE_URL` | Same all-three-present condition; no explicit URL validation in this function. | Langfuse processor endpoint. | Sensitive endpoint | `observability.ts:36-43` |
| `LANGFUSE_ENABLED` | Passed by launcher if present; **not read by sidecar source**. | No observed first-party Node effect. In particular, `false` does not override three populated Langfuse values. | No | `tools/start-sidecar.py:15-19`; `observability.ts:36-43` |
| `OTEL_SERVICE_NAME` | Passed by launcher if present; not read explicitly by sidecar source. | May be interpreted by OpenTelemetry dependencies; exact library behavior was not audited. | No | `tools/start-sidecar.py:17-19`; `observability.ts:1-4`, `:41-43` |
| `NODE_ENV` | Launcher forces `production`, overriding inherited value. | Exposed to Node and dependencies; no first-party consumer found in sidecar source. | No | `tools/start-sidecar.py:20-21` |
| `PATH`, `LANG`, `LC_ALL`, `TZ` | Passed through only when present. | Process/library environment; launcher locates Node with `shutil.which("node")`. | Normally no | `tools/start-sidecar.py:12-23` |

### Request-level business configuration and drift

| Setting | Current owner, validation and use | Application time / drift | Evidence |
|---|---|---|---|
| `definition.model` | Java constructs the model snapshot from active admin settings when complete; Node validates all fields. Model URL must be HTTP(S), with no credentials, query or fragment. Token counts are `1..10,000,000`, output must be below context window; flags and enums are typed. | Snapshot per run. If incomplete, Java returns an empty model map and omits `model`; Node silently uses its bootstrap model rather than failing closed. | `AgentBusinessRunPolicyProvider.java:253-255`, `:276-307`; `agent.ts:192-225`, `:303-306` |
| Fallback model capabilities | Node fallback is `contextWindow=128000`, `maxOutputTokens=16384`, `reasoning=true`, developer role false, reasoning effort and streaming usage true, `max_completion_tokens`. | These are hard-coded Node fallbacks, not independently configured Java business policy. | `agent.ts:92-116` |
| `maxTurns` | Required integer `1..32`; Java normal runs use admin `maxAgentTurns`, with provider fallback to injected `agent.run.max-turns` default `8`. Admin bootstrap default is `6`. | Per normal run; checkpoint separately fixes `6`. | `agent.ts:251-253`; `AgentBusinessConfiguration.java:35-40`; `AgentBusinessRunPolicyProvider.java:247-248`; `AdminModuleConfiguration.java:158`; `DefaultAgentCheckpointBridge.java:103-105` |
| `maxToolCalls` | Optional integer `1..1000`; Java normal-run setting defaults to `10`. Node counts and blocks calls at budget. | Per normal run; checkpoint fixes `10`. | `agent.ts:255-257`, `:433-438`; `AgentBusinessRunPolicyProvider.java:251`; `DefaultAgentCheckpointBridge.java:105` |
| `runTimeoutMs` | Optional integer `1000..600000`; Java normal-run provider fallback `180000`; admin bootstrap default `300000`; Node fallback `120000`. | Per run; checkpoint fixes `180000`. Java HTTP bridge also uses definition timeout when supplied. These are distinct defaults, not one shared limit. | `agent.ts:258-260`; `app.ts:80-88`; `AgentBusinessRunPolicyProvider.java:252`; `AdminModuleConfiguration.java:163`; `DefaultAgentCheckpointBridge.java:106`; `HttpAgentEngineBridge.java:80-83` |
| `thinkingLevel` and model capabilities | Java admin bootstrap defaults match the Node fallback capabilities listed above, but Java validates its own narrower administrative constraints and `reasoning=false` requires `thinkingLevel=off`. | New Java-snapshotted runs get saved live fields; sidecar env matters only for fallback. | `AdminModuleConfiguration.java:147-157`; `JdbcAdminConfigurationStore.java:288-315`; `agent.ts:333-337` |
| Instructions, tool schemas, ephemeral context, finalization and compaction | Java generates the business definition; Node validates generic shapes, passes them to Pi, and enforces execution. | Per run. This is the correct business/generic ownership seam. | `AgentBusinessRunPolicyProvider.java:240-254`; `agent.ts:228-267`; `agent-sidecar/README.md:39-53` |
| Heartbeat | Java admin `heartbeatMs` bootstrap defaults to `15000`, while Node independently reads `SIDECAR_HEARTBEAT_MS`; Java application also has `agent.heartbeat-ms`, default `15000`. | No shown mapping from admin `heartbeatMs` to Node’s heartbeat timer. Do not label these as one active setting. | `AdminModuleConfiguration.java:165`; `application.yml:18`; `config.ts:48`; `app.ts:119-120` |

**Bridge token distinction:** Java’s outbound Node bridge uses `agent.bridge.token` (`application.yml:13-17`); Java’s private callback filter is constructed from `agent.bridge-token` (`auth/internal/SecurityConfiguration.java:67-69`) and checks `X-Agent-Bridge-Token` (`BridgeAuthenticationFilter.java:40-45`). Node’s `JAVA_AGENT_TOOLS_TOKEN` must match the latter for both session and tool callbacks. An external YAML design must configure and document these separately or deliberately map them to one shared secret; setting only the nested YAML property must not be assumed to set the hyphenated property.

### Langfuse and sensitive output

- Node creates a no-op observer if **any** of base URL, public key or secret key is absent; if all are present, it starts an SDK at app construction. `observability.ts:29-43`. There is no Node-side read of `LANGFUSE_ENABLED`.
- Traces can contain user content, system prompts, message history, tool arguments/results, generated output and usage—not just operational metadata. `observability.ts:45-109`, `:115-143`. This needs explicit data-governance and deployment controls.
- Java admin stores and validates Langfuse fields (`AdminModuleConfiguration.java:169-172`; `JdbcAdminConfigurationStore.java:308-315`), but the inspected Java/Node source contains no transport from those saved fields to Node’s observer. A Java restart alone does not prove Node has reloaded them.
- Public event redaction is not a universal payload scrub: `app.ts:36-43`, `:101-116` intentionally restores several content/tool fields after redaction. `redaction.ts:1-23` masks matching object keys and bearer strings, but it is not proof arbitrary sensitive data cannot occur in passed-through content or tracing.

---

## Report: launcher, tools and test configuration

All listed script options are read at invocation. They do not hot-reload. The sidecar launcher **does not itself open `.env`**; its docstring tells the invoking shell to load it (`tools/start-sidecar.py:1-5`). In the scoped executable script search, no script-side dotenv loader was found. This excludes dependency internals and treats the historical `.scratch` prototype separately.

| Script / key or option | Default, validation and consumer | Secret / timing | Evidence |
|---|---|---|---|
| `start-sidecar.py` positional release directory | Default repository `agent-sidecar`; resolves supplied path, changes directory, executes `dist/server.js`. No explicit release integrity validation. | Deployment path; invocation | `tools/start-sidecar.py:22-28` |
| `start-sidecar.py` allowlist | Node keys in the runtime table plus `PATH`, locale, `TZ`, `LANGFUSE_ENABLED`, `OTEL_SERVICE_NAME`; excludes DB/admin/encryption values from the child. | Includes bridge/model/Langfuse credentials; invocation | `tools/start-sidecar.py:12-21` |
| `business-preview-upstream.mjs`: `BUSINESS_UPSTREAM_ORIGIN` | Required truthy string; `new URL(...).origin` normalizes it; proxy listens only on loopback. | Sensitive endpoint; invocation | `tools/business-preview-upstream.mjs:30-33`, `:131-136` |
| `business-preview-upstream.mjs`: `BUSINESS_UPSTREAM_PORT` | `3245`; `Number(...)`, no explicit integer/range validation before `listen`. | No; invocation | `tools/business-preview-upstream.mjs:131-136` |
| Preview proxy request credentials | Forwarded incoming `authorization` and `tenantcode`, with read-route guards and a 30-second upstream timeout. Not environment configuration. | Yes; per request | `tools/business-preview-upstream.mjs:80-117` |
| `business-preview-player.py`: `--directory` | Required existing directory; resolved strictly. | Path may reveal deployment structure; invocation | `tools/business-preview-player.py:26-35` |
| `business-preview-player.py`: `--port` | `9419`; argparse integer; loopback bind, no explicit port range check. | No; invocation | `tools/business-preview-player.py:27-36` |
| `probe-upstream-readonly.mjs`: `IOT_GATEWAY_BASE_URL` | Required nonblank; used as URL base. | Sensitive endpoint; invocation | `tools/probe-upstream-readonly.mjs:1-12`, `:23-26` |
| `probe-upstream-readonly.mjs`: `IOT_TEST_AUTHORIZATION`, `IOT_TEST_TENANTCODE` | Both required nonblank; sent as upstream headers. | Credentials/sensitive tenant identity; invocation | `tools/probe-upstream-readonly.mjs:1-6`, `:23-26` |
| `sidecar-java-process-smoke.mjs`: `BACKEND_URL`, `SIDECAR_URL`, `ADMIN_ORIGIN` | Defaults respectively `http://127.0.0.1:3205`, `http://127.0.0.1:3221`, `http://127.0.0.1:3200`; nullish fallback, no explicit URL validation. | Sensitive deployment endpoints; invocation | `tools/sidecar-java-process-smoke.mjs:6-10` |
| Smoke: `AGENT_BRIDGE_TOKEN`, `ADMIN_PASSWORD` | Both required truthy; used for authenticated Node health and admin login. | Yes; invocation | `tools/sidecar-java-process-smoke.mjs:8-25` |
| `migration-ticket-21.mjs`: positional `records.jsonl` | Required path; reads and validates local JSONL; no network call or source write. | Input may contain sensitive evidence; invocation | `tools/migration-ticket-21.mjs:1-2`, `:69-75` |
| `ticket10_local.py contract`: `--runtime-root`, `--backend-root` | Both required paths. | Paths; invocation | `tools/ticket10_local.py:338-346` |
| Contract: `--expected-paths`, `--expected-operations` | Integer defaults `66`, `69`. | No; invocation | `tools/ticket10_local.py:344-345` |
| Migration: `--source`, `--target` | Required JSONL paths. | Evidence may contain sensitive data; invocation | `tools/ticket10_local.py:347-349` |
| Migration: `--identity`, `--order`, `--version`, `--status` | Field-name defaults `id`, `sequence`, `version`, `status`. | No; invocation | `tools/ticket10_local.py:350-353` |
| Migration: `--terminal-values` | Comma-separated sorted built-in terminal set: `run.completed`, `run.awaiting_confirmation`, `run.failed`, `run.aborted`, `completed`, `failed`, `aborted`, `cancelled`, `CANCELLED`, `EXPIRED`, `SCHEDULED`, `CONFIRMED`, `NO_CHANGE`, `unknown`, `UNKNOWN`. | No; invocation | `tools/ticket10_local.py:15-19`, `:354` |
| `shadow`, `execution-boundary`: `--records` | Required local evidence path for each command. | Evidence may be sensitive; invocation | `tools/ticket10_local.py:356-361` |
| `cutover-gate`: `--approval`, `--operator`, `--window`, `--health`, `--backup`, `--migration`, `--shadow`, `--execution`, `--logs`, `--config`, `--rollback` | All required; gate reads evidence and does not itself deploy or switch routes. | Evidence/operator identifiers may be sensitive; invocation | `tools/ticket10_local.py:362-377`; `docs/migration/ticket-10-local-runbook.md:58-91` |
| Test fixture: `JAVA_SESSION_TEST_URL` | Required by standalone process and MySQL session contracts; optional test-default override in `defaultConfigForTests`. | Isolated test endpoint; invocation | `agent-sidecar/test/process-contract.mjs:6-8`; `test/mysql-session-contract.mjs:5-6`; `src/config.ts:55-60` |
| Test fixture: `JAVA_SESSION_TEST_TOKEN` | Defaults `test-java`; used for Java test callback. | Test credential; invocation | `test/process-contract.mjs:43`; `test/mysql-session-contract.mjs:10` |
| Process-contract `PATH` | Passed into spawned child, alongside deliberately constructed fixture configuration. | Invocation | `test/process-contract.mjs:37-51` |

`agent-sidecar/package.json:1-27` pins Node `>=22.19.0 <23`, package versions and scripts. It does not declare dotenv. The launcher’s error says “Node 22 is required” but does not itself enforce the package’s exact engine range (`tools/start-sidecar.py:22-24`).

---

## Report: Compose configuration

### Main local Compose

Docker Compose interpolation is startup/deployment-time. Even without an explicit `env_file`, standard Compose behavior can read a project `.env` for interpolation; therefore removing explicit `source .env` alone does not meet the approved no-`.env` convention.

| Key / setting | Default / validation observed | Consumer and application time | Secret | Evidence |
|---|---|---|---|---|
| `MYSQL_DATABASE` | `iot_agent` when unset or empty (`:-`). | Passed to MySQL container initialization. Existing persisted databases should not be assumed reinitialized by changing this value. | No | `compose.yaml:7-8`, `:15-17` |
| `MYSQL_USERNAME` | `iot_agent` when unset or empty. | Passed into container as `MYSQL_USER`. | No | `compose.yaml:9` |
| `MYSQL_PASSWORD` | `iot_agent_local` when unset or empty. | MySQL container `MYSQL_PASSWORD`; healthcheck uses container variable. Local convenience default is not a production secret policy. | Yes | `compose.yaml:10`, `:18-22` |
| `MYSQL_ROOT_PASSWORD` | `root_local_only` when unset or empty. | MySQL container root password initialization. | Yes | `compose.yaml:11` |
| `MYSQL_PORT` | `3307` when unset or empty. | Host mapping to container `3306`; no loopback bind is specified. | No | `compose.yaml:13-14` |
| `REDIS_PORT` | `6380` when unset or empty. | Host mapping to `6379`; no loopback bind is specified. | No | `compose.yaml:29-30` |
| `TZ` | Fixed `Asia/Shanghai` for MySQL container. | Container timezone. | No | `compose.yaml:12` |
| Other fixed settings | MySQL `8.4`, Redis `7.4-alpine`, restart `unless-stopped`, local bind-mounted data, MySQL initial SQL, Redis AOF plus snapshot, healthcheck timings. | Container startup; not externally interpolated config keys. | SQL/data mounts may contain sensitive business data. | `compose.yaml:3-33` |

### Business preview Compose

| Key / setting | Default / validation observed | Consumer and application time | Secret | Evidence |
|---|---|---|---|---|
| `BUSINESS_MYSQL_PASSWORD` | Required nonempty by `${...:?}`; error text instructs use of an ignored `.env`. | MySQL user initialization. | Yes | `compose.business-preview.yaml:7-10` |
| `BUSINESS_MYSQL_ROOT_PASSWORD` | Required nonempty by `${...:?}`; same outdated `.env` instruction. | MySQL root initialization. | Yes | `compose.business-preview.yaml:11` |
| `MYSQL_DATABASE`, `MYSQL_USER`, `TZ` | Fixed `iot_agent_business`, `iot_agent_business`, `Asia/Shanghai`. | MySQL container initialization. | No | `compose.business-preview.yaml:7-12` |
| Ports and volumes | MySQL `127.0.0.1:3338:3306`, Redis `127.0.0.1:6398:6379`; named persistent volumes. | Container deployment. | Data volumes can contain sensitive data. | `compose.business-preview.yaml:13-18`, `:25-37` |

A no-`.env` Compose design needs an explicit supported mechanism for **both** interpolation and secrets; merely replacing the error message does not stop implicit `.env` discovery. Any proposed secret-file mechanism must also match the container image’s supported initialization interface and existing-volume behavior.

---

## MySQL session ownership and migration

| Fact | Current implementation / implication | Evidence |
|---|---|---|
| Authority | Java owns business facts and durable Pi sessions in MySQL; Redis is coordination/cache, not session authority. | `README.md:3-4` |
| Node transport | Node uses a Java-authenticated virtual filesystem with the callback token and timeout; Pi’s `JsonlSessionRepo` name does not mean local session files. | `session.ts:97-105`; `mysql-session-repository.ts:104-106`, `:138-139`; `agent-sidecar/README.md:55-65` |
| Java scope | Java derives tenant, owner, source, conversation and run from an authenticated active binding. This covers both office and company source contexts; checkpoint context explicitly uses company. | `PiSessionConfiguration.java:18-32`; `DefaultAgentCheckpointBridge.java:167-170` |
| Tables and concurrency | Java JDBC service creates session scopes, documents, chunks and commit receipts; writer epochs, transactions and idempotent commit handling are Java concerns. | `JdbcPiSessionService.java:24-44`, `:84-144` |
| One-time cutover | Java schema initialization creates `storage_migrations`, transactionally inserts `pi-session-mysql-direct-cutover-v1` and, only on first insertion, marks uncleared conversations cleared. It preserves underlying business facts; it does not import old sessions. | `StorageSchemaInitializer.java:17-38`; `README.md:12-15` |
| Backup | Current guidance is whole authoritative MySQL schema, including business facts, Pi tables and marker, with independent restore rehearsal. | `README.md:13-15`; `agent-sidecar/README.md:68-70` |
| New configuration migration | **Proposed, not current:** add an explicit versioned migration/initialization path for MySQL business configuration, with encrypted secrets and idempotent bootstrap. Do not repurpose the Pi cutover marker or re-retire conversations. The current JDBC admin store creates its own table and initializes revision 1 from bootstrap only when no revision exists. | `JdbcAdminConfigurationStore.java:169-200`; `StorageSchemaInitializer.java:29-38` |

## Saved, active and applied revisions: current facts

- Java chooses MySQL admin configuration when `agent.storage.mysql.enabled=true`, provided JDBC/transactions and a valid 64-hex-character encryption key are available. Otherwise it uses encrypted file storage when MySQL is disabled, or a fail-closed store on configuration/construction failure. `AdminModuleConfiguration.java:34-57`.
- MySQL saves encrypted revision records with optimistic expected-revision checking. At process start, the store records the latest revision as its `activeRevision`; `active()` overlays the latest saved **live** fields on that startup revision. `JdbcAdminConfigurationStore.java:48-57`, `:80-100`, `:159-166`, `:183-200`; `LiveAgentSettings.java:7-24`.
- Consequently, a view can be a **hybrid of two revisions**. If restart-required differences remain, the displayed active revision is the startup revision even though live fields can already reflect later saves. If no differences remain, the view reports the latest revision. This is not a per-component acknowledgement from Java and Node. `JdbcAdminConfigurationStore.java:61-73`.
- Without a supervisor, apply returns unavailable for pending restart. With a configured supervisor, a positive response produces `CONFIG_APPLY_SCHEDULED`, not confirmation that the replacement process is ready and running the intended revision. Default `AdminWorkState` reports not busy. `AdminModuleConfiguration.java:106-142`; `SupervisedAdminApplyCoordinator.java:31-58`.
- Normal runs read active Java settings when their definition is built (`AgentBusinessRunPolicyProvider.java:107`, `:153-155`); existing runs do not retroactively switch model/policy. Scheduled checkpoints read model settings at their own invocation but retain their fixed budgets (`DefaultAgentCheckpointBridge.java:101-116`).

## Proposed no-`.env` cutover and concrete missing seams

These are design recommendations, **not existing behavior**:

1. **Packaged nonsecret defaults + external YAML bootstrap.** Give Java and Node explicit, deployment-managed YAML bootstrap paths. Define precedence and reject unknown or conflicting bootstrap fields. External bootstrap contains only deployment concerns: listen addresses, private bridge endpoints, secret-file references, MySQL connectivity and encryption-key reference, and optional observability deployment settings. Do not use `.env`, including local development or Compose’s implicit discovery.
2. **MySQL owns editable business settings.** Java reads encrypted MySQL revisions and snapshots model policy, limits and office/company instructions into each run. Design a first-boot provisioning/migration path for a valid initial business configuration and its secrets; existing initialization rejects invalid initial settings rather than creating a usable incomplete revision (`JdbcAdminConfigurationStore.java:183-200`).
3. **Remove the Node model bootstrap dependency by clean cutover.** Require a complete Java model definition for both normal runs and company checkpoints; fail explicitly if Java cannot supply one. Remove Node’s `MODEL_*` and thinking-level fallback and associated startup requirements only after all callers and contract tests migrate. Keep generic protocol bounds in Node, not duplicated business defaults.
4. **Explicit callback secrets and coordinated rotation.** Define whether Java→Node and Node→Java are distinct credentials; map both Java property names and Node’s corresponding YAML fields explicitly. Validate endpoint and token pairing without exposing values. Coordinate rotation across processes rather than claiming an applied revision before both sides accept the new configuration.
5. **Observability ownership and application.** Decide whether Langfuse is deployment-only or admin-managed. If admin-managed, implement a secure Java-to-Node configuration/reload or supervised restart path, honor an explicit enable flag, and report Node’s observed active observability revision. If deployment-only, remove misleading editable Java fields or make their non-application explicit. Preserve optional disabled mode and document trace payload sensitivity.
6. **Truthful per-component revision status.** Persist desired revision separately from actual active acknowledgements. Report Java live snapshot revision, Java restart-bound revision, Node bootstrap/observability revision, per-component readiness/failure and in-flight run semantics. Supervisor acceptance is “scheduled”; mark “applied” only after authenticated replacement components acknowledge the target revision. Handle mixed revisions, failed restarts and multi-instance rollout explicitly.
7. **Compose local startup without implicit dotenv.** Supply nonsecret configuration and secret files through a deliberately supported mechanism, including a way to prevent implicit `.env` loading. Replace preview Compose’s `.env` error guidance. Avoid passing database/admin credentials into Node; preserve launcher isolation.
8. **One deployment-managed admin identity.** Use the existing password-file seam as evidence of a possible file-based credential input (`AdminModuleConfiguration.java:67-87`), but define actual provisioning, rotation and restart semantics rather than introducing a second admin identity or claiming an existing mechanism is already the approved complete bootstrap.
9. **Model and limit scope in admin design.** Clearly distinguish normal office/company run settings, fixed company checkpoint budgets, Java heartbeat and Node heartbeat; do not offer a control as effective unless its consumer and activation mode are verified. Include the currently separate `180000`/`300000`/`120000` timeout defaults in the migration decisions.
10. **MySQL migration and recovery.** Version the business-config schema/bootstrap separately from the existing session cutover; preserve encrypted revision history and key management. Backup and recovery must include config revisions, business facts, Pi session tables and migration markers.

## Documentation and runbook supersession

| Location | Current status and required treatment |
|---|---|
| `agent-sidecar/README.md:11-25` | Current operational instructions explicitly source `.env` and describe env allowlisting. Replace with the implemented YAML/secret-file startup contract when cutover is delivered; document real activation/restart and remove obsolete model fallback guidance. |
| `README.md:7-15` | Current root points to the sidecar guide and launcher. Update its launcher/bootstrap summary and link to the authoritative no-`.env` runbook, without weakening Java/MySQL ownership or backup guidance. |
| `.env.example:1-2`, `:22-29` | Committed example explicitly instructs copying to `.env` and presents bridge/callback inputs. Supersede/remove it as part of the approved clean cutover, replacing it with nonsecret YAML examples and documented secret references—not actual credentials. |
| `compose.business-preview.yaml:10-11` | Error strings instruct an ignored `.env`; update alongside the real Compose secret/interpolation mechanism. |
| `docs/local-backend-preparation.md:1-8`, `:14-16`, `:28-41`, `:69-70` | Historical preparation evidence describes `.env`, an old scratch prototype and earlier observations. Add a prominent historical/superseded pointer to current instructions; preserve what was actually observed rather than rewriting the record as a current YAML runbook. |
| `docs/migration/ticket-10-local-runbook.md:20-27`, `:58-100` | Contains current MySQL backup guidance but later historical cutover sections refer to Pi SQLite backup/restore and unresolved older state. Mark the historical gate and old rollback assumptions explicitly; link current MySQL cutover/restore procedure. Do not erase historical evidence or represent old gates as current acceptance. |
| `tools/start-sidecar.py:1-5` | Its executable docstring directs users to load `.env`; change together with the launcher implementation, not documentation alone. |

**Verification performed:** static source, documentation and configuration inspection only. No runtime behavior, services, database contents, or tests were exercised.
