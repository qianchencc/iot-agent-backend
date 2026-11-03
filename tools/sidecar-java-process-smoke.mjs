#!/usr/bin/env node
/**
 * Local-only smoke for separately launched Java backend and Node sidecar.
 * Engineering diagnostics only; never a business acceptance substitute.
 */
const backend = process.env.BACKEND_URL ?? "http://127.0.0.1:3205";
const sidecar = process.env.SIDECAR_URL ?? "http://127.0.0.1:3221";
const bridgeToken = process.env.AGENT_BRIDGE_TOKEN;
const adminPassword = process.env.ADMIN_PASSWORD;
const origin = process.env.ADMIN_ORIGIN ?? "http://127.0.0.1:3200";
if (!bridgeToken || !adminPassword) throw new Error("AGENT_BRIDGE_TOKEN and ADMIN_PASSWORD are required");

const sidecarHealth = await fetch(`${sidecar}/healthz`, { headers: { authorization: `Bearer ${bridgeToken}` } });
if (!sidecarHealth.ok) throw new Error(`sidecar health failed: HTTP ${sidecarHealth.status}`);
const health = await sidecarHealth.json();
if (health.service !== "agent-sidecar") throw new Error("unexpected sidecar service identity");

const login = await fetch(`${backend}/api/admin/login`, {
  method: "POST",
  headers: { origin, "content-type": "application/json" },
  body: JSON.stringify({ password: adminPassword }),
});
if (!login.ok) throw new Error(`admin login failed: HTTP ${login.status}`);
const csrf = (await login.json()).csrfToken;
const cookie = login.headers.get("set-cookie")?.split(";", 1)[0];
if (!csrf || !cookie) throw new Error("admin login did not return session credentials");

const status = await fetch(`${backend}/api/admin/pi-session/health`, {
  headers: { origin, cookie },
});
const body = await status.json();
if (!status.ok || body.code !== "PI_SESSION_MYSQL_OK" || body.details?.backend !== "mysql") {
  throw new Error(`MySQL Pi Session health failed: HTTP ${status.status}`);
}
console.log(JSON.stringify({ sidecar: health.service, backend: body.details.backend,
  status: body.details.status, scopes: body.details.scopes, documents: body.details.documents }));
