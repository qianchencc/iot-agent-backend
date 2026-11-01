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
- `JAVA_AGENT_TOOLS_URL` and `JAVA_AGENT_TOOLS_TOKEN` for the authenticated callback to Java `/internal/agent/tools`
- `MODEL_BASE_URL`, `MODEL_ID`, and `MODEL_API_KEY` as the bootstrap model endpoint; a run's `definition.model` snapshot overrides them (production Java supplies the snapshot)
- `SIDECAR_SESSION_PATH` (default `./var/pi-agent/sessions.sqlite`)
- `SIDECAR_BACKUP_DIR` (default `./var/pi-agent/backups`)
- optional `SIDECAR_MINIMUM_FREE_BYTES`, `SIDECAR_RUN_TIMEOUT_MS`, `JAVA_AGENT_TOOL_TIMEOUT_MS`, and `SIDECAR_HEARTBEAT_MS`

Keep the sidecar SQLite volume separate from Java MySQL. Bind loopback unless an external private network is explicitly required. Never put a model key, bridge token, callback token, URL, or local path in health responses or operational errors.

## HTTP contract

Every route requires `Authorization: Bearer $AGENT_BRIDGE_TOKEN`. `POST /run` accepts the versioned generic definition below and streams `application/x-ndjson`. The sidecar owns no business tool, schema, prompt, status parser, or public business-event projection.

```json
{
  "protocolVersion": 1,
  "runId": "…",
  "conversationId": "…",
  "turnId": "…",
  "toolContextId": "…",
  "content": "…",
  "sessionScope": "opaque owner and source namespace",
  "definition": {
    "instructions": "system instructions supplied by Java",
    "tools": [
      {
        "name": "…",
        "label": "…",
        "description": "…",
        "parameters": { "type": "object", "properties": {} }
      }
    ],
    "maxTurns": 8,
    "maxToolCalls": 10,
    "runTimeoutMs": 120000,
    "model": {
      "modelId": "…",
      "modelBaseUrl": "https://model.example/v1",
      "modelApiKey": "…",
      "contextWindow": 128000,
      "maxOutputTokens": 16384,
      "reasoning": true,
      "thinkingLevel": "low",
      "supportsDeveloperRole": false,
      "supportsReasoningEffort": true,
      "supportsUsageInStreaming": true,
      "maxTokensField": "max_completion_tokens"
    }
  }
}
```

Unknown fields are rejected, so no tenant, source, focus, reference, or other business context travels through the engine. `definition.maxToolCalls` (1–1000) is enforced cumulatively: once reached, further tool attempts fail explicitly without a Java callback. `definition.runTimeoutMs` (1000–600000) overrides the configured run timeout for that request. When `definition.model` is present all eleven keys are required; every run builds its Pi model, thinking level, and API key from that immutable snapshot. The model key is never logged, traced, or emitted. Omitting `model` retains the sidecar configuration bootstrap for engineering fixtures only; production Java supplies the snapshot. Public NDJSON is limited to `run.started`, `run.progress`, `run.completed`, `run.failed`, `run.aborted`, `message.delta`, `message.completed`, `message.reclassified`, `tool.started`, `tool.progress`, `tool.completed`, `tool.result`, and `heartbeat`. A successful run always ends with `run.completed`; Java projects confirmation state from `tool.result` and `halt`. A run can be stopped with authenticated `POST /cancel/:runId`.

The sidecar calls Java, rather than exposing a public tool endpoint. Callback requests contain only `protocolVersion`, `runId`, `toolContextId`, `conversationId`, `turnId`, `toolName`, `toolCallId`, and `arguments`, and use `X-Agent-Bridge-Token: $JAVA_AGENT_TOOLS_TOKEN`. A successful callback returns `{"protocolVersion":1,"result":…,"halt":<boolean>}`; HTTP errors become tool errors. `halt:true` stops the generic loop after the current turn. Tool arguments are validated against the supplied JSON Schema by the installed Pi validator before the callback, with no evaluation of schema text. Session history is restored through the Pi session API for the opaque `sessionScope`, including compaction context.

Authenticated operations are:

- `POST /internal/pi-session/capacity`
- `POST /internal/pi-session/integrity-check`
- `POST /internal/pi-session/writer-lease/recover`
- `POST /internal/pi-session/backup` (`{destination,force}`; destination MUST be inside `SIDECAR_BACKUP_DIR`)
- `POST /internal/pi-session/restore` (`{backup,apply:true}`; source MUST be inside `SIDECAR_BACKUP_DIR`)
- `GET /healthz` (safe status only, still authenticated)

There is no supervisor/admin handoff endpoint. An external supervisor calling `POST /internal/admin/apply` (even with its supervisor header and no bridge bearer) gets an unmatched-route 404; the sidecar never falsely reports a scheduled restart. Model, prompt, and limit changes apply through the per-run private policy and do not require a restart.

Integrity uses SQLite `PRAGMA quick_check`; backups use `VACUUM INTO`; restore verifies the backup before an atomic replacement. Responses redact filesystem paths and credentials.

## Tests

The tests are local contract tests and use in-process Fastify plus local fake HTTP servers only. They do not contact customer gateways, cloud services, Java production, or the historical Runtime. Run them with `npm test` after installing dependencies.
