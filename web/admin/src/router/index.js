import { createRouter, createWebHistory } from "vue-router";
import { useSessionStore } from "../stores/session";
import LoginView from "../views/LoginView.vue";
import AppShell from "../layouts/AppShell.vue";
import HomeView from "../views/HomeView.vue";
import AllowlistView from "../views/AllowlistView.vue";
import AuditView from "../views/AuditView.vue";

export const router = createRouter({
  history: createWebHistory(),
  scrollBehavior(to) {
    if (to.hash) return { el: to.hash, behavior: "smooth", top: 76 };
    return { top: 0 };
  },
  routes: [
    { path: "/login", name: "login", component: LoginView },
    {
      path: "/",
      component: AppShell,
      children: [
        { path: "", name: "config", component: HomeView, meta: { title: "参数配置" } },
        { path: "config", redirect: "/" },
        { path: "allowlist", name: "allowlist", component: AllowlistView, meta: { title: "用户白名单" } },
        { path: "audit", name: "audit", component: AuditView, meta: { title: "用户操作审计" } }
      ]
    }
  ]
});

router.beforeEach(async (to) => {
  const session = useSessionStore();
  if (!session.bootstrapped) await session.bootstrap();
  if (to.name === "login") {
    return session.authenticated ? { name: "config" } : true;
  }
  if (!session.authenticated) {
    return { name: "login", query: to.fullPath === "/" ? {} : { redirect: to.fullPath } };
  }
  return true;
});
