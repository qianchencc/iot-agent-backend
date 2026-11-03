import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:http";

const sessionsBase = process.env.JAVA_SESSION_TEST_URL;
if (!sessionsBase) throw new Error("JAVA_SESSION_TEST_URL must point to the isolated Java/MySQL engineering fixture");
const sessionsUrl = `${sessionsBase}?case=${randomUUID()}`;
const token = "process-bridge";

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", resolve); });
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;
  await new Promise(resolve => server.close(resolve));
  return port;
}

const port = await freePort();
const modelServer = createServer(async (request, response) => {
  for await (const _chunk of request) { /* drain the model request */ }
  const id = "process-completion";
  response.writeHead(200, { "content-type": "text/event-stream" });
  response.write(`data: ${JSON.stringify({ id, choices: [{ delta: { role: "assistant", content: "process ok" }, finish_reason: null }] })}\n\n`);
  response.write(`data: ${JSON.stringify({ id, choices: [{ delta: {}, finish_reason: "stop" }] })}\n\n`);
  response.end("data: [DONE]\n\n");
});
await new Promise((resolve, reject) => {
  modelServer.once("error", reject);
  modelServer.listen(0, "127.0.0.1", resolve);
});
const modelAddress = modelServer.address();
if (!modelAddress || typeof modelAddress === "string") throw new Error("fake model address unavailable");
const modelUrl = `http://127.0.0.1:${modelAddress.port}/v1`;

const env = {
  PATH: process.env.PATH,
  SIDECAR_HOST: "127.0.0.1",
  SIDECAR_PORT: String(port),
  AGENT_BRIDGE_TOKEN: token,
  JAVA_AGENT_TOOLS_URL: "http://127.0.0.1:9/internal/agent/tools",
  JAVA_AGENT_TOOLS_TOKEN: process.env.JAVA_SESSION_TEST_TOKEN || "test-java",
  JAVA_AGENT_SESSIONS_URL: sessionsUrl,
  MODEL_BASE_URL: modelUrl,
  MODEL_ID: "process-test-model",
  MODEL_API_KEY: "process-test-key",
  SIDECAR_HEARTBEAT_MS: "1000",
};

function launch() {
  return spawn(process.execPath, ["dist/server.js"], { env, stdio: ["ignore", "pipe", "pipe"] });
}
async function waitForHealth() {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (childProcess.exitCode !== null) {
      throw new Error(`sidecar exited before readiness: ${childProcess.exitCode}`);
    }
    try {
      const response = await fetch(`http://127.0.0.1:${port}/healthz`, { headers: { authorization: `Bearer ${token}` } });
      if (response.ok) return response.json();
    } catch { /* process is still starting */ }
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error("sidecar did not become healthy");
}
async function post(path, body, headers = {}) {
  const response = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
}
async function stop(childProcess) {
  childProcess.kill("SIGTERM");
  await new Promise((resolve, reject) => { childProcess.once("error", reject); childProcess.once("exit", resolve); });
}

let childProcess = launch();

try {
  const health = await waitForHealth();
  assert.equal(health.service, "agent-sidecar");
  const handoff = await post("/internal/admin/apply", { handoff: "restart" }, { "x-admin-supervisor-token": "process-supervisor" });
  assert.equal(handoff.status, 404);
  await stop(childProcess);
  childProcess = launch();
  const restarted = await waitForHealth();
  assert.equal(restarted.service, "agent-sidecar");

  const runResponse = await fetch(`http://127.0.0.1:${port}/run`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      accept: "application/x-ndjson",
    },
    body: JSON.stringify({
      protocolVersion: 1,
      runId: "process-run",
      toolContextId: "process-context",
      conversationId: "process-conversation",
      turnId: "process-turn",
      content: "process hello",
      sessionScope: "process-scope",
      definition: {
        instructions: "Answer using the supplied request.",
        tools: [],
        maxTurns: 2,
        maxToolCalls: 1,
        runTimeoutMs: 5000,
        model: {
          modelId: "process-model",
          modelBaseUrl: modelUrl,
          modelApiKey: "process-model-key",
          contextWindow: 32768,
          maxOutputTokens: 4096,
          reasoning: true,
          thinkingLevel: "low",
          supportsDeveloperRole: false,
          supportsReasoningEffort: true,
          supportsUsageInStreaming: true,
          maxTokensField: "max_completion_tokens",
        },
      },
    }),
  });
  assert.equal(runResponse.status, 200);
  const runEvents = (await runResponse.text()).trim().split("\n").filter(Boolean).map(line => JSON.parse(line));
  const allowed = {
    "run.started": true, "run.progress": true, "run.completed": true, "run.failed": true,
    "run.aborted": true, heartbeat: true, "message.delta": true, "message.completed": true,
    "message.reclassified": true, "tool.started": true, "tool.progress": true,
    "tool.completed": true, "tool.result": true,
  };
  assert.ok(runEvents.some(event => event.type === "run.started"));
  assert.ok(runEvents.some(event => event.type === "run.completed"));
  assert.ok(runEvents.every(event => allowed[event.type] === true));
  assert.equal(JSON.stringify(runEvents).includes("process-model-key"), false);
  console.log("sidecar process contract: ok (engineering only; not business acceptance)");
} finally {
  if (childProcess.exitCode === null) await stop(childProcess);
  await new Promise(resolve => modelServer.close(resolve));
}
