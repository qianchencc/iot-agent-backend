# Independent Pi Agent Sidecar

This directory is a separately buildable Node 22 service. It owns the published `pi-agent-core`, `pi-ai`, and Pi Session SQLite integration. It has **no runtime import, process invocation, workspace dependency, or filesystem dependency** on `../iot-agent-runtime`.

## Install, build, and run

Use Node `>=22.19.0 <23` from this directory:

```sh
npm install
npm run build
npm start
```

`package-lock.json` is generated and maintained in this directory with `npm install --package-lock-only` when dependency versions change. Deployment installs with `npm ci` and then runs `npm run build`; no parent repository package or workspace is required.

## Configuration

All configuration is environment-driven. Production must set:

- `SIDECAR_HOST` (default `127.0.0.1`) and `SIDECAR_PORT` (default `8787`)
- `AGENT_BRIDGE_TOKEN` for Java-to-sidecar bearer authentication
- `SIDECAR_SUPERVISOR_TOKEN` for the separate admin handoff identity
- `JAVA_AGENT_TOOLS_URL` and `JAVA_AGENT_TOOLS_TOKEN` for the authenticated callback to Java `/internal/agent/tools`
- `MODEL_BASE_URL`, `MODEL_ID`, and `MODEL_API_KEY` for the OpenAI-compatible model endpoint
- `SIDECAR_SESSION_PATH` (default `./var/pi-agent/sessions.sqlite`)
- `SIDECAR_BACKUP_DIR` (default `./var/pi-agent/backups`)
- optional `SIDECAR_MINIMUM_FREE_BYTES`, `SIDECAR_RUN_TIMEOUT_MS`, and `SIDECAR_HEARTBEAT_MS`

Keep the sidecar SQLite volume separate from Java MySQL. Bind loopback unless an external private network is explicitly required. Never put a model key, bridge token, callback token, URL, or local path in health responses or operational errors.

## HTTP contract

Every regular route requires `Authorization: Bearer $AGENT_BRIDGE_TOKEN`; the supervisor handoff route instead requires `X-Admin-Supervisor-Token` (and never accepts an unrecognized identity). `POST /run` accepts the Java bridge fields (`runId`, `toolContextId`, `conversationId`, `turnId`, `source`, `content`, `tenantCode`, and optional `focusRegionId`/`reference`) and streams `application/x-ndjson`. Only `run.*`, `message.*`, `tool.*`, `action.*`, `task.*`, `observation.*`, and `heartbeat` are emitted. A run can be stopped with authenticated `POST /cancel/:runId`.

The sidecar calls Java, rather than exposing a public tool endpoint. Callback requests contain `toolContextId`, `conversationId`, `turnId`, `toolName`, and `arguments`, and use `X-Agent-Bridge-Token: $JAVA_AGENT_TOOLS_TOKEN`.

Authenticated operations are:

- `POST /internal/pi-session/capacity`
- `POST /internal/pi-session/integrity-check`
- `POST /internal/pi-session/writer-lease/recover`
- `POST /internal/pi-session/backup` (`{destination,force}`; destination MUST be inside `SIDECAR_BACKUP_DIR`)
- `POST /internal/pi-session/restore` (`{backup,apply:true}`; source MUST be inside `SIDECAR_BACKUP_DIR`)
- `POST /internal/admin/apply` with `X-Admin-Supervisor-Token` and `{handoff:"restart"}`; only then is `{accepted:true}` returned
- `GET /healthz` (safe status only, still authenticated)

Integrity uses SQLite `PRAGMA quick_check`; backups use `VACUUM INTO`; restore verifies the backup before an atomic replacement. Responses redact filesystem paths and credentials.

## Tests

The tests are local contract tests and use in-process Fastify plus local fake HTTP servers only. They do not contact customer gateways, cloud services, Java production, or the historical Runtime. Run them with `npm test` after installing dependencies.
