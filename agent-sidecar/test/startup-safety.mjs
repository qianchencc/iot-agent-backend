// Externally observable startup-safety contract for the generic agent engine:
// spawn the built entry with --config and watch exit codes, stderr and the
// /healthz bearer check. Startup failures must fail closed and must never echo
// configuration file contents or token values. Engineering only; not business
// acceptance.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { chmod, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const serverEntry = fileURLToPath(new URL("../dist/server.js", import.meta.url));
const workDir = await mkdtemp(path.join(tmpdir(), "agent-engine-startup-"));
const cases = [];
let passed = 0;

function case_(name, run) {
  cases.push({ name, run });
}

async function launch(args, { env = {}, cwd } = {}) {
  const child = spawn(process.execPath, [serverEntry, ...args], {
    cwd,
    env: { PATH: process.env.PATH, ...env },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stderr = "";
  let stdout = "";
  child.stdout.on("data", chunk => { stdout += chunk; });
  child.stderr.on("data", chunk => { stderr += chunk; });
  const exitCode = await new Promise(resolve => child.once("exit", (code, signal) => resolve(code ?? (signal ? -1 : 0))));
  return { child, exitCode, stderr, stdout };
}

async function writeToken(name, content, mode = 0o600) {
  const file = path.join(workDir, name);
  await writeFile(file, content, { mode: 0o600 });
  await chmod(file, mode);
  return file;
}

async function writeConfig(name, lines) {
  const file = path.join(workDir, name);
  await writeFile(file, `${lines.join("\n")}\n`);
  return file;
}

function minimalConfig(port, bridgeTokenFile, toolsUrl) {
  return [
    "server:",
    "  host: 127.0.0.1",
    `  port: ${port}`,
    "bridge:",
    `  token-file: ${bridgeTokenFile}`,
    "java:",
    `  tools-url: ${toolsUrl}`,
  ];
}

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", resolve); });
  const address = server.address();
  const port = typeof address === "object" && address ? address.port : 0;
  await new Promise(resolve => server.close(resolve));
  return port;
}

// Missing --config fails closed with usage guidance.
case_("missing --config argument", async () => {
  const result = await launch([]);
  assert.notEqual(result.exitCode, 0, "must exit nonzero without --config");
  assert.match(result.stderr, /--config/);
});

// Unreadable config file fails closed without echoing anything from it.
case_("absent config file", async () => {
  const result = await launch(["--config", path.join(workDir, "absent-engine.yaml")]);
  assert.notEqual(result.exitCode, 0);
  assert.match(result.stderr, /absent-engine\.yaml/);
  assert.match(result.stderr, /startup failed/);
});

// Invalid YAML reports only the file and line; the secret-looking strings
// embedded in the file must not reach stderr or stdout.
case_("invalid yaml redacts file contents", async () => {
  const planted = "planted-secret-token-DO-NOT-LEAK";
  const config = await writeConfig("broken.yaml", [
    `# ${planted}`,
    "server:",
    `  port: ${planted}`,
    "bridge: {",
  ]);
  const result = await launch(["--config", config]);
  assert.notEqual(result.exitCode, 0);
  assert.equal(result.stderr.includes(planted), false, "stderr must not echo YAML contents");
  assert.equal(result.stdout.includes(planted), false, "stdout must not echo YAML contents");
  assert.match(result.stderr, /broken\.yaml/);
});

// A value of the wrong type is rejected without echoing the value itself.
case_("invalid value does not echo the value", async () => {
  const planted = "not-a-port-number-secret";
  const tokenFile = await writeToken("placeholder.token", "placeholder-token\n");
  const config = await writeConfig("wrong-type.yaml", [
    "server:",
    `  port: ${JSON.stringify(planted)}`,
    "bridge:",
    `  token-file: ${JSON.stringify(tokenFile)}`,
    "java:",
    "  tools-url: http://127.0.0.1:9/internal/agent/tools",
  ]);
  const result = await launch(["--config", config]);
  assert.notEqual(result.exitCode, 0);
  assert.match(result.stderr, /server\.port must be a positive integer/);
  assert.equal(result.stderr.includes(planted), false);
});

// Missing or empty token files fail closed.
case_("missing bridge token file", async () => {
  const config = await writeConfig("no-token.yaml", minimalConfig(3382, path.join(workDir, "absent.token"), "http://127.0.0.1:9/internal/agent/tools"));
  const result = await launch(["--config", config]);
  assert.notEqual(result.exitCode, 0);
  assert.match(result.stderr, /bridge\.token-file/);
});

case_("empty bridge token file", async () => {
  const tokenFile = await writeToken("empty.token", "\n");
  const config = await writeConfig("empty-token.yaml", minimalConfig(3382, tokenFile, "http://127.0.0.1:9/internal/agent/tools"));
  const result = await launch(["--config", config]);
  assert.notEqual(result.exitCode, 0);
  assert.match(result.stderr, /bridge\.token-file file is empty/);
});

// Group/other-readable token files refuse to start: the secret must stay
// owner-only.
case_("loose bridge token file permissions", async () => {
  const tokenFile = await writeToken("loose.token", "loose-token\n", 0o644);
  const config = await writeConfig("loose-token.yaml", minimalConfig(3382, tokenFile, "http://127.0.0.1:9/internal/agent/tools"));
  const result = await launch(["--config", config]);
  assert.notEqual(result.exitCode, 0);
  assert.match(result.stderr, /owner-only/);
});

// Ambient environment variables and a stray .env next to the process must not
// override the explicit config file: the engine adopts the config-file token
// (trailing newline trimmed) and port, and rejects the ambient token.
case_("config file wins over ambient env and .env", async () => {
  const port = await freePort();
  const javaOriginServer = createServer((request, response) => {
    if (request.url === "/internal/agent/observability") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end(JSON.stringify({ enabled: false }));
      return;
    }
    response.writeHead(404).end();
  });
  await new Promise((resolve, reject) => { javaOriginServer.once("error", reject); javaOriginServer.listen(0, "127.0.0.1", resolve); });
  const javaOrigin = `http://127.0.0.1:${javaOriginServer.address().port}`;

  const cwd = await mkdir(path.join(workDir, "cwd"), { recursive: true });
  await writeFile(path.join(cwd, ".env"), "AGENT_BRIDGE_TOKEN=ambient-env-token\nSIDECAR_PORT=9\n");
  const bridgeTokenFile = await writeToken("bridge.token", "config-bridge-token\n");
  const config = await writeConfig("engine.yaml", minimalConfig(port, bridgeTokenFile, `${javaOrigin}/internal/agent/tools`));

  const child = spawn(process.execPath, [serverEntry, "--config", config], {
    cwd,
    env: {
      PATH: process.env.PATH,
      AGENT_BRIDGE_TOKEN: "ambient-env-token",
      JAVA_AGENT_TOOLS_TOKEN: "ambient-env-token",
      SIDECAR_PORT: "9",
      SIDECAR_HOST: "0.0.0.0",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let stderr = "";
  child.stderr.on("data", chunk => { stderr += chunk; });
  try {
    const deadline = Date.now() + 30_000;
    let healthy = false;
    while (Date.now() < deadline) {
      if (child.exitCode !== null) throw new Error(`engine exited before readiness: ${child.exitCode}\n${stderr}`);
      try {
        const response = await fetch(`http://127.0.0.1:${port}/healthz`, { headers: { authorization: "Bearer config-bridge-token" } });
        if (response.ok) { healthy = true; break; }
      } catch { /* still starting */ }
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    assert.equal(healthy, true, "engine must serve /healthz with the config-file bridge token");
    const ambient = await fetch(`http://127.0.0.1:${port}/healthz`, { headers: { authorization: "Bearer ambient-env-token" } });
    assert.equal(ambient.status, 401, "ambient env/.env token must be rejected");
  } finally {
    if (child.exitCode === null) child.kill("SIGTERM");
    const deadline = Date.now() + 15_000;
    while (child.exitCode === null && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    await new Promise(resolve => javaOriginServer.close(resolve));
  }
});

let failures = 0;
for (const item of cases) {
  try {
    await item.run();
    passed += 1;
    console.log(`PASS ${item.name}`);
  } catch (error) {
    failures += 1;
    console.error(`FAIL ${item.name}:`, error);
  }
}
await rm(workDir, { recursive: true, force: true });
console.log(`agent-engine startup safety: ${passed}/${cases.length} passed (engineering only; not business acceptance)`);
if (failures) process.exitCode = 1;
