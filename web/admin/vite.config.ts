import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// Dev-only proxy: the browser origin is this dev server; ADMIN_ORIGIN on the
// backend must be set to the printed origin (default http://127.0.0.1:5173).
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.ADMIN_API_TARGET ?? "http://127.0.0.1:3200",
        changeOrigin: true
      }
    }
  }
});
