import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type Server } from "node:http";
import { buildApp } from "../src/app.js";
import { defaultConfigForTests, type SidecarConfig } from "../src/config.js";
import {
  createObservability,
  credentialsDigest,
  fetchObservabilitySettings,
  type Observability,
} from "../src/observability.js";

const servers: Server[] = [];

/** Per-test model endpoint used by the default run definition, mirroring Java always sending a model snapshot. */
let modelUrlForRuns = "http://127.0.0.1:9/v1";

beforeEach(() => {
  modelUrlForRuns = "http://127.0.0.1:9/v1";
});

afterEach(async () => {
  for (const server of servers.splice(0)) {
    const closed = Promise.withResolvers<void>();
    server.close(() => closed.resolve());
    server.closeAllConnections();
    await closed.promise;
  }
});

async function config(overrides: Partial<SidecarConfig> = {}): Promise<SidecarConfig> {
  const base = process.env.JAVA_SESSION_TEST_URL;
  return defaultConfigForTests({
    ...(base ? { javaSessionsUrl: `${base}?case=${randomUUID()}` } : {}),
    ...overrides,
  });
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
    definition: definition(),
    ...overrides,
  };
}

function definition(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    instructions: "Use a tool only when it provides evidence needed by the request.",
    tools: [],
    maxTurns: 4,
    model: modelSettings(),
    ...overrides,
  };
}

function modelSettings(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    modelId: "snapshot-model",
    modelBaseUrl: modelUrlForRuns,
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
          beginStage() { return { finish() {} }; },
          observeEvent(event: unknown) { seen.push(event); },
          beginGeneration(input: unknown) {
            seen.push(input);
            return { firstText() {}, finish(output: unknown) { seen.push(output); } };
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
    const invalid = await app.inject({ method: "POST", url: "/run", headers: { authorization: "Bearer wrong" }, payload: {} });
    assert.equal(invalid.statusCode, 401);
    await app.close();
  });

  it("returns only safe health fields and rejects malformed v1 run requests", async () => {
    const app = buildApp(await config());
    const health = await app.inject({ method: "GET", url: "/healthz", headers: auth() });
    assert.equal(health.statusCode, 200);
    assert.deepEqual(Object.keys(health.json()).sort(), ["activeRuns", "node", "observability", "service", "status"]);
    assert.deepEqual(health.json().observability, {});

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
      runBody({ definition: definition({ model: undefined }) }),
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


it("streams generic NDJSON and supports cancellation", async () => {
  const model = await openModel(() => ({ content: "收到" }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config());
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
  modelUrlForRuns = model.url;
  const app = buildApp(await config());
  try {
    const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody() });
    const observed = events(run.body);
    assert.ok(observed.some(event => event.type === "run.failed"));
    assert.equal(observed.some(event => event.type === "run.completed"), false);
  } finally {
    await app.close();
  }
});

it("rejects an empty model completion when no tool produced a result", async () => {
  const model = await openModel(() => ({ content: "" }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config());
  try {
    const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody() });
    const observed = events(run.body);
    assert.ok(observed.some(event => event.type === "run.failed"));
    assert.equal(observed.some(event => event.type === "run.completed"), false);
  } finally {
    await app.close();
  }
});

it("does not report success for an empty answer after a lookup", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "" });
  const java = await openJava();
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
  try {
    const run = await app.inject({ method: "POST", url: "/run", headers: auth(),
      payload: runBody({ definition: definition({ tools: [LOOKUP_TOOL] }) }) });
    const observed = events(run.body);
    assert.ok(observed.some(event => event.type === "tool.result"));
    assert.ok(observed.some(event => event.type === "run.failed"));
    assert.equal(observed.some(event => event.type === "run.completed"), false);
  } finally { await app.close(); }
});

it("uses the per-run model snapshot without exposing its key", async () => {
  const model = await openModel(() => ({ content: "snapshot ok" }));
  const current = await config();
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
  assert.equal(model.authorizations.length, 1);
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
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "tool-budget",
    definition: definition({ tools: [LOOKUP_TOOL], maxToolCalls: 1 }),
  }) });
  const observed = events(run.body);
  assert.equal(java.calls.length, 1);
  assert.equal(observed.filter(event => event.type === "tool.result").length, 1);
  assert.ok(observed.some(event => event.type === "run.failed"));
  assert.equal(observed.some(event => event.type === "run.completed"), false);
  await app.close();
});

it("uses the per-request run timeout override", async () => {
  const model = await openHangingModel();
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ runTimeoutMs: 120_000 }));
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
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ runTimeoutMs: 120_000 }));
  const pending = app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "cancel-run", conversationId: "cancel-conversation", sessionScope: "cancel-scope", content: "wait",
  }) }).then(response => response);
  const endedBeforeModelStart = await Promise.race([model.started.then(() => null), pending]);
  assert.equal(endedBeforeModelStart, null, endedBeforeModelStart?.body);
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
  modelUrlForRuns = model.url;
  const timeoutApp = buildApp(await config({ runTimeoutMs: 20 }));
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
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
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
  assert.equal(java.calls[0]?.toolContextId, "tool-context");
  assert.equal(typeof java.calls[0]?.writerId, "string");
  assert.equal(typeof java.calls[0]?.epoch, "number");
  assert.equal("tenant" in java.calls[0]!, false);
  assert.equal("model" in java.calls[0]!, false);
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
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "result-fidelity",
    definition: definition({ tools: [LOOKUP_TOOL] }),
  }) });
  const result = events(run.body).find(event => event.type === "tool.result");
  assert.deepEqual(result?.result, longResult);
  await app.close();
});

it("compacts long durable history before inference and reuses it after reopening", async () => {
  const marker = "selected-scope-907";
  const largeReply = `${marker}: ${"historical detail ".repeat(3000)}`;
  const model = await openModel(index => {
    const request = JSON.stringify(model.requests[index]);
    return request.includes("You are a context summarization assistant.")
      ? { content: `The user selected ${marker}; preserve this selection and await confirmation.` }
      : { content: index < 5 ? largeReply : "The existing selection still awaits confirmation." };
  });
  modelUrlForRuns = model.url;
  const settings = await config();
  let app = buildApp(settings);
  try {
    for (let turn = 0; turn < 6; turn++) {
      const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
        runId: `history-${turn}`, turnId: `turn-${turn}`, content: `Continue ${turn}`,
        definition: definition({ model: modelSettings({ modelBaseUrl: model.url, contextWindow: 65_536,
          thinkingLevel: "off" }) }),
      }) });
      assert.ok(events(run.body).some(event => event.type === "run.completed"), run.body.slice(-1000));
    }
    const summaries = model.requests.filter(request => JSON.stringify(request).includes("You are a context summarization assistant."));
    assert.ok(summaries.length > 0, "Long history must be summarized before the next inference");
    await app.close();
    app = buildApp(settings);
    const reopened = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
      runId: "history-reopened", turnId: "turn-reopened", content: "What remains to be confirmed?",
      definition: definition({ model: modelSettings({ modelBaseUrl: model.url, contextWindow: 65_536,
        thinkingLevel: "off" }) }),
    }) });
    assert.ok(events(reopened.body).some(event => event.type === "run.completed"), reopened.body.slice(-1000));
    const restored = JSON.stringify(model.requests.at(-1));
    assert.ok(restored.includes(marker), "The prior selection must survive reopening");
    assert.ok(restored.length < largeReply.length * 4, "Reopening must not restore the unbounded original context");
  } finally { await app.close(); }
});

it("uses the Pi validator before calling Java for malformed tool arguments", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: {} } }
    : { content: "done" });
  const java = await openJava();
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
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
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
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
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
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
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
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

it("keeps a server-completed result terminal instead of starting another model turn", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "This extra model turn must never run." });
  const java = await openJava(() => ({
    status: 200, payload: { protocolVersion: 1, result: { id: "prepared-1" }, halt: true,
      completion: "Prepared result retained; confirmation is still required." },
  }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
  try {
    const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
      definition: definition({ tools: [LOOKUP_TOOL], finalizationInstructions: "Finish using retained facts." }),
    }) });
    assert.equal(model.requests.length, 1);
    assert.equal(java.calls.length, 1);
    const observed = events(run.body);
    assert.ok(observed.some(event => event.type === "message.completed"
      && event.content === "Prepared result retained; confirmation is still required."));
    assert.ok(observed.some(event => event.type === "run.completed"));
    assert.equal(observed.some(event => event.type === "run.failed"), false);
  } finally { await app.close(); }
});

it("finalizes retained results without tools after a Java halt decision", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "The prepared result is retained and still awaits confirmation." });
  const java = await openJava(() => ({
    status: 200, payload: { protocolVersion: 1, result: { id: "prepared-1" }, halt: true },
  }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
  try {
    const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
      definition: definition({ tools: [LOOKUP_TOOL], finalizationInstructions: "Retain the result; finish without further actions." }),
    }) });
    assert.equal(java.calls.length, 1);
    assert.equal(model.requests.length, 2);
    const finalRequest = model.requests[1];
    assert.ok(finalRequest && typeof finalRequest === "object");
    const tools = "tools" in finalRequest ? finalRequest.tools : undefined;
    assert.ok(tools === undefined || Array.isArray(tools));
    assert.equal(tools?.length ?? 0, 0);
    assert.ok(JSON.stringify(finalRequest).includes("prepared-1"));
    assert.ok(events(run.body).some(event => event.type === "message.completed"
      && event.content === "The prepared result is retained and still awaits confirmation."));
  } finally { await app.close(); }
});

it("uses a tool-free final turn after a failed call consumes the run budget", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "The requested result is unavailable; no result was invented." });
  const java = await openJava(() => ({ status: 503, payload: { code: "UNAVAILABLE", message: "The evidence is unavailable." } }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
  try {
    const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
      definition: definition({ tools: [LOOKUP_TOOL], maxToolCalls: 1,
        finalizationInstructions: "State the incomplete objective using only retained evidence." }),
    }) });
    assert.equal(java.calls.length, 1);
    assert.equal(model.requests.length, 2);
    const finalRequest = model.requests[1];
    assert.ok(finalRequest && typeof finalRequest === "object");
    const tools = "tools" in finalRequest ? finalRequest.tools : undefined;
    assert.ok(tools === undefined || Array.isArray(tools));
    assert.equal(tools?.length ?? 0, 0);
    assert.ok(events(run.body).some(event => event.type === "tool.completed" && event.status === "failed"));
    assert.ok(events(run.body).some(event => event.type === "message.completed"
      && event.content === "The requested result is unavailable; no result was invented."));
  } finally { await app.close(); }
});

it("honors the supplied maxTurns", async () => {
  const model = await openModel(index => index === 0
    ? { tool: { name: "lookup_records", arguments: { query: "north" } } }
    : { content: "unexpected second turn" });
  const java = await openJava();
  modelUrlForRuns = model.url;
  const app = buildApp(await config({ javaToolsUrl: java.url }));
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    sessionScope: "turn-limit",
    definition: definition({ tools: [LOOKUP_TOOL], maxTurns: 1 }),
  }) });
  assert.equal(model.requests.length, 1);
  assert.ok(events(run.body).some(event => event.type === "run.failed"));
  assert.equal(events(run.body).some(event => event.type === "run.completed"), false);
  await app.close();
});

it("does not feed private reasoning text back into the next model turn", async () => {
  const model = await openModel(index => ({ content: index === 0
    ? "<thinking>PRIVATE_PLAN_NOT_A_USER_FACT</thinking>Public answer"
    : "Follow-up answer" }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config());
  try {
    await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
      runId: "reasoning-first", turnId: "reasoning-turn-1", content: "first question", sessionScope: "reasoning-scope",
    }) });
    const second = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
      runId: "reasoning-second", turnId: "reasoning-turn-2", content: "follow-up", sessionScope: "reasoning-scope",
    }) });
    assert.ok(events(second.body).some(event => event.type === "run.completed"), second.body);
    const input = JSON.stringify(model.requests[1]);
    assert.ok(input.includes("Public answer"));
    assert.equal(input.includes("PRIVATE_PLAN_NOT_A_USER_FACT"), false);
  } finally { await app.close(); }
});

it("restores prior Pi messages for a follow-up in the same session scope", async () => {
  const model = await openModel(index => ({ content: index === 0 ? "first answer" : "second answer" }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config());
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

it("continues a system decision without journaling a duplicate user request", async () => {
  const model = await openModel(index => ({ content: index === 0 ? "prepared" : "choose a new time" }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config());
  await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "decision-first", turnId: "original-turn", content: "original goal", sessionScope: "decision-scope",
  }) });
  const continued = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "decision-resume", turnId: "original-turn", content: "original goal", sessionScope: "decision-scope",
    definition: definition({ resume: true, context: "SYSTEM_DECISION_TIME_PASSED" }),
  }) });
  assert.ok(events(continued.body).some(event => event.type === "message.completed" && event.content === "choose a new time"), continued.body);
  const input = JSON.stringify(model.requests[1]);
  assert.equal(input.split("original goal").length - 1, 1);
  assert.ok(input.indexOf("SYSTEM_DECISION_TIME_PASSED") > input.indexOf("prepared"));
  await app.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "decision-follow", turnId: "follow-turn", content: "new time", sessionScope: "decision-scope",
  }) });
  assert.equal(JSON.stringify(model.requests[2]).includes("SYSTEM_DECISION_TIME_PASSED"), false);
  await app.close();
});

it("uses current invocation context without persisting it into later conversations", async () => {
  const model = await openModel(index => ({ content: index === 0 ? "first answer" : "second answer" }));
  modelUrlForRuns = model.url;
  const settings = await config();
  const firstApp = buildApp(settings);
  const first = await firstApp.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "context-first", turnId: "context-turn-1", content: "first question", sessionScope: "ephemeral-context",
    definition: definition({ context: "CURRENT_CONTEXT_A" }),
  }) });
  assert.ok(events(first.body).some(event => event.type === "run.completed"), first.body);
  await firstApp.close();
  const secondApp = buildApp(settings);
  const second = await secondApp.inject({ method: "POST", url: "/run", headers: auth(), payload: runBody({
    runId: "context-second", turnId: "context-turn-2", content: "second question", sessionScope: "ephemeral-context",
    definition: definition({ context: "CURRENT_CONTEXT_B" }),
  }) });
  assert.ok(events(second.body).some(event => event.type === "run.completed"));
  const firstInput = JSON.stringify(model.requests[0]);
  assert.match(firstInput, /CURRENT_CONTEXT_A[\s\S]*first question/);
  const secondInput = JSON.stringify(model.requests[1]);
  assert.equal(secondInput.includes("CURRENT_CONTEXT_A"), false);
  assert.ok(secondInput.includes("first question"));
  assert.ok(secondInput.includes("first answer"));
  assert.match(secondInput, /CURRENT_CONTEXT_B[\s\S]*second question/);
  assert.equal(secondInput.split("second question").length - 1, 1);
  await secondApp.close();
});

it("binds history to the opaque session scope", async () => {
  const model = await openModel(index => ({ content: index === 0 ? "first answer" : "second answer" }));
  modelUrlForRuns = model.url;
  const app = buildApp(await config());
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

describe("observability configuration contract", () => {
  interface RecordingReceiver {
    server: Server;
    url: string;
    hits: Array<{ path: string; authorization: string }>;
  }

  function openReceiver(): Promise<RecordingReceiver> {
    const hits: RecordingReceiver["hits"] = [];
    const listening = Promise.withResolvers<RecordingReceiver>();
    const server = createServer((request, response) => {
      hits.push({
        path: request.url ?? "",
        authorization: typeof request.headers.authorization === "string" ? request.headers.authorization : "",
      });
      response.writeHead(200, { "content-type": "application/json" });
      response.end("{}");
    });
    server.once("error", listening.reject);
    server.listen(0, "127.0.0.1", () => {
      servers.push(server);
      const address = server.address();
      if (!address || typeof address === "string") {
        listening.reject(new Error("observability receiver address unavailable"));
        return;
      }
      listening.resolve({ server, url: `http://127.0.0.1:${address.port}`, hits });
    });
    return listening.promise;
  }

  function openJavaObservability(reply: (request: IncomingMessage) => { status: number; body: unknown }): Promise<{ url: string; requests: IncomingMessage[] }> {
    const requests: IncomingMessage[] = [];
    const listening = Promise.withResolvers<{ url: string; requests: IncomingMessage[] }>();
    const server = createServer((request, response) => {
      requests.push(request);
      if (request.headers["x-agent-bridge-token"] !== "test-java") {
        response.writeHead(401);
        response.end();
        return;
      }
      const result = reply(request);
      response.writeHead(result.status, { "content-type": "application/json" });
      response.end(JSON.stringify(result.body));
    });
    server.once("error", listening.reject);
    server.listen(0, "127.0.0.1", () => {
      servers.push(server);
      const address = server.address();
      if (!address || typeof address === "string") {
        listening.reject(new Error("observability fixture address unavailable"));
        return;
      }
      listening.resolve({ url: `http://127.0.0.1:${address.port}/internal/agent/tools`, requests });
    });
    return listening.promise;
  }

  it("fetches the startup snapshot from Java with the bridge token", async () => {
    const java = await openJavaObservability(() => ({
      status: 200,
      body: { enabled: false, revision: 4 },
    }));
    const settings = await fetchObservabilitySettings(defaultConfigForTests({ javaToolsUrl: java.url }));
    assert.deepEqual(settings, { enabled: false, revision: 4 });
    assert.equal(java.requests[0]?.url, "/internal/agent/observability");
    assert.equal(java.requests[0]?.headers["x-agent-bridge-token"], "test-java");
  });

  it("rejects unauthorized or malformed observability responses at startup", async () => {
    const unauthorized = await openJavaObservability(() => ({ status: 503, body: { code: "ADMIN_CONFIG_UNAVAILABLE" } }));
    await assert.rejects(
      fetchObservabilitySettings(defaultConfigForTests({ javaToolsUrl: unauthorized.url })),
      /observability configuration unavailable/,
    );

    const malformed = await openJavaObservability(() => ({ status: 200, body: { enabled: "yes" } }));
    await assert.rejects(
      fetchObservabilitySettings(defaultConfigForTests({ javaToolsUrl: malformed.url })),
      /malformed/,
    );

    const incomplete = await openJavaObservability(() => ({
      status: 200,
      body: { enabled: true, baseUrl: "https://langfuse.example.test" },
    }));
    await assert.rejects(
      fetchObservabilitySettings(defaultConfigForTests({ javaToolsUrl: incomplete.url })),
      /malformed: publicKey/,
    );
  });

  it("sends spans only to the fetched receiver and ignores leftover langfuse environment values", async () => {
    const expected = await openReceiver();
    const ignored = await openReceiver();
    process.env.LANGFUSE_BASE_URL = ignored.url;
    process.env.LANGFUSE_PUBLIC_KEY = "pk-env-leftover";
    process.env.LANGFUSE_SECRET_KEY = "sk-env-leftover";
    try {
      const settings = {
        enabled: true,
        revision: 7,
        baseUrl: expected.url,
        publicKey: "pk-run-expected",
        secretKey: "sk-run-expected",
      };
      const observability = createObservability(settings);
      const trace = observability.startAgentRun({ runId: "obs-run", turnId: "obs-turn", conversationId: "obs-conversation", content: "observed run" });
      assert.ok(trace);
      trace.finish({});
      await observability.flush();
      await observability.shutdown();
      assert.ok(expected.hits.length > 0, "the fetched receiver must observe the run");
      for (const hit of expected.hits) {
        assert.ok(hit.path.startsWith("/api/public/"), `unexpected ingestion path ${hit.path}`);
        assert.equal(hit.authorization, `Basic ${Buffer.from("pk-run-expected:sk-run-expected").toString("base64")}`);
      }
      assert.equal(ignored.hits.length, 0, "environment leftovers must never receive spans");
      assert.equal(
        credentialsDigest(settings),
        createHash("sha256").update("pk-run-expected:sk-run-expected").digest("hex"),
      );
      assert.equal(credentialsDigest({ enabled: false }), undefined);
    } finally {
      delete process.env.LANGFUSE_BASE_URL;
      delete process.env.LANGFUSE_PUBLIC_KEY;
      delete process.env.LANGFUSE_SECRET_KEY;
    }
  });

  it("reports adopted observability state in health without secrets", async () => {
    const app = buildApp(await config(), createObservability(null), () => ({
      enabled: true,
      revision: 9,
      baseUrl: "https://langfuse.example.test",
      credentialsDigest: "digest-value",
    }));
    const health = await app.inject({ method: "GET", url: "/healthz", headers: auth() });
    const observability = health.json().observability as Record<string, unknown>;
    assert.equal(observability.enabled, true);
    assert.equal(observability.revision, 9);
    assert.equal(observability.baseUrl, "https://langfuse.example.test");
    assert.equal(observability.credentialsDigest, "digest-value");
    assert.equal("publicKey" in observability || "secretKey" in observability, false);
    await app.close();
  });

  it("disabled settings produce a noop observability", async () => {
    const observability = createObservability({ enabled: false });
    assert.equal(observability.startAgentRun({ runId: "r", turnId: "t", conversationId: "c", content: "x" }), null);
    await observability.flush();
    await observability.shutdown();
  });
});
