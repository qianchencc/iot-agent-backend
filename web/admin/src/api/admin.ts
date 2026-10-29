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
}

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
      return { code: body.code ?? `HTTP_${error.response.status}`, message: body.message };
    }
    if (error.response.status === 401) return { code: "ADMIN_UNAUTHORIZED", message: "会话已失效，请重新登录" };
    return { code: `HTTP_${error.response.status}`, message: fallback };
  }
  return { code: "NETWORK", message: "无法连接后端服务，请确认已启动" };
}

export const adminApi = {
  login: (password: string) => http.post<{ csrfToken: string }>("/login", { password }),
  session: () => http.get<{ csrfToken: string }>("/session"),
  logout: () => http.post<{ ok: boolean }>("/logout"),
  passwordPolicy: () => http.get<{ editable: boolean; minimumLength: number }>("/password")
};
