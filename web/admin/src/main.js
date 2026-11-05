import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import App from "./App.vue";
import { router } from "./router";
import { useSessionStore } from "./stores/session";
import { installUnauthorizedHandler } from "./api/admin";
import "element-plus/dist/index.css";
import "./styles/tokens.css";
import "./styles/global.css";

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
app.use(router);
app.use(ElementPlus, { locale: zhCn });

const session = useSessionStore(pinia);
session.attachToken();
installUnauthorizedHandler(() => {
  session.csrfToken = null;
  session.authenticated = false;
  if (router.currentRoute.value.name !== "login") router.replace({ name: "login" });
});

app.mount("#app");
