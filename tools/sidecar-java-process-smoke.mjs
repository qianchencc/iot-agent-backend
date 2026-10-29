#!/usr/bin/env node
/**
 * Local-only smoke for separately launched Java backend and Node sidecar.
 * It performs only Pi Session health/integrity operations and prints no secrets.
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

async function operation(path) {
  const response = await fetch(`${backend}${path}`, {
    method: "POST",
    headers: { origin, cookie, "X-Admin-CSRF": csrf },
  });
  const body = await response.json();
  if (!response.ok || body.code !== "PI_SESSION_OK") throw new Error(`${path} failed: HTTP ${response.status}`);
  return body.details;
}

const capacity = await operation("/api/admin/pi-session/capacity");
const integrity = await operation("/api/admin/pi-session/integrity-check");
if (integrity.integrity !== "ok" || typeof capacity.freeBytes !== "number") throw new Error("Pi Session details are incomplete");
console.log(JSON.stringify({ sidecar: health.service, capacity: "ok", integrity: "ok" }));
