import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { spawn } from "node:child_process";
import { createServer } from "node:http";

const root = await mkdtemp(join(tmpdir(), "iot-agent-sidecar-process-"));
const sessionPath = join(root, "sessions.sqlite");
const backupDir = join(root, "backups");
const token = "process-bridge";
const supervisor = "process-supervisor";

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", resolve); });
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;
  await new Promise(resolve => server.close(resolve));
  return port;
}

const port = await freePort();
const env = {
  ...process.env,
  SIDECAR_HOST: "127.0.0.1",
  SIDECAR_PORT: String(port),
  AGENT_BRIDGE_TOKEN: token,
  SIDECAR_SUPERVISOR_TOKEN: supervisor,
  JAVA_AGENT_TOOLS_URL: "http://127.0.0.1:9/internal/agent/tools",
  JAVA_AGENT_TOOLS_TOKEN: token,
  MODEL_BASE_URL: "http://127.0.0.1:9/v1",
  MODEL_ID: "process-test-model",
  MODEL_API_KEY: "process-test-key",
  SIDECAR_SESSION_PATH: sessionPath,
  SIDECAR_BACKUP_DIR: backupDir,
  SIDECAR_HEARTBEAT_MS: "1000",
};

function launch() {
  return spawn(process.execPath, ["dist/server.js"], { env, stdio: ["ignore", "pipe", "pipe"] });
}
async function waitForHealth() {
  for (let attempt = 0; attempt < 50; attempt++) {
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
  const denied = await post("/internal/admin/apply", { handoff: "restart" }, { authorization: `Bearer wrong` });
  assert.equal(denied.status, 401);
  const accepted = await post("/internal/admin/apply", { handoff: "restart" }, { "x-admin-supervisor-token": supervisor });
  assert.deepEqual(accepted.body, { accepted: true });
  const backup = await post("/internal/pi-session/backup", { destination: join(backupDir, "process.sqlite"), force: false }, { authorization: `Bearer ${token}` });
  assert.equal(backup.status, 200);
  assert.equal(backup.body.destination, "[sidecar-path]");
  await stop(childProcess);
  childProcess = launch();
  const restarted = await waitForHealth();
  assert.equal(restarted.service, "agent-sidecar");
  const capacity = await post("/internal/pi-session/capacity", {}, { authorization: `Bearer ${token}` });
  assert.equal(capacity.status, 200);
  assert.equal(typeof capacity.body.freeBytes, "number");
  console.log("sidecar process contract: ok");
} finally {
  if (childProcess.exitCode === null) await stop(childProcess);
  await rm(root, { recursive: true, force: true });
}
