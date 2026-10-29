import { createRouter, createWebHistory } from "vue-router";
import { useSessionStore } from "../stores/session";
import LoginView from "../views/LoginView.vue";
import HomeView from "../views/HomeView.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/login", name: "login", component: LoginView },
    { path: "/", name: "home", component: HomeView },
    { path: "/:pathMatch(.*)*", redirect: "/" }
  ]
});

router.beforeEach(async (to) => {
  const session = useSessionStore();
  if (!session.bootstrapped) await session.bootstrap();
  if (to.name === "login") {
    return session.authenticated ? { name: "home" } : true;
  }
  if (!session.authenticated) {
    return { name: "login", query: to.fullPath === "/" ? {} : { redirect: to.fullPath } };
  }
  return true;
});
