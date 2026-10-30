import { afterEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer, type Server } from "node:http";
import { TOOL_CAPABILITY_MATRIX } from "../src/agent.js";
import { buildApp } from "../src/app.js";
import { defaultConfigForTests, type SidecarConfig } from "../src/config.js";

const servers: Server[] = [];
const directories: string[] = [];

afterEach(async () => {
  for (const server of servers.splice(0)) await new Promise<void>(resolve => server.close(() => resolve()));
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

describe("private sidecar HTTP contract", () => {
  it("rejects missing and invalid bearer credentials on every route", async () => {
    const app = buildApp(await config());
    const missing = await app.inject({ method: "GET", url: "/healthz" });
    assert.equal(missing.statusCode, 401);
    const invalid = await app.inject({ method: "POST", url: "/internal/pi-session/capacity", headers: { authorization: "Bearer wrong" } });
    assert.equal(invalid.statusCode, 401);
    await app.close();
  });

  it("returns only safe health fields and rejects malformed run requests", async () => {
    const app = buildApp(await config());
    const health = await app.inject({ method: "GET", url: "/healthz", headers: auth() });
    assert.equal(health.statusCode, 200);
    assert.deepEqual(Object.keys(health.json()).sort(), ["activeRuns", "node", "service", "status"]);
    const malformed = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: { content: "missing identity" } });
    assert.equal(malformed.statusCode, 400);
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

  it("requires supervisor identity and an accepted handoff", async () => {
    const app = buildApp(await config());
    const denied = await app.inject({ method: "POST", url: "/internal/admin/apply", headers: auth(), payload: { handoff: "restart" } });
    assert.equal(denied.statusCode, 401);
    const incomplete = await app.inject({ method: "POST", url: "/internal/admin/apply", headers: { ...auth(), "x-admin-supervisor-token": "test-supervisor" }, payload: {} });
    assert.equal(incomplete.statusCode, 409);
    const accepted = await app.inject({ method: "POST", url: "/internal/admin/apply", headers: { "x-admin-supervisor-token": "test-supervisor" }, payload: { handoff: "restart" } });
    assert.deepEqual(accepted.json(), { accepted: true });
    await app.close();
  });
});

it("publishes an explicit fail-closed capability matrix", () => {
  const byName = new Map(TOOL_CAPABILITY_MATRIX.map(capability => [capability.name, capability]));
  assert.equal(byName.get("read_device_states")?.status, "java");
  assert.equal(byName.get("get_device_details")?.status, "java");
  assert.equal(byName.get("query_alerts")?.status, "java");
  assert.equal(byName.get("query_environment_history")?.status, "java");
  assert.equal(byName.get("query_energy_history")?.status, "java");
  assert.equal(byName.get("summarize_energy")?.status, "java");
  assert.equal(byName.get("compare_energy")?.status, "java");
  assert.equal(byName.get("create_energy_report")?.event, "report.generated");
  assert.equal(byName.get("propose_action_draft")?.event, "action.proposed");
  assert.equal(byName.get("confirm_action")?.status, "java");
  assert.equal(byName.get("read_action_draft")?.status, "java");
  assert.equal(byName.get("read_action_device_states")?.status, "java");
  assert.equal(byName.get("read_action_summary")?.status, "java");
  assert.equal(byName.get("propose_task")?.event, "task.proposed");
  assert.equal(byName.get("propose_task_change")?.status, "java");
  assert.equal(byName.get("propose_task_cancellation")?.status, "java");
  assert.equal(byName.get("confirm_task")?.status, "java");
  assert.equal(byName.get("confirm_task_change")?.status, "java");
  assert.equal(byName.get("confirm_task_cancellation")?.status, "java");
  assert.equal(byName.get("list_tasks")?.status, "java");
  assert.equal(byName.get("list_task_plans")?.status, "java");
  assert.equal(byName.get("get_task")?.status, "java");
  assert.equal(byName.get("prepare_video_playback")?.status, "unavailable");
  assert.equal(byName.get("propose_company_night_closure")?.status, "unavailable");
  assert.equal(byName.get("continue_company_night_closure")?.status, "unavailable");
  assert.equal(byName.get("readback_device_states")?.javaToolName, "read_device_states");
  assert.ok(TOOL_CAPABILITY_MATRIX.every(capability => capability.event));
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

function openFakeModel(): Promise<{ server: Server; url: string }> {
  return new Promise((resolve, reject) => {
    const server = createServer((_request, response) => {
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(`data: ${JSON.stringify({ id: "x", choices: [{ delta: { role: "assistant", content: "收到" }, finish_reason: null }] })}\n\n`);
      response.write(`data: ${JSON.stringify({ id: "x", choices: [{ delta: {}, finish_reason: "stop" }] })}\n\n`);
      response.end("data: [DONE]\n\n");
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      servers.push(server);
      const address = server.address();
      if (!address || typeof address === "string") return reject(new Error("fake server address unavailable"));
      resolve({ server, url: `http://127.0.0.1:${address.port}/v1` });
    });
  });
}
function openHangingModel(): Promise<{ server: Server; url: string; started: Promise<void> }> {
  let resolveStarted!: () => void;
  const started = new Promise<void>(resolve => { resolveStarted = resolve; });
  return new Promise((resolve, reject) => {
    const server = createServer((_request, response) => {
      resolveStarted();
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.write(`data: ${JSON.stringify({ id: "hanging", choices: [{ delta: { role: "assistant", content: "处理中" }, finish_reason: null }] })}\n\n`);
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      servers.push(server);
      const address = server.address();
      if (!address || typeof address === "string") return reject(new Error("hanging model address unavailable"));
      resolve({ server, url: `http://127.0.0.1:${address.port}/v1`, started });
    });
  });
}
function openToolModel(toolName = "read_device_states"): Promise<{ server: Server; url: string }> {
  let requests = 0;
  const argumentsValue = toolName === "read_device_states"
    ? JSON.stringify({ candidateSets: [{ candidateSetId: "set-1", deviceIds: ["device-1"] }] })
    : toolName === "propose_action_draft"
      ? JSON.stringify({ candidateSetIds: ["set-1"], summary: "开灯", actions: [{ deviceId: "device-1", closeOrOpen: 1, selected: true }] })
      : "{}";
  return new Promise((resolve, reject) => {
    const server = createServer((_request, response) => {
      requests++;
      response.writeHead(200, { "content-type": "text/event-stream" });
      if (requests === 1) {
        response.write(`data: ${JSON.stringify({ id: "tool-1", choices: [{ delta: { role: "assistant", tool_calls: [{ index: 0, id: "call-1", type: "function", function: { name: toolName, arguments: argumentsValue } }] }, finish_reason: null }] })}\n\n`);
        response.write(`data: ${JSON.stringify({ id: "tool-1", choices: [{ delta: {}, finish_reason: "tool_calls" }] })}\n\n`);
      } else {
        response.write(`data: ${JSON.stringify({ id: "tool-2", choices: [{ delta: { role: "assistant", content: "工具结果已收到" }, finish_reason: null }] })}\n\n`);
        response.write(`data: ${JSON.stringify({ id: "tool-2", choices: [{ delta: {}, finish_reason: "stop" }] })}\n\n`);
      }
      response.end("data: [DONE]\n\n");
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      servers.push(server);
      const address = server.address();
      if (!address || typeof address === "string") return reject(new Error("fake tool model address unavailable"));
      resolve({ server, url: `http://127.0.0.1:${address.port}/v1` });
    });
  });
}
function openFakeJavaTool(result: Record<string, unknown> = { status: "ok", devices: [] }): Promise<{ server: Server; url: string; calls: Record<string, unknown>[] }> {
  const calls: Record<string, unknown>[] = [];
  return new Promise((resolve, reject) => {
    const server = createServer(async (request, response) => {
      let body = "";
      for await (const chunk of request) body += chunk;
      if (request.headers.authorization !== "Bearer test-java"
          || request.headers["x-agent-bridge-token"] !== "test-java") {
        response.writeHead(401);
        response.end();
        return;
      }
      calls.push(JSON.parse(body) as Record<string, unknown>);
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify(result));
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      servers.push(server);
      const address = server.address();
      if (!address || typeof address === "string") return reject(new Error("fake Java tool address unavailable"));
      resolve({ server, url: `http://127.0.0.1:${address.port}/internal/agent/tools`, calls });
    });
  });
}

it("streams public NDJSON and supports cancellation", async () => {
  const model = await openFakeModel();
  const current = await config({ modelBaseUrl: model.url });
  const app = buildApp(current);
  const run = await app.inject({ method: "POST", url: "/run", headers: { ...auth(), accept: "application/x-ndjson" }, payload: {
    runId: "r1", toolContextId: "ctx", conversationId: "c1", turnId: "t1", source: "office", tenantCode: "tenant", content: "你好",
  } });
  assert.equal(run.statusCode, 200);
  const events = run.body.trim().split("\n").map(line => JSON.parse(line) as { type: string });
  assert.equal(events[0]?.type, "run.started");
  assert.ok(events.some(event => event.type === "message.completed"));
  assert.ok(events.some(event => event.type === "run.completed"));
  const cancel = await app.inject({ method: "POST", url: "/cancel/unknown", headers: auth() });
  assert.equal(cancel.statusCode, 404);
  await app.close();
});
it("aborts an active run and fails a timed-out run", async () => {
  const model = await openHangingModel();
  const app = buildApp(await config({ modelBaseUrl: model.url, runTimeoutMs: 500 }));
  const pending = app.inject({ method: "POST", url: "/run", headers: auth(), payload: {
    runId: "cancel-run", toolContextId: "ctx", conversationId: "cancel-conversation",
    turnId: "t1", source: "office", tenantCode: "tenant", content: "等待",
  } });
  await model.started;
  const duplicate = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: {
    runId: "cancel-run", toolContextId: "ctx", conversationId: "cancel-conversation",
    turnId: "t2", source: "office", tenantCode: "tenant", content: "重复",
  } });
  assert.equal(duplicate.statusCode, 409);
  const cancel = await app.inject({ method: "POST", url: "/cancel/cancel-run", headers: auth() });
  assert.equal(cancel.statusCode, 202);
  const cancelled = await pending;
  const cancelledEvents = cancelled.body.trim().split("\n").map(line => JSON.parse(line) as { type: string });
  assert.ok(cancelledEvents.some(event => event.type === "run.aborted"));
  await app.close();

  // This intentionally uses the platform timer to verify the production timeout abort path.
  const timeoutApp = buildApp(await config({ modelBaseUrl: model.url, runTimeoutMs: 20 }));
  const timedOut = await timeoutApp.inject({ method: "POST", url: "/run", headers: auth(), payload: {
    runId: "timeout-run", toolContextId: "ctx", conversationId: "timeout-conversation",
    turnId: "t1", source: "office", tenantCode: "tenant", content: "超时",
  } });
  const timeoutEvents = timedOut.body.trim().split("\n").map(line => JSON.parse(line) as { type: string });
  assert.ok(timeoutEvents.some(event => event.type === "run.failed"));
  await timeoutApp.close();
});
it("forwards a pi-agent tool call to the authenticated Java bridge", async () => {
  const model = await openToolModel();
  const java = await openFakeJavaTool();
  const current = await config({ modelBaseUrl: model.url, javaToolsUrl: java.url });
  const app = buildApp(current);
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: {
    runId: "tool-run", toolContextId: "tool-context", conversationId: "tool-conversation",
    turnId: "tool-turn", source: "office", tenantCode: "tenant", content: "查询设备",
  } });
  const events = run.body.trim().split("\n").map(line => JSON.parse(line) as { type: string; tool?: string });
  assert.ok(events.some(event => event.type === "tool.started" && event.tool === "read_device_states"));
  assert.ok(events.some(event => event.type === "observation.reported"));
  assert.ok(events.some(event => event.type === "run.completed"));
  assert.equal(java.calls.length, 1);
  assert.equal(java.calls[0]?.toolName, "read_device_states");
  assert.equal(java.calls[0]?.toolContextId, "tool-context");
  await app.close();
});

it("returns structured unavailable results without calling Java", async () => {
  const model = await openToolModel("prepare_video_playback");
  const java = await openFakeJavaTool();
  const current = await config({ modelBaseUrl: model.url, javaToolsUrl: java.url });
  const app = buildApp(current);
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: {
    runId: "unsupported-run", toolContextId: "ctx", conversationId: "unsupported-conversation",
    turnId: "t1", source: "office", tenantCode: "tenant", content: "查告警",
  } });
  const events = run.body.trim().split("\n").map(line => JSON.parse(line) as { type: string; details?: Record<string, unknown> });
  const reported = events.find(event => event.type === "observation.reported");
  assert.equal(reported?.details?.code, "AGENT_TOOL_UNAVAILABLE");
  assert.equal(java.calls.length, 0);
  assert.ok(events.some(event => event.type === "run.completed"));
  await app.close();
});

it("maps pending action proposals to awaiting-confirmation terminal events", async () => {
  const model = await openToolModel("propose_action_draft");
  const java = await openFakeJavaTool({ status: "PENDING_CONFIRMATION", draftId: "draft-1" });
  const current = await config({ modelBaseUrl: model.url, javaToolsUrl: java.url });
  const app = buildApp(current);
  const run = await app.inject({ method: "POST", url: "/run", headers: auth(), payload: {
    runId: "proposal-run", toolContextId: "ctx", conversationId: "proposal-conversation",
    turnId: "t1", source: "office", tenantCode: "tenant", content: "开灯",
  } });
  const events = run.body.trim().split("\n").map(line => JSON.parse(line) as { type: string });
  assert.ok(events.some(event => event.type === "action.proposed"));
  assert.ok(events.some(event => event.type === "run.awaiting_confirmation"));
  assert.equal(events.some(event => event.type === "run.completed"), false);
  await app.close();
});
