export interface SidecarConfig {
  host: string;
  port: number;
  bridgeToken: string;
  javaToolsUrl: string;
  javaSessionsUrl: string;
  javaToolsToken: string;
  runTimeoutMs: number;
  javaToolTimeoutMs: number;
  heartbeatMs: number;
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim();
  if (!value) throw new Error(`Missing required environment variable: ${name}`);
  return value;
}

function positive(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const value = env[name] === undefined ? fallback : Number(env[name]);
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${name} must be a positive integer`);
  return value;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): SidecarConfig {
  return {
    host: env.SIDECAR_HOST?.trim() || "127.0.0.1",
    port: positive(env, "SIDECAR_PORT", 3382),
    bridgeToken: required(env, "AGENT_BRIDGE_TOKEN"),
    javaToolsUrl: required(env, "JAVA_AGENT_TOOLS_URL"),
    javaSessionsUrl: env.JAVA_AGENT_SESSIONS_URL?.trim() || new URL("/internal/agent/sessions", required(env, "JAVA_AGENT_TOOLS_URL")).toString(),
    javaToolsToken: required(env, "JAVA_AGENT_TOOLS_TOKEN"),
    javaToolTimeoutMs: positive(env, "JAVA_AGENT_TOOL_TIMEOUT_MS", 60_000),
    runTimeoutMs: positive(env, "SIDECAR_RUN_TIMEOUT_MS", 120_000),
    heartbeatMs: positive(env, "SIDECAR_HEARTBEAT_MS", 15_000),
  };
}

export function defaultConfigForTests(overrides: Partial<SidecarConfig> = {}): SidecarConfig {
  return {
    host: "127.0.0.1",
    port: 0,
    bridgeToken: "test-bridge",
    javaToolsUrl: "http://127.0.0.1:9/internal/agent/tools",
    javaSessionsUrl: process.env.JAVA_SESSION_TEST_URL || "http://127.0.0.1:9/internal/agent/sessions",
    javaToolsToken: "test-java",
    javaToolTimeoutMs: 60_000,
    runTimeoutMs: 120_000,
    heartbeatMs: 15_000,
    ...overrides,
  };
}
