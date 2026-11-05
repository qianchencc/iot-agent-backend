import { readFile, stat } from "node:fs/promises";
import { parse, YAMLParseError } from "yaml";

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

/**
 * Startup bootstrap for the generic agent engine. One explicit YAML file
 * (`--config PATH`, no environment or .env fallback) plus owner-only token
 * files. Only generic transport/resource settings live here: business,
 * database, admin, model and observability bootstrap values stay Java-owned
 * and are never read by this process.
 */

const CONFIG_USAGE = "usage: node dist/server.js --config /absolute/path/to/agent-engine.yaml";

/** Extracts the required `--config` path from process arguments. */
export function configPathFromArgs(argv: string[]): string {
  let configPath: string | undefined;
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--config" || arg.startsWith("--config=")) {
      const value = arg === "--config" ? argv[(index += 1)] : arg.slice("--config=".length);
      if (!value || value.startsWith("--")) {
        throw new Error(`--config requires a file path\n${CONFIG_USAGE}`);
      }
      if (configPath !== undefined) throw new Error(`--config must be given exactly once\n${CONFIG_USAGE}`);
      configPath = value;
    } else {
      throw new Error(`unexpected argument: ${arg}\n${CONFIG_USAGE}`);
    }
  }
  if (configPath === undefined) {
    throw new Error(`missing required --config /absolute/path/to/agent-engine.yaml\n${CONFIG_USAGE}`);
  }
  return configPath;
}

type Section = Record<string, unknown>;

function section(value: unknown, name: string): Section | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "object" || Array.isArray(value)) throw new Error(`${name || "configuration file"} must be a mapping`);
  return value as Section;
}

function rejectUnknownKeys(sectionValue: Section | undefined, known: readonly string[], name: string): void {
  if (!sectionValue) return;
  for (const key of Object.keys(sectionValue)) {
    if (!known.includes(key)) throw new Error(`${name}.${key} is not a recognized agent-engine setting`);
  }
}

/** Nonempty trimmed string, or undefined when absent/blank. */
function text(sectionValue: Section | undefined, name: string, key: string): string | undefined {
  const value = sectionValue?.[key];
  if (value === undefined || value === null) return undefined;
  if (typeof value !== "string") throw new Error(`${name}.${key} must be a string`);
  const trimmed = value.trim();
  return trimmed === "" ? undefined : trimmed;
}

function requiredText(sectionValue: Section | undefined, name: string, key: string): string {
  return text(sectionValue, name, key) ?? (() => { throw new Error(`${name}.${key} is required`); })();
}

function positiveInt(sectionValue: Section | undefined, name: string, key: string, fallback: number): number {
  const value = sectionValue?.[key];
  if (value === undefined || value === null) return fallback;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name}.${key} must be a positive integer`);
  }
  return value;
}

function httpUrl(value: string, name: string, key: string): URL {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`${name}.${key} must be an absolute http(s) URL`);
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error(`${name}.${key} must be an absolute http(s) URL`);
  }
  return parsed;
}

/**
 * Reads one secret token file. The value never appears in logs or errors; a
 * trailing newline is trimmed and the file must be owner-only so the token
 * cannot leak through group/other read access.
 */
async function readTokenFile(path: string, key: string): Promise<string> {
  let info;
  try {
    info = await stat(path);
  } catch {
    throw new Error(`unable to access ${key} file: ${path}`);
  }
  if (!info.isFile()) throw new Error(`${key} file must be a regular file: ${path}`);
  if ((info.mode & 0o077) !== 0) throw new Error(`${key} file must be owner-only (chmod 600): ${path}`);
  let content: string;
  try {
    content = await readFile(path, "utf8");
  } catch {
    throw new Error(`unable to read ${key} file: ${path}`);
  }
  const token = content.replace(/[\r\n]+$/, "");
  if (token.trim() === "") throw new Error(`${key} file is empty: ${path}`);
  return token;
}

export async function loadConfig(argv: string[]): Promise<SidecarConfig> {
  const configPath = configPathFromArgs(argv);
  let source: string;
  try {
    source = await readFile(configPath, "utf8");
  } catch {
    throw new Error(`unable to read agent-engine configuration file: ${configPath}`);
  }
  let document: unknown;
  try {
    document = parse(source);
  } catch (error) {
    // Parser messages embed YAML source; only the line number is safe to report.
    const line = error instanceof YAMLParseError && error.linePos ? ` (line ${error.linePos[0]})` : "";
    throw new Error(`agent-engine configuration file is not valid YAML: ${configPath}${line}`);
  }
  const root = section(document, "configuration file");
  if (root) {
    for (const key of Object.keys(root)) {
      if (!["server", "bridge", "java", "resources"].includes(key)) {
        throw new Error(`${key} is not a recognized agent-engine setting`);
      }
    }
  }

  const server = section(root?.server, "server");
  rejectUnknownKeys(server, ["host", "port"], "server");
  const bridge = section(root?.bridge, "bridge");
  rejectUnknownKeys(bridge, ["token-file"], "bridge");
  const java = section(root?.java, "java");
  rejectUnknownKeys(java, ["tools-url", "sessions-url", "token-file"], "java");
  const resources = section(root?.resources, "resources");
  rejectUnknownKeys(resources, ["java-tool-timeout-ms", "run-timeout-ms", "heartbeat-ms"], "resources");

  const toolsOrigin = httpUrl(requiredText(java, "java", "tools-url"), "java", "tools-url");
  const sessionsUrl = text(java, "java", "sessions-url");
  const bridgeToken = await readTokenFile(requiredText(bridge, "bridge", "token-file"), "bridge.token-file");
  const javaTokenFile = text(java, "java", "token-file");
  const javaToolsToken = javaTokenFile
    ? await readTokenFile(javaTokenFile, "java.token-file")
    : bridgeToken;

  return {
    host: text(server, "server", "host") ?? "127.0.0.1",
    port: positiveInt(server, "server", "port", 3382),
    bridgeToken,
    javaToolsUrl: toolsOrigin.toString(),
    javaSessionsUrl: sessionsUrl ? httpUrl(sessionsUrl, "java", "sessions-url").toString()
      : new URL("/internal/agent/sessions", toolsOrigin).toString(),
    javaToolsToken,
    javaToolTimeoutMs: positiveInt(resources, "resources", "java-tool-timeout-ms", 60_000),
    runTimeoutMs: positiveInt(resources, "resources", "run-timeout-ms", 120_000),
    heartbeatMs: positiveInt(resources, "resources", "heartbeat-ms", 15_000),
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
