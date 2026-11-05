import axios, { AxiosError } from "axios";

/** Single axios instance: same-origin cookies carry `runtime_admin`; mutating
 * requests must also send the `x-admin-csrf` header and an `Origin` header that
 * matches the backend's configured ADMIN_ORIGIN (browsers attach Origin). */
export const http = axios.create({
  baseURL: "/api/admin",
  timeout: 15_000,
  headers: { "Content-Type": "application/json" }
});

/** Called by main.js after pinia exists: any 401 from an authed request
 * invalidates the session and bounces to login. */
export function installUnauthorizedHandler(onUnauthorized) {
  http.interceptors.response.use(
    (response) => response,
    (error) => {
      if (error instanceof AxiosError && error.response?.status === 401) onUnauthorized();
      return Promise.reject(error);
    }
  );
}

/**
 * Normalize a backend error body `{code,message,issues?}` or a network
 * failure into a shape views can render without leaking internals.
 */
export function errorOf(error, fallback) {
  if (error instanceof AxiosError && error.response) {
    const body = error.response.data;
    if (body && typeof body.message === "string" && body.message.length > 0) {
      const issues = Array.isArray(body.issues)
        ? body.issues.filter((item) => typeof item === "string")
        : undefined;
      return {
        code: body.code ?? `HTTP_${error.response.status}`,
        message: body.message,
        ...(issues && issues.length > 0 ? { issues } : {})
      };
    }
    if (error.response.status === 401) return { code: "ADMIN_UNAUTHORIZED", message: "会话已失效，请重新登录" };
    if (error.response.status === 404) {
      return { code: "HTTP_404", message: "该功能在当前后端不可用，请联系部署方确认服务版本" };
    }
    return { code: `HTTP_${error.response.status}`, message: fallback };
  }
  if (error instanceof AxiosError && (error.code === "ECONNABORTED" || error.code === "ETIMEDOUT")) {
    return { code: "TIMEOUT", message: "请求超时，请稍后重试" };
  }
  return { code: "NETWORK", message: "无法连接后端服务，请确认已启动" };
}

export const adminApi = {
  login: (password) => http.post("/login", { password }),
  session: () => http.get("/session"),
  logout: () => http.post("/logout"),
  config: () => http.get("/config"),
  piSessionHealth: () => http.get("/pi-session/health"),
  saveConfig: (expectedRevision, changes) =>
    http.patch("/config", { expectedRevision, changes }),
  changeHistory: () => http.get("/changes"),
  restoreConfig: (expectedRevision, revision) =>
    http.post("/restore", { expectedRevision, revision }),
  /** The Java probe allows up to 20s; do not abort it with the default 15s. */
  testModel: (changes) => http.post("/test-model", { changes }, { timeout: 30_000 }),
  prompts: () => http.get("/prompts"),
  allowlist: () => http.get("/allowlist"),
  addAllowlistedUser: (expectedRevision, user) =>
    http.post("/allowlist", { expectedRevision, ...user }),
  removeAllowlistedUser: (expectedRevision, user) =>
    http.post("/allowlist/remove", { expectedRevision, ...user }),
  audit: (filters) => http.get("/audit", { params: filters }),
  auditTenants: () => http.get("/audit/tenants"),
  auditEventTypes: () => http.get("/audit/event-types"),
  auditDetail: (eventId) => http.get(`/audit/${encodeURIComponent(eventId)}`),
  auditMembers: (filters) => http.get("/audit/members", { params: filters })
};
