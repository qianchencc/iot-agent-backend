import axios, { AxiosError } from "axios";

/** Single axios instance: same-origin cookies carry `runtime_admin`; mutating
 * requests must also send the `x-admin-csrf` header and an `Origin` header that
 * matches the backend's configured ADMIN_ORIGIN (browsers attach Origin). */
export const http = axios.create({
  baseURL: "/api/admin",
  timeout: 15_000,
  headers: { "Content-Type": "application/json" }
});

export interface AdminError {
  code: string;
  message: string;
  issues?: string[];
}

export type ThinkingLevel = "off" | "low" | "medium" | "high";
export type MaxTokensField = "max_completion_tokens" | "max_tokens";

/** Public settings as returned by GET /config. Secret values are never present;
 * `secrets` carries configured booleans only. */
export interface AdminConfigValues {
  modelBaseUrl: string;
  modelId: string;
  contextWindow: number;
  maxOutputTokens: number;
  reasoning: boolean;
  thinkingLevel: string;
  supportsDeveloperRole: boolean;
  supportsReasoningEffort: boolean;
  supportsUsageInStreaming: boolean;
  maxTokensField: string;
  maxAgentTurns: number;
  maxToolCalls: number;
  officeInstructions: string;
  companyInstructions: string;
  replyInstructions: string;
  runTimeoutMs: number;
  draftTtlMs: number;
  heartbeatMs: number;
  readbackWaitMs: number;
  controlTimeoutMs: number;
  scanIntervalMs: number;
  catalogTtlMs: number;
  authoritativeStateTtlMs: number;
  actionIdTtlMs: number;
  energyReadTtlMs: number;
  langfuseEnabled: boolean;
  langfuseBaseUrl: string;
}

export interface AdminSecretState {
  configured: boolean;
  activeConfigured: boolean;
}

export interface AdminDeployment {
  host: string;
  port: number;
  version: string;
  environment: string;
  timezone: string;
}

export interface AdminConfigView {
  revision: number;
  /** Revision of the snapshot used by the next run; may lag `revision` when
   * deployment-owned fields are pending. */
  activeRevision: number;
  saved: AdminConfigValues;
  active: AdminConfigValues;
  secrets: Record<string, AdminSecretState>;
  locked: string[];
  pendingRestart: boolean;
  changedFields: string[];
  applyEnabled: boolean;
  deployment: AdminDeployment;
}

export interface AdminChangeRecord {
  revision: number;
  at: string;
  actor: string;
  changedFields: string[];
}

/** Field values accepted by PATCH /config; only changed fields are sent. */
export type ConfigChangeSet = Record<string, string | number | boolean>;

/** Called by main.ts after pinia exists: any 401 from an authed request
 * invalidates the session and bounces to login (same behaviour the
 * runtime console enforces: "auth failures return to login"). */
export function installUnauthorizedHandler(onUnauthorized: () => void) {
  http.interceptors.response.use(
    (response) => response,
    (error) => {
      if (error instanceof AxiosError && error.response?.status === 401) onUnauthorized();
      return Promise.reject(error);
    }
  );
}

export function errorOf(error: unknown, fallback: string): AdminError {
  if (error instanceof AxiosError && error.response) {
    const body = error.response.data as Partial<AdminError> | undefined;
    if (body && typeof body.message === "string" && body.message.length > 0) {
      const issues = Array.isArray(body.issues)
        ? body.issues.filter((item): item is string => typeof item === "string")
        : undefined;
      return {
        code: body.code ?? `HTTP_${error.response.status}`,
        message: body.message,
        ...(issues && issues.length > 0 ? { issues } : {})
      };
    }
    if (error.response.status === 401) return { code: "ADMIN_UNAUTHORIZED", message: "会话已失效，请重新登录" };
    return { code: `HTTP_${error.response.status}`, message: fallback };
  }
  if (error instanceof AxiosError && (error.code === "ECONNABORTED" || error.code === "ETIMEDOUT")) {
    return { code: "TIMEOUT", message: "请求超时，请稍后重试" };
  }
  return { code: "NETWORK", message: "无法连接后端服务，请确认已启动" };
}

export interface PiSessionHealth {
  code: string;
  message: string;
  details: { status: string; backend: "mysql"; scopes: number; documents: number; invalidJournals: number };
}

export const adminApi = {
  login: (password: string) => http.post<{ csrfToken: string }>("/login", { password }),
  session: () => http.get<{ csrfToken: string }>("/session"),
  logout: () => http.post<{ ok: boolean }>("/logout"),
  passwordPolicy: () => http.get<{ editable: boolean; minimumLength: number }>("/password"),
  config: () => http.get<AdminConfigView>("/config"),
  piSessionHealth: () => http.get<PiSessionHealth>("/pi-session/health"),
  saveConfig: (expectedRevision: number, changes: ConfigChangeSet) =>
    http.patch<AdminConfigView>("/config", { expectedRevision, changes }),
  changeHistory: () => http.get<{ changes: AdminChangeRecord[] }>("/changes"),
  restoreConfig: (expectedRevision: number, revision: number) =>
    http.post<AdminConfigView>("/restore", { expectedRevision, revision }),
  /** The Java probe allows up to 20s; do not abort it with the default 15s. */
  testModel: (changes: ConfigChangeSet) =>
    http.post<{ ok: boolean; message: string }>("/test-model", { changes }, { timeout: 30_000 }),
  prompts: () => http.get<{ revision: number; office: string; company: string }>("/prompts")
};
