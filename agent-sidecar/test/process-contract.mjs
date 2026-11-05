import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { chmod, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

const sessionsBase = process.env.JAVA_SESSION_TEST_URL;
if (!sessionsBase) throw new Error("JAVA_SESSION_TEST_URL must point to the isolated Java/MySQL engineering fixture");
const sessionsUrl = `${sessionsBase}?case=${randomUUID()}`;
const javaToken = process.env.JAVA_SESSION_TEST_TOKEN || "test-java";
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
const javaSettingsServer = createServer((request, response) => {
  if (request.url === "/internal/agent/observability") {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(JSON.stringify({ enabled: false }));
    return;
  }
  response.writeHead(404).end();
});
await new Promise((resolve, reject) => {
  javaSettingsServer.once("error", reject);
  javaSettingsServer.listen(0, "127.0.0.1", resolve);
});
const javaOrigin = `http://127.0.0.1:${javaSettingsServer.address().port}`;
const workDir = await mkdir(path.join(tmpdir(), `agent-engine-process-${randomUUID()}`), { recursive: true });
const bridgeTokenFile = path.join(workDir, "agent.bridge.token");
const javaTokenFile = path.join(workDir, "java.token");
await writeFile(bridgeTokenFile, `${token}\n`, { mode: 0o600 });
await writeFile(javaTokenFile, `${javaToken}\n`, { mode: 0o600 });
await chmod(bridgeTokenFile, 0o600);
await chmod(javaTokenFile, 0o600);
const configFile = path.join(workDir, "agent-engine.yaml");
await writeFile(configFile, [
  "server:",
  "  host: 127.0.0.1",
  `  port: ${port}`,
  "bridge:",
  `  token-file: ${JSON.stringify(bridgeTokenFile)}`,
  "java:",
  `  tools-url: ${javaOrigin}/internal/agent/tools`,
  `  sessions-url: ${JSON.stringify(sessionsUrl)}`,
  `  token-file: ${JSON.stringify(javaTokenFile)}`,
  "resources:",
  "  heartbeat-ms: 1000",
  "",
].join("\n"));

// The engine reads only the config file and token files above; ambient
// environment variables and a stray .env must never reach it.
const env = {
  PATH: process.env.PATH,
  AGENT_BRIDGE_TOKEN: "ambient-must-be-ignored",
  JAVA_AGENT_TOOLS_TOKEN: "ambient-must-be-ignored",
  SIDECAR_PORT: "9",
};

function launch() {
  return spawn(process.execPath, ["dist/server.js", "--config", configFile], { env, stdio: ["ignore", "pipe", "inherit"] });
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
  assert.equal(typeof health.observability?.enabled, "boolean", "startup must adopt one observability snapshot");
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
  await new Promise(resolve => javaSettingsServer.close(resolve));
  await rm(workDir, { recursive: true, force: true });
}
