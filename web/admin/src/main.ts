import { createApp } from "vue";
import { createPinia } from "pinia";
import ArcoVue from "@arco-design/web-vue";
import App from "./App.vue";
import { router } from "./router";
import { useSessionStore } from "./stores/session";
import { installUnauthorizedHandler } from "./api/admin";
import "@arco-design/web-vue/dist/arco.css";
import "./styles/global.css";
import "./styles/tokens.css";

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
app.use(router);
app.use(ArcoVue);

const session = useSessionStore(pinia);
// One request interceptor for the lifetime of the app; reads the live store.
session.attachToken();
// Any authed 401 clears the session and returns to login.
installUnauthorizedHandler(() => {
  session.csrfToken = null;
  session.authenticated = false;
  if (router.currentRoute.value.name !== "login") router.replace({ name: "login" });
});

app.mount("#app");
