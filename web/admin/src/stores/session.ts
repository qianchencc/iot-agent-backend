import { defineStore } from "pinia";
import { adminApi, http } from "../api/admin";

// CSRF token lives in memory only — never persisted to storage (tech spec:
// no sensitive data in the browser beyond what the flow requires).
export const useSessionStore = defineStore("admin-session", {
  state: () => ({
    csrfToken: null as string | null,
    authenticated: false,
    bootstrapped: false
  }),
  actions: {
    attachToken() {
      http.interceptors.request.use((config) => {
        if (this.csrfToken && config.method && config.method.toUpperCase() !== "GET") {
          config.headers["x-admin-csrf"] = this.csrfToken;
        }
        return config;
      });
    },
    async login(password: string) {
      const { data } = await adminApi.login(password);
      this.csrfToken = data.csrfToken;
      this.authenticated = true;
    },
    /** Re-hydrate after a page refresh: cookie may still be valid. */
    async bootstrap() {
      try {
        const { data } = await adminApi.session();
        this.csrfToken = data.csrfToken;
        this.authenticated = true;
      } catch {
        this.csrfToken = null;
        this.authenticated = false;
      } finally {
        this.bootstrapped = true;
      }
    },
    async logout() {
      try {
        await adminApi.logout();
      } finally {
        this.csrfToken = null;
        this.authenticated = false;
      }
    }
  }
});
