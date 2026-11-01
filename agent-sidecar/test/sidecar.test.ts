import { afterEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer, type IncomingMessage, type Server } from "node:http";
import { buildApp } from "../src/app.js";
import { defaultConfigForTests, type SidecarConfig } from "../src/config.js";
import type { Observability } from "../src/observability.js";

const servers: Server[] = [];
const directories: string[] = [];

afterEach(async () => {
  for (const server of servers.splice(0)) {
    const closed = Promise.withResolvers<void>();
    server.close(() => closed.resolve());
    await closed.promise;
  }
  for (const directory of directories.splice(0)) await rm(directory, { recursive: true, force: true });
});

async function config(overrides: Partial<SidecarConfig> = {}): Promise<SidecarConfig> {
  const directory = await mkdtemp(join(tmpdir(), "agent-sidecar-test-"));
  directories.push(directory);
  return defaultConfigForTests({ sessionPath: join(directory, "sessions.sqlite"), backupDir: join(directory, "backups"), ...overrides });
}

function auth(): Record<string, string> {
  return { authorization: "Bearer test-bridge" };
}

const LOOKUP_TOOL: Record<string, unknown> = {
  name: "lookup_records",
  label: "Lookup records",
  description: "Look up records using a query.",
  parameters: {
    type: "object",
    properties: { query: { type: "string" } },
    required: ["query"],
    additionalProperties: false,
  },
};

function runBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    protocolVersion: 1,
    runId: "run-1",
    toolContextId: "context-1",
    conversationId: "conversation-1",
    turnId: "turn-1",
    content: "hello",
    sessionScope: "scope-1",
    definition: {
      instructions: "Use a tool only when it provides evidence needed for the request.",
      tools: [],
      maxTurns: 4,
    },
    ...overrides,
  };
}

function definition(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    instructions: "Use a tool only when it provides evidence needed by the request.",
    tools: [],
    maxTurns: 4,
    ...overrides,
  };
}

function modelSettings(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    modelId: "snapshot-model",
    modelBaseUrl: "http://127.0.0.1:9/v1",
    modelApiKey: "snapshot-secret-key",
    contextWindow: 32_768,
    maxOutputTokens: 4_096,
    reasoning: true,
    thinkingLevel: "medium",
    supportsDeveloperRole: true,
    supportsReasoningEffort: true,
    supportsUsageInStreaming: true,
    maxTokensField: "max_tokens",
    ...overrides,
  };
}

function events(body: string): Array<Record<string, unknown>> {
  return body.trim().split("\n").filter(Boolean).map(line => JSON.parse(line) as Record<string, unknown>);
}

const PUBLIC_EVENT_TYPES: Record<string, true> = {
  "run.started": true,
  "run.progress": true,
  "run.completed": true,
  "run.failed": true,
  "run.aborted": true,
  "message.delta": true,
  "message.completed": true,
  "message.reclassified": true,
  "tool.started": true,
  "tool.progress": true,
  "tool.completed": true,
  "tool.result": true,
  heartbeat: true,
};

function assertGenericEvents(observed: Array<Record<string, unknown>>): void {
  for (const event of observed) assert.equal(PUBLIC_EVENT_TYPES[String(event.type)], true, `unexpected event ${String(event.type)}`);
}

function recordingObservability(): { observability: Observability; seen: unknown[] } {
  const seen: unknown[] = [];
  return {
    seen,
    observability: {
      startAgentRun(input: unknown) {
        seen.push(input);
        return {
          observeEvent(event: unknown) { seen.push(event); },
          beginGeneration(input: unknown) {
            seen.push(input);
            return { finish(output: unknown) { seen.push(output); } };
          },
          finish(input: unknown) { seen.push(input); },
        };
      },
      async flush() {},
      async shutdown() {},
    },
  };
}

function openHangingModel(): Promise<{ server: Server; url: string; started: Promise<void> }> {
  const started = Promise.withResolvers<void>();
  const listening = Promise.withResolvers<{ server: Server; url: string; started: Promise<void> }>();
  const server = createServer((_request, response) => {
    started.resolve();
    response.writeHead(200, { "content-type": "text/event-stream" });
    response.write(`data: ${JSON.stringify({ id: "hanging", choices: [{ delta: { role: "assistant", content: "working" }, finish_reason: null }] })}\n\n`);
  });
  server.once("error", listening.reject);
  server.listen(0, "127.0.0.1", () => {
    servers.push(server);
    const address = server.address();
    if (!address || typeof address === "string") {
      listening.reject(new Error("hanging model address unavailable"));
      return;
    }
    listening.resolve({ server, url: `http://127.0.0.1:${address.port}/v1`, started: started.promise });
  });
  return listening.promise;
}

interface ModelStep {
  content?: string;
  tool?: { name: string; arguments: Record<string, unknown> };
  error?: { status: number; message: string };
}

interface FakeModel {
  server: Server;
  url: string;
  requests: unknown[];
  authorizations: string[];
}

function openModel(script: (index: number) => ModelStep): Promise<FakeModel> {
  const requests: unknown[] = [];
  const authorizations: string[] = [];
  const listening = Promise.withResolvers<FakeModel>();
  const server = createServer(async (request: IncomingMessage, response) => {
    let raw = "";
    for await (const chunk of request) raw += chunk;
    let body: unknown = null;
    try { body = raw ? JSON.parse(raw) as unknown : null; } catch { body = null; }
    requests.push(body);
    authorizations.push(typeof request.headers.authorization === "string" ? request.headers.authorization : "");
    const step = script(requests.length - 1);
    const id = `completion-${requests.length}`;
    if (step.error) {
      response.writeHead(step.error.status, { "content-type": "application/json" });
      response.end(JSON.stringify({ error: { message: step.error.message, type: "invalid_request_error" } }));
      return;
    }
    response.writeHead(200, { "content-type": "text/event-stream" });
    if (step.tool) {
      response.write(`data: ${JSON.stringify({ id, choices: [{ delta: { role: "assistant", tool_calls: [{
        index: 0,
        id: `call-${requests.length}`,
        type: "function",
        function: { name: step.tool.name, arguments: JSON.stringify(step.tool.arguments) },
      }] }, finish_reason: null }] })}\n\n`);
      response.write(`data: ${JSON.stringify({ id, choices: [{ delta: {}, finish_reason: "tool_calls" }] })}\n\n`);
    } else {
      response.write(`data: ${JSON.stringify({ id, choices: [{ delta: { role: "assistant", content: step.content ?? "" }, finish_reason: null }] })}\n\n`);
      response.write(`data: ${JSON.stringify({ id, choices: [{ delta: {}, finish_reason: "stop" }] })}\n\n`);
    }
    response.end("data: [DONE]\n\n");
  });
  server.once("error", listening.reject);
  server.listen(0, "127.0.0.1", () => {
    servers.push(server);
    const address = server.address();
    if (!address || typeof address === "string") {
      listening.reject(new Error("fake model address unavailable"));
      return;
    }
    listening.resolve({ server, url: `http://127.0.0.1:${address.port}/v1`, requests, authorizations });
  });
  return listening.promise;
}

interface JavaReply {
  status: number;
  payload: unknown;
}

interface FakeJava {
  server: Server;
  url: string;
  calls: Array<Record<string, unknown>>;
}

function openJava(reply: (call: Record<string, unknown>) => JavaReply = () => ({
  status: 200,
  payload: { protocolVersion: 1, result: { records: [{ id: "record-1" }] }, halt: false },
})): Promise<FakeJava> {
  const calls: Array<Record<string, unknown>> = [];
  const listening = Promise.withResolvers<FakeJava>();
  const server = createServer(async (request, response) => {
    let raw = "";
    for await (const chunk of request) raw += chunk;
    if (request.headers.authorization !== "Bearer test-java"
        || request.headers["x-agent-bridge-token"] !== "test-java") {
      response.writeHead(401);
      response.end();
      return;
    }
    const call = JSON.parse(raw) as Record<string, unknown>;
    calls.push(call);
    const result = reply(call);
    response.writeHead(result.status, { "content-type": "application/json" });
    response.end(JSON.stringify(result.payload));
  });
  server.once("error", listening.reject);
  server.listen(0, "127.0.0.1", () => {
    servers.push(server);
    const address = server.address();
    if (!address || typeof address === "string") {
      listening.reject(new Error("fake Java address unavailable"));
      return;
    }
    listening.resolve({ server, url: `http://127.0.0.1:${address.port}/internal/agent/tools`, calls });
  });
  return listening.promise;
}

describe("private sidecar HTTP contract", () => {
  it("rejects missing and invalid bearer credentials on registered routes", async () => {
    const app = buildApp(await config());
    const missing = await app.inject({ method: "GET", url: "/healthz" });
    assert.equal(missing.statusCode, 401);
    const invalid = await app.inject({ method: "POST", url: "/internal/pi-session/capacity", headers: { authorization: "Bearer wrong" } });
    assert.equal(invalid.statusCode, 401);
    await app.close();
  });

  it("returns only safe health fields and rejects malformed v1 run requests", async () => {
    const app = buildApp(await config());
    const health = await app.inject({ method: "GET", url: "/healthz", headers: auth() });
    assert.equal(health.statusCode, 200);
    assert.deepEqual(Object.keys(health.json()).sort(), ["activeRuns", "node", "service", "status"]);

    const malformed = [
      { content: "missing identity" },
      runBody({ protocolVersion: 2 }),
      runBody({ source: "unsupported" }),
      runBody({ definition: definition({ maxTurns: 0 }) }),
      runBody({ definition: definition({ maxTurns: 33 }) }),
      runBody({ definition: definition({ instructions: 4 }) }),
      runBody({ definition: definition({ tools: [{ ...LOOKUP_TOOL, parameters: [] }] }) }),
      runBody({ definition: definition({ tools: [LOOKUP_TOOL, LOOKUP_TOOL] }) }),
      runBody({ sessionScope: "" }),
      runBody({ definition: definition({ extra: true }) }),
      runBody({ definition: definition({ maxToolCalls: 0 }) }),
      runBody({ definition: definition({ maxToolCalls: 1_001 }) }),
      runBody({ definition: definition({ runTimeoutMs: 999 }) }),
      runBody({ definition: definition({ runTimeoutMs: 600_001 }) }),
      runBody({ definition: definition({ model: modelSettings({ modelApiKey: undefined }) }) }),
      runBody({ definition: definition({ model: modelSettings({ extra: true }) }) }),
      runBody({ definition: definition({ model: modelSettings({ contextWindow: 0 }) }) }),
      runBody({ definition: definition({ model: modelSettings({ maxOutputTokens: 32_768 }) }) }),
      runBody({ definition: definition({ model: modelSettings({ thinkingLevel: "xhigh" }) }) }),
      runBody({ definition: definition({ model: modelSettings({ maxTokensField: "max_output_tokens" }) }) }),
      runBody({ definition: definition({ model: modelSettings({ supportsDeveloperRole: "yes" }) }) }),
      runBody({ definition: definition({ model: modelSettings({ modelBaseUrl: "not-a-url" }) }) }),
      runBody({ definition: definition({ model: modelSettings({ modelBaseUrl: "http://user:pass@127.0.0.1/v1" }) }) }),
    ];
    for (const payload of malformed) {
      const response = await app.inject({ method: "POST", url: "/run", headers: auth(), payload });
      assert.equal(response.statusCode, 400);
    }
    await app.close();
  });

  it("supports capacity, quick-check, lease recovery, backup, restore, and redacts paths", async () => {
    const current = await config();
    const app = buildApp(current);
    const capacity = await app.inject({ method: "POST", url: "/internal/pi-session/capacity", headers: auth(), payload: {} });
    assert.equal(capacity.statusCode, 200);
    assert.equal(typeof capacity.json().freeBytes, "number");
    const integrity = await app.inject({ method: "POST", url: "/internal/pi-session/integrity-check", headers: auth(), payload: {} });
    assert.equal(integrity.statusCode, 200);
    assert.equal(integrity.json().integrity, "ok");
    const lease = await app.inject({ method: "POST", url: "/internal/pi-session/writer-lease/recover", headers: auth(), payload: {} });
    assert.equal(lease.statusCode, 200);
    const outside = await app.inject({ method: "POST", url: "/internal/pi-session/backup", headers: auth(), payload: { destination: join(current.backupDir, "..", "outside.sqlite"), force: false } });
    assert.equal(outside.statusCode, 503);
    const backupPath = join(current.backupDir, "session.sqlite");
    const backup = await app.inject({ method: "POST", url: "/internal/pi-session/backup", headers: auth(), payload: { destination: backupPath, force: false } });
    assert.equal(backup.statusCode, 200);
    assert.equal(backup.json().destination, "[sidecar-path]");
    const restore = await app.inject({ method: "POST", url: "/internal/pi-session/restore", headers: auth(), payload: { backup: backupPath, apply: true } });
    assert.equal(restore.statusCode, 200);
    assert.equal(restore.json().backup, "[sidecar-path]");
    await app.close();
  });

  it("does not expose a fake supervisor handoff", async () => {
    const app = buildApp(await config());
    const supervisorOnly = await app.inject({
      method: "POST",
      url: "/internal/admin/apply",
      headers: { "x-admin-supervisor-token": "unused" },
      payload: { handoff: "restart" },
    });
    assert.equal(supervisorOnly.statusCode, 404);
    const withBridge = await app.inject({ method: "POST", url: "/internal/admin/apply", headers: auth(), payload: { handoff: "restart" } });
    assert.equal(withBridge.statusCode, 404);
    await app.close();
  });
});

it("reopens the SQLite session store after close", async () => {
  const current = await config();
  const first = buildApp(current);
  const firstIntegrity = await first.inject({ method: "POST", url: "/internal/pi-session/integrity-check", headers: auth(), payload: {} });
  assert.equal(firstIntegrity.statusCode, 200);
  await first.close();
  const second = buildApp(current);
  const secondIntegrity = await second.inject({ method: "POST", url: "/internal/pi-session/integrity-check", headers: auth(), payload: {} });
  assert.equal(secondIntegrity.statusCode, 200);
  assert.equal(secondIntegrity.json().integrity, "ok");
  await second.close();
});

it("streams generic NDJSON and supports cancellation", async () => {
  const model = await openModel(() => ({ content: "收到" }));
  const app = buildApp(await config({ modelBaseUrl: model.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: { ...auth(), accept: "application/x-ndjson" }, payload: runBody() });
  assert.equal(run.statusCode, 200);
  const observed = events(run.body);
  assert.equal(observed[0]?.type, "run.started");
  assert.ok(observed.some(event => event.type === "message.completed"));
  assert.ok(observed.some(event => event.type === "run.completed"));
  assertGenericEvents(observed);
  const cancel = await app.inject({ method: "POST", url: "/cancel/unknown", headers: auth() });
  assert.equal(cancel.statusCode, 404);
  await app.close();
});

it("reports provider failures instead of a successful empty run", async () => {
  const model = await openModel(() => ({ error: { status: 401, message: "provider rejected request" } }));
  const app = buildApp(await config({ modelBaseUrl: model.url }));
  try {
    const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody() });
    const observed = events(run.body);
    assert.ok(observed.some(event => event.type === "run.failed"));
    assert.equal(observed.some(event => event.type === "run.completed"), false);
  } finally {
    await app.close();
  }
});

it("uses the per-run model snapshot without exposing its key", async () => {
  const model = await openModel(() => ({ content: "snapshot ok" }));
  const current = await config({ modelBaseUrl: "http://127.0.0.1:9/not-the-fake", modelApiKey: "config-key" });
  const recording = recordingObservability();
  const app = buildApp(current, recording.observability);
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "model-snapshot",
    definition: definition({ model: modelSettings({ modelBaseUrl: model.url }) }),
  }) });
  assert.equal(run.statusCode, 200);
  const observed = events(run.body);
  assert.ok(observed.some(event => event.type === "message.completed" && event.content === "snapshot ok"));
  assert.ok(observed.some(event => event.type === "run.completed"));
  assert.ok(model.authorizations[0]?.includes("snapshot-secret-key"));
  assert.equal(model.authorizations[0]?.includes("config-key"), false);
  const sent = JSON.stringify(model.requests[0]);
  assert.ok(sent.includes('"model":"snapshot-model"'));
  assert.ok(sent.includes('"max_tokens":4096'));
  assert.ok(sent.includes('"reasoning_effort":"medium"'));
  assert.equal(sent.includes("snapshot-secret-key"), false);
  assert.equal(run.body.includes("snapshot-secret-key"), false);
  assert.ok(recording.seen.length > 0);
  assert.equal(JSON.stringify(recording.seen).includes("snapshot-secret-key"), false);
  await app.close();
});

it("enforces the cumulative tool-call budget before calling Java", async () => {
  const model = await openModel(index => index < 2
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "done" });
  const java = await openJava();
  const app = buildApp(await config({ modelBaseUrl: model.url, javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "tool-budget",
    definition: definition({ tools: [LOOKUP_TOOL], maxToolCalls: 1 }),
  }) });
  const observed = events(run.body);
  assert.equal(java.calls.length, 1);
  assert.equal(observed.filter(event => event.type === "tool.result").length, 1);
  assert.equal(observed.filter(event => event.type === "tool.completed" && event.status === "failed").length, 1);
  assert.ok(model.requests.length >= 3);
  assert.ok(JSON.stringify(model.requests[2]).includes("budget exhausted"));
  assert.ok(observed.some(event => event.type === "run.completed"));
  await app.close();
});

it("uses the per-request run timeout override", async () => {
  const model = await openHangingModel();
  const app = buildApp(await config({ modelBaseUrl: model.url, runTimeoutMs: 120_000 }));
  const started = Date.now();
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "request-timeout",
    definition: definition({ runTimeoutMs: 1_000 }),
  }) });
  const observed = events(run.body);
  assert.ok(observed.some(event => event.type === "run.failed" && event.code === "AGENT_ENGINE_TIMEOUT"));
  assert.ok(Date.now() - started >= 900);
  await app.close();
});

it("aborts an active run and fails a timed-out run", async () => {
  const model = await openHangingModel();
  const app = buildApp(await config({ modelBaseUrl: model.url, runTimeoutMs: 500 }));
  const pending = app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "cancel-run", conversationId: "cancel-conversation", sessionScope: "cancel-scope", content: "wait",
  }) });
  await model.started;
  const duplicate = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "cancel-run", conversationId: "cancel-conversation", turnId: "turn-2", sessionScope: "cancel-scope", content: "repeat",
  }) });
  assert.equal(duplicate.statusCode, 409);
  const cancel = await app.inject({ method: "POST", url: "/cancel/cancel-run", headers: auth() });
  assert.equal(cancel.statusCode, 202);
  const cancelled = await pending;
  assert.ok(events(cancelled.body).some(event => event.type === "run.aborted"));
  await app.close();

  // This intentionally uses the platform timer to verify the production timeout abort path.
  const timeoutApp = buildApp(await config({ modelBaseUrl: model.url, runTimeoutMs: 20 }));
  const timedOut = await timeoutApp.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "timeout-run", conversationId: "timeout-conversation", sessionScope: "timeout-scope", content: "wait",
  }) });
  assert.ok(events(timedOut.body).some(event => event.type === "run.failed"));
  await timeoutApp.close();
});

it("forwards a tool call to Java with protocol data only and publishes the raw result", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "done" });
  const java = await openJava();
  const app = buildApp(await config({ modelBaseUrl: model.url, javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "tool-run",
    toolContextId: "tool-context",
    conversationId: "tool-conversation",
    turnId: "tool-turn",
    sessionScope: "tool-scope",
    content: "look something up",
    definition: definition({ tools: [LOOKUP_TOOL] }),
  }) });
  assert.equal(run.statusCode, 200);
  const observed = events(run.body);
  assert.ok(observed.some(event => event.type === "tool.started" && event.tool === "lookup_records"));
  const result = observed.find(event => event.type === "tool.result");
  assert.ok(result);
  assert.deepEqual(result?.result, { records: [{ id: "record-1" }] });
  assert.ok(observed.some(event => event.type === "tool.completed" && event.status === "completed"));
  assert.ok(observed.some(event => event.type === "message.completed"));
  assert.ok(observed.some(event => event.type === "run.completed"));
  assertGenericEvents(observed);

  assert.equal(java.calls.length, 1);
  assert.deepEqual(java.calls[0], {
    protocolVersion: 1,
    runId: "tool-run",
    toolContextId: "tool-context",
    conversationId: "tool-conversation",
    turnId: "tool-turn",
    toolName: "lookup_records",
    toolCallId: "call-1",
    arguments: { query: "north" },
  });
  await app.close();
});

it("forwards the Java business result without lossy redaction", async () => {
  const longResult = {
    records: Array.from({ length: 60 }, (_, index) => ({
      id: `record-${index}`,
      note: "n".repeat(600),
    })),
  };
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "done" });
  const java = await openJava(() => ({
    status: 200,
    payload: { protocolVersion: 1, result: longResult, halt: false },
  }));
  const app = buildApp(await config({ modelBaseUrl: model.url, javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "result-fidelity",
    definition: definition({ tools: [LOOKUP_TOOL] }),
  }) });
  const result = events(run.body).find(event => event.type === "tool.result");
  assert.deepEqual(result?.result, longResult);
  await app.close();
});

it("uses the Pi validator before calling Java for malformed tool arguments", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: {} } }
    : { content: "done" });
  const java = await openJava();
  const app = buildApp(await config({ modelBaseUrl: model.url, javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "invalid-args",
    definition: definition({ tools: [LOOKUP_TOOL] }),
  }) });
  const observed = events(run.body);
  assert.equal(java.calls.length, 0);
  assert.ok(observed.some(event => event.type === "tool.completed" && event.status === "failed"));
  assert.equal(observed.some(event => event.type === "tool.result"), false);
  assert.ok(observed.some(event => event.type === "run.completed"));
  await app.close();
});

it("turns Java HTTP failures into tool errors without fabricating a result", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "done" });
  const java = await openJava(() => ({ status: 403, payload: { code: "DENIED", message: "not authorized" } }));
  const app = buildApp(await config({ modelBaseUrl: model.url, javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "java-error",
    definition: definition({ tools: [LOOKUP_TOOL] }),
  }) });
  const observed = events(run.body);
  assert.equal(java.calls.length, 1);
  assert.ok(observed.some(event => event.type === "tool.completed" && event.status === "failed"));
  assert.equal(observed.some(event => event.type === "tool.result"), false);
  assert.ok(observed.some(event => event.type === "run.completed"));
  await app.close();
});

it("rejects a callback with a mismatched protocol version", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "done" });
  const java = await openJava(() => ({ status: 200, payload: { protocolVersion: 2, result: {}, halt: false } }));
  const app = buildApp(await config({ modelBaseUrl: model.url, javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "callback-protocol",
    definition: definition({ tools: [LOOKUP_TOOL] }),
  }) });
  const observed = events(run.body);
  assert.ok(observed.some(event => event.type === "tool.completed" && event.status === "failed"));
  assert.equal(observed.some(event => event.type === "tool.result"), false);
  assert.ok(observed.some(event => event.type === "run.completed"));
  await app.close();
});

it("stops after the current turn when Java halts and still completes generically", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "unexpected second turn" });
  const java = await openJava(() => ({
    status: 200,
    payload: { protocolVersion: 1, result: { status: "PENDING_CONFIRMATION", draftId: "draft-1" }, halt: true },
  }));
  const app = buildApp(await config({ modelBaseUrl: model.url, javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "halt-scope",
    definition: definition({ tools: [LOOKUP_TOOL] }),
  }) });
  const observed = events(run.body);
  assert.equal(model.requests.length, 1);
  assert.ok(observed.some(event => event.type === "tool.result"));
  assert.ok(observed.some(event => event.type === "run.completed"));
  assertGenericEvents(observed);
  await app.close();
});

it("honors the supplied maxTurns", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "unexpected second turn" });
  const java = await openJava();
  const app = buildApp(await config({ modelBaseUrl: model.url, javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "turn-limit",
    definition: definition({ tools: [LOOKUP_TOOL], maxTurns: 1 }),
  }) });
  assert.equal(model.requests.length, 1);
  assert.ok(events(run.body).some(event => event.type === "run.completed"));
  await app.close();
});

it("restores prior Pi messages for a follow-up in the same session scope", async () => {
  const model = await openModel(index => ({ content: index === 0 ? "first answer" : "second answer" }));
  const app = buildApp(await config({ modelBaseUrl: model.url }));
  const first = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "follow-1", turnId: "turn-1", content: "first question", sessionScope: "follow-scope",
  }) });
  assert.ok(events(first.body).some(event => event.type === "run.completed"));
  const second = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "follow-2", turnId: "turn-2", content: "second question", sessionScope: "follow-scope",
  }) });
  const observed = events(second.body);
  assert.ok(observed.some(event => event.type === "message.completed" && event.content === "second answer"));
  assert.equal(model.requests.length, 2);
  const serialized = JSON.stringify(model.requests[1]);
  assert.ok(serialized.includes("first question"));
  assert.ok(serialized.includes("first answer"));
  assert.ok(serialized.includes("second question"));
  assert.equal(serialized.split("first question").length - 1, 1);
  await app.close();
});

it("binds history to the opaque session scope", async () => {
  const model = await openModel(index => ({ content: index === 0 ? "first answer" : "second answer" }));
  const app = buildApp(await config({ modelBaseUrl: model.url }));
  const first = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "scope-1", turnId: "turn-1", content: "first question", sessionScope: "scope-a",
  }) });
  assert.ok(events(first.body).some(event => event.type === "run.completed"));
  const second = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "scope-2", turnId: "turn-2", content: "second question", sessionScope: "scope-b",
  }) });
  assert.ok(events(second.body).some(event => event.type === "run.completed"));
  const serialized = JSON.stringify(model.requests[1]);
  assert.equal(serialized.includes("first question"), false);
  assert.ok(serialized.includes("second question"));
  await app.close();
});
