import { homedir } from "node:os";
import { resolve } from "node:path";

export interface SidecarConfig {
  host: string;
  port: number;
  bridgeToken: string;
  javaToolsUrl: string;
  javaToolsToken: string;
  modelBaseUrl: string;
  modelId: string;
  modelApiKey: string;
  sessionPath: string;
  backupDir: string;
  minimumFreeBytes: number;
  runTimeoutMs: number;
  javaToolTimeoutMs: number;
  heartbeatMs: number;
  thinkingLevel: "off" | "low" | "medium" | "high";
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
function parseThinkingLevel(value: string | undefined): "off" | "low" | "medium" | "high" {
  const level = value?.trim() || "low";
  if (level === "off" || level === "low" || level === "medium" || level === "high") return level;
  throw new Error("AGENT_THINKING_LEVEL must be one of off|low|medium|high");
}


export function loadConfig(env: NodeJS.ProcessEnv = process.env): SidecarConfig {
  const sessionPath = resolve(env.SIDECAR_SESSION_PATH?.trim() || "./var/pi-agent/sessions.sqlite");
  return {
    host: env.SIDECAR_HOST?.trim() || "127.0.0.1",
    port: positive(env, "SIDECAR_PORT", 8787),
    bridgeToken: required(env, "AGENT_BRIDGE_TOKEN"),
    javaToolsUrl: required(env, "JAVA_AGENT_TOOLS_URL"),
    javaToolsToken: required(env, "JAVA_AGENT_TOOLS_TOKEN"),
    modelBaseUrl: required(env, "MODEL_BASE_URL"),
    modelId: required(env, "MODEL_ID"),
    modelApiKey: required(env, "MODEL_API_KEY"),
    sessionPath,
    backupDir: resolve(env.SIDECAR_BACKUP_DIR?.trim() || "./var/pi-agent/backups"),
    minimumFreeBytes: positive(env, "SIDECAR_MINIMUM_FREE_BYTES", 1),
    javaToolTimeoutMs: positive(env, "JAVA_AGENT_TOOL_TIMEOUT_MS", 60_000),
    runTimeoutMs: positive(env, "SIDECAR_RUN_TIMEOUT_MS", 120_000),
    heartbeatMs: positive(env, "SIDECAR_HEARTBEAT_MS", 15_000),
    thinkingLevel: parseThinkingLevel(env.AGENT_THINKING_LEVEL),
  };
}

export function defaultConfigForTests(overrides: Partial<SidecarConfig> = {}): SidecarConfig {
  return {
    host: "127.0.0.1",
    port: 0,
    bridgeToken: "test-bridge",
    javaToolsUrl: "http://127.0.0.1:9/internal/agent/tools",
    javaToolsToken: "test-java",
    modelBaseUrl: "http://127.0.0.1:9/v1",
    modelId: "test-model",
    modelApiKey: "test-model-key",
    sessionPath: resolve(`${homedir()}/.iot-agent-sidecar-test.sqlite`),
    backupDir: resolve(`${homedir()}/.iot-agent-sidecar-test-backups`),
    minimumFreeBytes: 1,
    javaToolTimeoutMs: 60_000,
    runTimeoutMs: 120_000,
    heartbeatMs: 15_000,
    thinkingLevel: "low",
    ...overrides,
  };
}
