## Summary

- The examined business code has **51 `@Value` declarations, 48 `@ConditionalOnProperty` declarations, 10 `Environment.getProperty` call sites, two scheduled-property declarations, and three `System.getenv` calls at two source locations**. No `@ConfigurationProperties`, `@EnableConfigurationProperties`, `Binder.get`, `getRequiredProperty`, or `System.getProperty` match was found in the stated Java scope. Counts exclude `admin/internal`; they are declaration/call-site counts, not unique keys.
- **Current activation is heterogeneous.** Spring-injected settings and constructed clients are generally startup-bound. Agent run settings are read from `AdminConfigurationStore.active()` when a new run is prepared; checkpoint runs read only the model subset. That establishes consumption timing, **not** that save/apply correctly promotes or distributes a revision.
- **Highest-risk drift:** outbound bridge clients read `agent.bridge.token`, while the inbound internal-route filter reads the distinct `agent.bridge-token`; packaged YAML supplies only the former. The normal run’s `runTimeoutMs` is always placed in the definition and overrides the bridge’s nominal 120-second timeout. The checkpoint bridge has separate fixed limits and deadlines.
- **Default deployment is fail-closed for IoT scope:** `office-scope-path` defaults blank and its absence makes the entire IoT adapter fail closed, including company operations. Company and office must both appear in the admin audit.
- Existing configuration and proposed categories below are separated. **No conclusion about admin-store implementation, distributed revision consistency, or supervisor apply semantics is made:** `admin/internal` was explicitly excluded.

## Files and architecture

| File | Role |
|---|---|
| `src/main/resources/application.yml:1-83` | Packaged Spring defaults and placeholder sources. |
| `src/main/java/ltd/qianc/iotagent/agent/internal/AgentBusinessRunPolicyProvider.java:80-157,241-354` | Reads active agent settings for each prepared run; validates and constructs definition/model/instructions. |
| `src/main/java/ltd/qianc/iotagent/agent/internal/DefaultAgentCheckpointBridge.java:56-140,189-192` | Separate checkpoint bridge: active model subset, fixed run limits and HTTP timeouts. |
| `src/main/java/ltd/qianc/iotagent/conversation/internal/ConversationConfiguration.java:29-68` | Executor, scheduler, outbound bridge and heartbeat wiring. |
| `src/main/java/ltd/qianc/iotagent/conversation/internal/HttpAgentEngineBridge.java:45-105,157-160` | Uses definition timeout preferentially; outbound bearer token. |
| `src/main/java/ltd/qianc/iotagent/auth/internal/SecurityConfiguration.java:31-84` | Account verification policy, inbound bridge token, video CORS. |
| `src/main/java/ltd/qianc/iotagent/auth/internal/BridgeAuthenticationFilter.java:22-48` | `/internal/agent/` checks `X-Agent-Bridge-Token`; empty expected token rejects. |
| `src/main/java/ltd/qianc/iotagent/iot/internal/IotAdapterConfiguration.java:23-99` | Office/company route wiring and fail-closed selection. |
| `src/main/java/ltd/qianc/iotagent/iot/internal/ObservationAdapterConfiguration.java:39-155` | Observation route mapping, safety cursor key, composed adapters. |
| `src/main/java/ltd/qianc/iotagent/storage/internal/StorageConfiguration.java:39-75,132-145,159-205` | MySQL/Redis bootstrap, recurrence batch, read-model namespace. |
| `src/main/java/ltd/qianc/iotagent/task/internal/TaskConfiguration.java:46-119,165-167` | Task stores, credential key, fixture/real execution, scanner. |
| `src/main/java/ltd/qianc/iotagent/admin/AdminConfigurationStore.java:6-34` | Public contract distinguishes saved and active maps/revisions, restart status and secret-presence view. |

**Connection:** packaged YAML and external Spring property sources feed construction-time beans. MySQL enables durable business stores and schedulers; Redis optionally backs read snapshots. Agent policy preparation obtains a current store snapshot and sends a definition to the outbound engine. IoT and security clients do not read that active map in the examined code. The store contract exposes revision state, but its implementation and apply coordinator are outside this assignment.

## Report

### Coverage and conventions

Searches covered the stated `src/main/java` tree excluding `admin/internal`, the complete packaged `application.yml`, and test paths only for verification seams. Searches included annotation/property APIs, scheduling, durations, executor/queue construction, and named limits. Hardcoded-policy findings below are a **named-constant and recognizable-API inventory**, not a claim that arbitrary numeric literals, external dependencies, framework defaults, or all transitive behavior were exhaustively proven.

Table conventions:

- **B** = proposed deployment bootstrap; **R** = proposed controlled runtime business setting; **S** = proposed security/bootstrap secret or security policy; **D** = development-only; **I** = invariant/internal implementation, not an ordinary UI knob.
- Unless otherwise noted, Java `@Value`, route, connection and scheduler values are resolved at bean/scheduler construction: **restart/rebuild required**, not evidenced hot apply.
- YAML placeholders identify existing Spring property sources; they are **not** a recommendation to use environment files. The approved destination is packaged defaults + external YAML bootstrap + MySQL business configuration.
- “No local validation shown” means the cited consumer does not show it; framework or downstream validation may still exist.

### Packaged framework and core settings

| Key | Existing default / unit; source | Consumer, validation and timing | Proposed class |
|---|---|---|---|
| `server.address` | `127.0.0.1`; `${SERVER_ADDRESS}` | Spring server binding; `application.yml:1-3`; startup. | B, network-sensitive |
| `server.port` | `3200`; `${SERVER_PORT}` | Spring server binding; same evidence; startup. | B |
| `spring.application.name` | `iot-agent-backend` | Spring application identity; `application.yml:5-7`; startup. | I/B metadata |
| `spring.codec.max-in-memory-size` | `8MB`; `${SPRING_CODEC_MAX_IN_MEMORY_SIZE}` | Spring codecs; comment documents large upstream pages; `application.yml:8-11`; startup. | B, resource limit |
| `agent.bridge.base-url` | blank; `${AGENT_BRIDGE_BASE_URL}` | Outbound conversation/checkpoint clients and health; `ConversationConfiguration.java:55-60`, `AgentBusinessConfiguration.java:65-69`, `HealthController.java:37-41`; clients startup, health reads environment on request. | B, internal endpoint |
| `agent.bridge.token` | blank; `${AGENT_BRIDGE_TOKEN}` | Outbound bearer token; health checks only nonblank; same anchors. Blank makes conversation bridge unavailable (`HttpAgentEngineBridge.java:72-75`). | S, secret |
| `agent.bridge-token` | blank; no packaged YAML entry | **Different key**, inbound expected token; `SecurityConfiguration.java:67-69`; blank rejects `/internal/agent/` (`BridgeAuthenticationFilter.java:33-47`). Do not assume the dotted key populates it. | S, secret |
| `agent.bridge.run-timeout-ms` | `120000 ms`; `${AGENT_BRIDGE_RUN_TIMEOUT_MS}` | Conversation bridge constructor requires timeout; `ConversationConfiguration.java:55-60`; used only if definition lacks numeric `runTimeoutMs` (`HttpAgentEngineBridge.java:79-85`). Normal business definition always supplies one. | B until reconciled |
| `agent.heartbeat-ms` | `15000 ms`; `${AGENT_HEARTBEAT_MS}` | `ConversationConfiguration.java:63-68`; `ConversationFacade.java:153-157` schedules only if `>0`, so zero/negative disables; startup. | B, operational |
| `agent.video.allowed-origin` | blank; `${AGENT_VIDEO_ALLOWED_ORIGIN}` | Video-only CORS; `SecurityConfiguration.java:83-84`, `VideoStreamCorsFilter.java:48-54`; packaged comment requires exact origin and blank closes cross-origin playback (`application.yml:19-22`). | S/B |
| `agent.storage.mysql.enabled` | `false`; `${AGENT_STORAGE_MYSQL_ENABLED}` | MySQL conditional beans, report/session/task endpoints and loops; missing/false selects fail-closed stores where defined. `StorageConfiguration.java:39-54,79-175`, `TaskConfiguration.java:46-79,165-167`, `PiSessionConfiguration.java:18-21`, report controller/service conditionals. Startup. | B |
| `agent.storage.redis.enabled` | `false`; `${AGENT_STORAGE_REDIS_ENABLED}` | Redis connection, coordination and snapshot persistence; `StorageConfiguration.java:59-75,168-185`; startup. | B |
| `AGENT_MYSQL_URL` | required nonblank when MySQL enabled | `StorageConfiguration.java:40-44,203-205`; datasource built at startup. URL may contain credentials. | S/B |
| `AGENT_MYSQL_USERNAME` | required nonblank when enabled | Same datasource evidence. | S/B, sensitive identity |
| `AGENT_MYSQL_PASSWORD` | required nonblank when enabled | Same datasource evidence. | S/B, secret |
| `AGENT_REDIS_URL` | required nonblank when Redis enabled | `StorageConfiguration.java:60-70,203-205`; URI host/port, default port 6379, optional path DB and password from user-info. Treat entire URL as secret; startup. | S/B |
| `agent.control.mode` | blank; `${AGENT_CONTROL_MODE}` | `fixture` selects fixture IoT/action/task beans (`IotAdapterConfiguration.java:43-46`, `ActionDraftConfiguration.java:51-57`, `TaskConfiguration.java:89-94`); startup. | D; prohibit production fixture selection |
| `agent.control.fixture.outcome` | `accepted`; `${AGENT_CONTROL_FIXTURE_OUTCOME}` | Fixture action gateway; `ActionDraftConfiguration.java:52-57`; normalization/fallback `FixtureActionCommandGateway.java:108-120`. | D |
| `agent.control.fixture.readback` | `confirmed`; `${AGENT_CONTROL_FIXTURE_READBACK}` | Same fixture gateway and normalization. | D |
| `agent.control.fixture.task-outcome` | `COMPLETED`; `${AGENT_CONTROL_FIXTURE_TASK_OUTCOME}` | Fixture task gateway; `TaskConfiguration.java:89-94`. | D |
| `AGENT_BUILD_ID` | `local` if unset; direct process environment | Report producer uses unset-only fallback (`DefaultReportFactProducer.java:213-215`); evidence trims and also treats blank as local (`ReportEvidenceService.java:616-618`). Read at production time, not active-store-backed. | B, nonsecret build metadata; reconcile blank behavior |

### Agent active-map settings

All rows are **current per-new-run reads**, not proven immediate effect on an in-flight run or proof of active-revision convergence. `AgentBusinessRunPolicyProvider.java:106-107,241-275` takes a copy of `active()`; store absence yields empty settings, store errors fail the run. `DefaultAgentCheckpointBridge.java:105-116` reads active settings separately and forwards **only `model(...)`**.

| Active key | Current consumer/default/validation | Proposed class |
|---|---|---|
| `maxAgentTurns` | Definition `maxTurns`; fallback `agent.run.max-turns` default `8`, startup fallback must be `1..32`; active number accepted `1..32`, otherwise fallback. `AgentBusinessConfiguration.java:35-39`, `AgentBusinessRunPolicyProvider.java:80-88,248,332-342`. | R; new ordinary runs |
| `maxToolCalls` | Ordinary definition default `10`, range `1..1000`; checkpoint fixed `10`. `AgentBusinessRunPolicyProvider.java:251,332-342`; `DefaultAgentCheckpointBridge.java:105`. | R only after checkpoint semantics explicitly distinguished |
| `runTimeoutMs` | Ordinary definition default `180000 ms`, range `1000..600000`; used as HTTP deadline and watchdog. Checkpoint definition/HTTP timeout fixed `180000 ms`, wait `185 s`. `AgentBusinessRunPolicyProvider.java:252`; `HttpAgentEngineBridge.java:79-101`; `DefaultAgentCheckpointBridge.java:105-106,134-140`. | R for ordinary new runs; not checkpoint-hot |
| `officeInstructions` | Nonblank text appended to fixed office instructions for office runs. `AgentBusinessRunPolicyProvider.java:345-353`. | R, privileged behavioral policy |
| `companyInstructions` | Nonblank text appended for company runs. Same anchor. | R, privileged behavioral policy |
| `replyInstructions` | Nonblank text appended for both sources. Same anchor. | R, privileged behavioral policy |
| `modelId` | Nonblank text; model included only if **all** listed model fields pass validation. `AgentBusinessRunPolicyProvider.java:276-309`. | R, new ordinary and checkpoint runs |
| `modelBaseUrl` | Nonblank text; same all-or-nothing model validation; no URL validation shown in this consumer. | R, security-sensitive endpoint |
| `modelApiKey` | String required, but this consumer accepts an empty string; forwarded in model map. Same anchor. | S/R secret; masked/write-only UI, no value in audit |
| `contextWindow` | Number, `>0`, must exceed `maxOutputTokens`; same anchor. | R |
| `maxOutputTokens` | Number, `>0`, less than `contextWindow`; same anchor. | R |
| `reasoning` | Boolean required; same anchor. | R |
| `thinkingLevel` | Nonblank; one of `off`, `low`, `medium`, `high`; same anchor. | R |
| `supportsDeveloperRole` | Boolean required; same anchor. | R |
| `supportsReasoningEffort` | Boolean required; same anchor. | R |
| `supportsUsageInStreaming` | Boolean required; same anchor. | R |
| `maxTokensField` | Nonblank; `max_completion_tokens` or `max_tokens`; same anchor. | R |

**Runtime honesty:** model invalidity causes this consumer to omit the whole model object; it does not independently report an applied-model revision. Integer values outside bounds fall back rather than failing at this consumer. The normal bridge’s 120-second configuration is thus not the ordinary business-run deadline when the normal policy is used.

### Identity and task settings

| Key | Existing default/source; validation and consumer | Proposed class |
|---|---|---|
| `iot.identity.verify` | `true`; `${IOT_IDENTITY_VERIFY}`. When false, public agent requests still require authorization and tenant headers, but use presented principal without upstream verification. `SecurityConfiguration.java:60-62`, `AgentIdentityFilter.java:43-67`. Startup. | S; false is D-only |
| `iot.identity.verify-timeout-ms` | `10000 ms`; `${IOT_IDENTITY_VERIFY_TIMEOUT_MS}`. | S/B |
| `iot.identity.refresh-interval-ms` | `60000 ms`; `${IOT_IDENTITY_REFRESH_INTERVAL_MS}`. | S/B |
| `iot.identity.max-validation-age-ms` | `86400000 ms`; `${IOT_IDENTITY_MAX_VALIDATION_AGE_MS}`. | S/B |
| `iot.identity.retry-cooldown-ms` | `60000 ms`; `${IOT_IDENTITY_RETRY_COOLDOWN_MS}`. | S/B |
| Identity four durations | All must be at least 1 ms; refresh cannot exceed maximum age. Blank gateway yields fail-closed verifier; constructed once. `SecurityConfiguration.java:31-43`, `AccountIdentityCachePolicy.java:5-29`. | Security-reviewed, not casual UI controls |
| `TASK_CREDENTIAL_KEY` | Preferred nested placeholder, default falls through to `agent.task.credential-key`, then blank. `TaskConfiguration.java:70-75`. | S/B secret |
| `agent.task.credential-key` | Fallback if the upper-case property is absent, default blank. Same anchor. Blank permits store construction but use requiring key fails; nonblank must be 64 hex characters / 32 bytes. AES-GCM, random 12-byte IV, 16-byte tag and owner-key AAD: `JdbcTaskExecutionIdentityStore.java:30-45,85-123,135-152`. Key change without migration risks old ciphertext becoming unreadable. | S/B secret; rotation/migration required |
| `agent.task.scope-timeout-ms` | `30000 ms`, no packaged entry; `TaskConfiguration.java:106-119` clamps to at least `1000 ms` for real task gateway and safety reader. Startup. | B |
| `agent.task.recurrence.batch-size` | `25` items, no packaged entry; `StorageConfiguration.java:132-139`; constructor requires `1..100` (`JdbcTaskRecurrenceMaterializer.java:43-56`). Startup. | B, operational |
| `agent.task.recurrence.poll-ms` | `1000 ms`; no packaged entry; scheduled fixed delay `JdbcTaskRecurrenceLoop.java:17-18`. Startup scheduler resolution; no local bounds shown. | B |
| `agent.task.scanner.poll-ms` | `1000 ms`; no packaged entry; `TaskScanLoop.java:17-18`. Startup scheduler resolution; no local bounds shown. | B |
| `agent.action-draft-ttl-ms` | `600000 ms`, no packaged entry; `ActionDraftConfiguration.java:78-82` clamps to at least 1 ms, startup. | B or future R with explicit lifecycle semantics |
| `iot.gateway.readback-wait-ms` | `6000 ms`, no packaged entry; action gateway clamps to `>=0`; `ActionDraftConfiguration.java:41-48`; startup. | B |

### IoT gateway routes and timeout

All gateway rows are Spring properties with startup-constructed consumers. Paths are **integration/security-sensitive**, not unrestricted ordinary admin input. `IotGatewayRoutes.java:43-56` validates paths; adapter construction catches invalid configuration and fails closed. Unless noted, defaults below are from `IotAdapterConfiguration.java:53-73`; packaged entries and environment placeholders are at `application.yml:37-56`.

| `iot.gateway.*` suffix | Existing default | Consumer / caveat | Proposed |
|---|---|---|---|
| `base-url` | blank | IoT, observations, account verifier, actions and snapshot namespace; `IotAdapterConfiguration.java:53`, `ObservationAdapterConfiguration.java:48-52`, `SecurityConfiguration.java:32-38`, `StorageConfiguration.java:193-197`. | B/S endpoint |
| `office-scope-path` | blank | Required by wiring even if office directory routes exist; blank fails entire adapter (`IotAdapterConfiguration.java:75-77`). | B |
| `company-scope-path` | blank | Fallback paginated scope; configured company catalog takes precedence (`WebClientIotAdapter.java:90-101`). Not inherently dead. | B |
| `office-membership-path` | `/micro-api/api/office/office/member/my/office` | Office directory membership. | B |
| `office-ac-path` | `/micro-api/api/office/app/device/ac/myOffice/pageByAc` | Office AC source. | B |
| `office-lighting-path` | `/micro-api/api/office/device/page` | Office lighting source. | B |
| `office-access-path` | `/micro-api/api/office/device/page` | Office access source. | B |
| `office-video-path` | `/micro-api/api/office/device/page` | Office video source. | B |
| `office-ac-association-path` | `/micro-api/api/office/acAreaDevice/page` | Office association. | B |
| `office-lighting-association-path` | `/micro-api/api/office/lightingAreaDevice/page` | Office association. | B |
| `office-access-association-path` | `/micro-api/api/office/accessAreaDevice/page` | Office association. | B |
| `company-environment-path` | `/micro-api/api/iot-center/device/page` | Company catalog; **no packaged YAML entry**. | B |
| `company-energy-path` | `/micro-api/api/energy/equipment/page` | Company catalog. | B |
| `company-safety-path` | `/micro-api/api/perception/device/page` | Company catalog. | B |
| `company-safety-detail-path` | `/micro-api/api/iot-center/device` | Company route plus safety metadata reader (`ObservationAdapterConfiguration.java:140-147`). | B |
| `company-smart-base-path` | `/micro-api/api/office/app/device` | Company catalog. | B |
| `company-ac-area-path` | `/micro-api/api/office/app/device/ac/myAreaList` | Company catalog. | B |
| `company-ac-device-path` | `/micro-api/api/office/app/device/ac/devicePage` | Company catalog. | B |
| `company-ac-binding-path` | `/micro-api/api/iot-center/device/page` | Company catalog. | B |
| `company-monitor-down-path` | `/micro-api/api/office/app/device/monitor/down` | Company video path; **no packaged YAML entry**; consumed `WebClientIotAdapter.java:1046-1049`. | B |
| `company-lighting-down-path` | `/micro-api/api/office/app/device/lighting/down` | Action command gateway; **no packaged YAML entry**, `ActionDraftConfiguration.java:43-47`. | B, control-sensitive |
| `company-access-down-path` | `/micro-api/api/office/app/device/access/down` | Same, `:44-47`; no packaged entry. | B, control-sensitive |
| `company-ac-down-path` | `/micro-api/api/office/app/device/ac/down` | Same, `:45-47`; no packaged entry. | B, control-sensitive |
| `call-timeout-ms` | YAML `30000 ms` | Same key has Java fallbacks **30000** IoT/action command, **15000** observation, **10000** action scope (`IotAdapterConfiguration.java:73`, `ObservationAdapterConfiguration.java:48`, `ActionDraftConfiguration.java:41,78`). Packaged YAML normally overrides these divergent fallbacks; action values clamp `>=1`, adapter validates/fails closed. | B; reconcile per-consumer meaning |

### Observation routes and cursor secret

`ObservationAdapterConfiguration.java:54-87,153-155` constructs keys as `iot.observation.routes.<lowercase-operation-with-hyphens>`. Blank values fall back to the code fallback, **not necessarily disabled**. Routes must start `/`, exclude query/fragment, and only supported ledger templates are allowed (`ObservationGatewayRoutes.java:11-26,39-56`). All are startup-bound.

| Suffix | Packaged default | Effective code fallback / consumer status | Proposed |
|---|---|---|---|
| `environment-history` | `/micro-api/api/iot-center/env/history/page` | Same; raw observation. | B |
| `energy-history` | `/micro-api/api/energy/electricData/page` | Same; raw history feeds company energy compositions. | B |
| `dashboard` | blank | No fallback; composed behavior may handle operation. | B, capability-specific |
| `safety-events` | blank | **Blank resolves to** `/micro-api/api/perception/fire/{ledger}/alarm/page`; office and company safety composition. | B |
| `safety-event` | blank | **Not inserted into configured route map**. Event detail resolved by local ledger composition, not raw upstream route (`ObservationAdapterConfiguration.java:54-87`, `WebClientObservationAdapter.java:172-175,449-454`). Packaged key is dead in this wiring. | Remove/reconcile; not UI |
| `safety-event-cameras` | blank | No route fallback; company composition handles event-camera flow; do not assume a configured raw route is the sole capability. | B, investigate end-to-end before exposing |
| `energy-comparison` | blank | No fallback; company energy is composed. | B only if raw seam demonstrably needed |
| `energy-report` | blank | No fallback; company energy is composed. | B only if raw seam demonstrably needed |
| `company-energy-daily` | blank | No fallback; composed. | B only if needed |
| `company-energy-report` | blank | No fallback; composed. | B only if needed |
| `company-meter-report` | blank | No fallback; composed. | B only if needed |
| `video-stream` | blank | No fallback; other IoT video path exists. | B, capability-specific |
| `report` | blank | No fallback; artifact protocol unavailable absent customer contract per configuration comment. | B, capability-specific |
| `report-download` | blank | Same. | B, capability-specific |
| `region-list` | **No packaged entry** | `/micro-api/api/office/office/member/my/office`; dynamic property is read and maps office observation regions. `ObservationAdapterConfiguration.java:84-86`. | B |
| `iot.observation.safety-cursor-secret` | `${IOT_OBSERVATION_SAFETY_CURSOR_SECRET:${CONFIG_SECRET_KEY:}}`, otherwise blank | `ObservationAdapterConfiguration.java:118-133`: 64 hex chars decoded to 32 bytes, otherwise UTF-8 text accepted if at least 16 bytes; invalid/blank yields null and composer uses ephemeral key. Restart invalidates cursors with ephemeral key. | S/B secret; plan rotation and cursor invalidation |
| `CONFIG_SECRET_KEY` | Nested YAML fallback only in this scope | Shared fallback to cursor secret; do not imply independent key isolation. `application.yml:73-76`. | S/B secret |

### Hardcoded operational policies and invariants

These are **current constants**, not existing console-configurable keys. A proposed category is not evidence of implementation or hot application.

| Policy / current value | Evidence and significance | Proposed treatment |
|---|---|---|
| Conversation execution: max 256 virtual-thread workers, synchronous queue, 60 s keepalive; one scheduler thread | `ConversationConfiguration.java:29-40` | B candidate after load analysis; not current UI |
| Identity verified-cache capacity 4096; refresh pool 2 threads, queue 64, 30 s keepalive | `CachedAccountPrincipalVerifier.java:22-24,52-59` | S/B capacity; freshness bounds remain security-reviewed |
| Identity upstream route `/sso-api/account/me`; blocking extra 250 ms | `WebClientAccountPrincipalVerifier.java:23-26,40-42` | I protocol contract |
| Video CORS preflight max-age 600 s; GET/OPTIONS only, no credentials | `VideoStreamCorsFilter.java:48-54` | S/I |
| Ordinary agent scope/tool/reference timeouts each 30 s | `AgentBusinessRunPolicyProvider.java:43`, `AgentBusinessToolDispatcher.java:52`, `AgentReferenceResolver.java:37` | B candidate only after deadline hierarchy design |
| Ordinary bridge connect and cancel 2 s | `HttpAgentEngineBridge.java:67,157-160` | B candidate |
| Checkpoint connect 10 s, run 180 s, wait 185 s, cancel 5 s, meter read 45 s; max tool calls 10 | `DefaultAgentCheckpointBridge.java:56,105-106,134-140,189-191,268-270` | B candidate; separate from active ordinary-run settings |
| Agent run guard retention 15 min | `AgentRunGuard.java:14-16` | I/S lifecycle |
| Conversation active window 6 h | `ConversationFacade.java:30,218-220` | Business policy candidate; not currently configurable |
| Action ordinary state read 120 s | `ActionDraftFacade.java:51,724-725` | B candidate |
| IoT office scope reuse 5 s; company only shares concurrent load | `WebClientIotAdapter.java:57,90-130` | S/B; authorization freshness, not cosmetic cache setting |
| IoT state deadline 25 s; composition deadline at least 120 s | `WebClientIotAdapter.java:59-63,79-81` | B candidate, maintain relationship to caller deadlines |
| Device controller adapter deadline 120 s; observation normal 30 s and rebuild 150 s | `IotDeviceController.java:37-38`, `ObservationController.java:44-58` | B candidate, coordinate hierarchy |
| Company energy max pages 60, rows 1200, meter and page concurrency 6, read budget 120000 ms, max 90 days | `CompanyEnergyObservationComposerImpl.java:51-59,103-112` | B candidate for performance caps; max-days may be product contract; constructor bounds pages `1..1000`, rows `1..100000` |
| Energy history current TTL 60 s, 16 entries, 60000 samples, 8 coverages/meter; one-day anchor | `EnergyHistoryStore.java:25-31,193-196` | B candidate for resource knobs; anchor/freshness semantics require domain review |
| Read snapshots: 2048 entries, 30 min idle, 7 d retention, retry 60 s; current refresh 60 s, historical next Shanghai midnight | `ReadSnapshots.java:100-104,130-138` | B/S candidate; avoid calling hot. Settings require positive capacity/expiry/retention and retry `>=60 s` |
| Snapshot workers: upstream 8 / queue 128; storage 1 / queue 4096; cadence 5 s; drain 3 s | `ReadModelConfiguration.java:27-49` | B candidate |
| Snapshot persistence wait 2 s; Redis command/connect 2 s, shutdown 100 ms | `SnapshotReadModel.java:214-215`, `StorageConfiguration.java:60-70` | B candidate |
| Safety ledger page reads/request 3, detail pages 5, metadata concurrency 3 | `SafetyLedgerComposer.java:57-59` | B candidate, preserve partial-result behavior |
| Safety cursor limits: length 2200, page 10000, offset 100, before field 700 | `SafetyLedgerComposer.java:60-63,479-525` | S/I input-abuse and signed-protocol bounds; not UI knobs |
| Safety metadata retry once, delay/max backoff 500 ms | `WebClientSafetyMetadataReader.java:23-24,50-53` | B candidate |
| Candidate presentation 50, grouping 100; device-context vocabulary 8 | `DeviceCandidateMatcher.java:24-25,132-158`, `AgentDeviceContext.java:27,70-72` | I/product presentation, not scope authorization limits |
| Task scanner due batch 4 and report retry batch 4 | `TaskScanner.java:58-63,120-126` | B candidate; preserve CAS and no duplicate dispatch |
| Task proposal TTL 15 min; elapsed occurrence grace 10 min | `TaskFacade.java:113-115,175-176,206-207,454-455`, `JdbcTaskRecurrenceMaterializer.java:32-35` | Business/safety lifecycle; do not casually expose |
| Task safety ledger page size 100, max 20 pages | `ObservationSafetyLedgerReader.java:25,58-60`, `RealTaskExecutionGateway.java:50-51,358-360` | B candidate only with completeness/partial-result semantics |
| AC cadence 15/30/60 min; max 48 checkpoints; max window 24 h; temperature default 26°C, range 16–30°C | `TaskProposalNormalizer.java:36-41,478-486` | I/domain and safety constraints, not ordinary knobs |
| AC estimation assumptions: 0.07 per degree, 2.5 kW, 0.8 per kWh | `RealTaskExecutionGateway.java:44-48` | **Business assumptions**, not infrastructure; propose explicit sourced/versioned policy only after domain review, not silent tuning |
| Task notification page limit 100; task calendar range 62 days | `TaskFacade.java:784-787`, `TaskController.java:269-272` | I/API limits |
| Pi session lease 600000 ms | `JdbcPiSessionService.java:25-26` | S/I lease semantics |
| PDF 44 lines/page, 78 chars/line | `ReportPdfRenderer.java:18-20` | I rendering invariant |
| Shanghai timezone and fixed day arithmetic | `CompanyEnergyObservationComposerImpl.java:48-61`, `ReadSnapshots.java:100-104` | I/domain calendar semantics; not generic operator timezone setting |
| Scoped key maximum 128; password-policy 6 minimum/1024 maximum, PBKDF iterations 210000, key bits 256 | `ScopedKey.java:15-16`, `AdminPasswordPolicy.java:9-13` | S/I protocol/security; public admin class is in scope, admin internals were not inspected |

### Prioritized gaps and safe design implications

1. **P0 — bridge credential split.** Dotted outbound and hyphenated inbound properties are distinct declarations; packaged configuration supplies only dotted form. Health checks the outbound pair only and can say “configured” while inbound callbacks reject. Define one explicit credential contract and rotation plan; do not print either value. Evidence: `SecurityConfiguration.java:67-69`, `ConversationConfiguration.java:55-57`, `HealthController.java:37-41`, `BridgeAuthenticationFilter.java:33-47`.

2. **P0 — honest active revisions and deadlines.** Store contract distinguishes saved/active revision, but ordinary run and checkpoint consumers use different subsets and different deadlines. A console must report actual per-consumer adoption, distinguish new versus in-flight work, and never present a save or store promotion as proof all clients applied it. Implementation of store/apply is outside this audit.

3. **P0 — security boundaries.** Keep identity verification enabled outside controlled fixtures; keep task credential key, bridge tokens, database URLs/passwords, model API key, and cursor MAC key masked/write-only and out of logs/audit values. Task-key rotation needs ciphertext migration; cursor-key rotation invalidates prior cursors.

4. **P1 — default/fallback drift.** Reconcile `call-timeout-ms`’s four consumer defaults; ordinary bridge nominal 120 s versus definition default 180 s; report blank build ID behavior; safety-events blank YAML versus nonblank effective fallback. Surface effective value and source rather than YAML text alone.

5. **P1 — dead/misleading and missing route declarations.** Packaged `safety-event` is not mapped by the observation configuration. Conversely `region-list`, company environment/monitor and three action down routes are consumed without packaged entries. Company scope fallback is conditional rather than simply dead. Avoid exposing a route until the actual composed/raw consumer is traced.

6. **P1 — office/company default availability.** Blank office scope makes the shared adapter fail closed before company catalog can be used. Show both source readiness independently, and distinguish configured routes from actual authenticated upstream readiness; do not infer readiness from property presence.

7. **P2 — classify rather than expose every constant.** Resource limits may eventually be bounded bootstrap settings; cryptographic limits, domain constraints, authorization freshness, task dispatch/recovery and protocol formats need security/domain ownership. Any proposed runtime conversion requires explicit validation and actual consumer wiring.

### Existing high-level verification seams—not executed

| Seam | Evidence |
|---|---|
| Full Spring/MockMvc runtime contract with verification disabled and heartbeat disabled | `src/test/java/ltd/qianc/iotagent/RuntimeContractGateTest.java:37-43` |
| Active-map ordinary run definition, model and instructions | `src/test/java/ltd/qianc/iotagent/agent/AgentBusinessDefinitionContractTest.java:285-298,526-530` |
| Internal bridge boundary and video CORS | `src/test/java/ltd/qianc/iotagent/auth/AgentBoundarySecurityTest.java:20-24`; `src/test/java/ltd/qianc/iotagent/auth/VideoStreamCorsTest.java:23-28` |
| Real HTTP bridge contract | `src/test/java/ltd/qianc/iotagent/conversation/AgentEngineBridgeContractTest.java:47-50` |
| Account verification, realm isolation and office/company invalidation | `src/test/java/ltd/qianc/iotagent/auth/AccountPrincipalVerifierTest.java:101-121,280-320` |
| Recurrence materializer isolated DB seam | `src/test/java/ltd/qianc/iotagent/storage/JdbcTaskRecurrenceMaterializerTest.java:23-40` |
| Explicitly gated real storage integration seam | `src/test/java/ltd/qianc/iotagent/storage/StorageIntegrationTest.java:19-25`; do not run against real services for this audit. |

No tests, builds, lint, real services, database mutations, or secret-value reads were performed.
