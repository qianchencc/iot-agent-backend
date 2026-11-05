import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// Dev-only same-origin proxy. Java retains its configured Origin and CSRF checks;
// LAN/localhost browser origins are normalized only when they match this server.
export default defineConfig({
  envDir: false,
  plugins: [vue()],
  server: {
    port: 5185,
    host: "0.0.0.0",
    strictPort: true,
    proxy: {
      "/api/admin": {
        target: process.env.ADMIN_API_TARGET ?? "http://127.0.0.1:3381",
        changeOrigin: true,
        configure(proxy) {
          proxy.on("proxyReq", (outgoing, incoming) => {
            if (incoming.headers.origin === `http://${incoming.headers.host}`) {
              outgoing.setHeader("Origin", process.env.ADMIN_PROXY_ORIGIN ?? "http://127.0.0.1:5185");
            }
          });
        }
      }
    }
  }
});
