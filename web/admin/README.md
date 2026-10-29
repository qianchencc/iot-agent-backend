# web/admin — 物联 Agent 管理控制台

Vite + Vue 3 + TypeScript + Arco Design Vue + Pinia + Vue Router + Axios。
技术栈按客户《项目技术选型清单》（`docs/reference/项目技术选型清单.docx`）。

开发规范（组件用法、页面步骤、验证门槛）见 [`AGENTS.md`](./AGENTS.md)；
部署、环境变量、排障、升级见 [`docs/maintenance.md`](./docs/maintenance.md)。

## 本地开发

```sh
# 1) 后端（仓根）：ADMIN_ORIGIN 必须等于浏览器访问的源
JAVA_HOME=/opt/homebrew/opt/openjdk \
ADMIN_PASSWORD=admin12345 \
ADMIN_ORIGIN=http://127.0.0.1:5173 \
mvn spring-boot:run

# 2) 前端
cd web/admin && npm install && npm run dev
# → http://127.0.0.1:5173
```

- Vite dev server 把 `/api` 代理到 `127.0.0.1:3200`（`ADMIN_API_TARGET` 可覆盖）。
- 认证契约（对齐 runtime `docs/api/openapi.yaml`）：`POST /api/admin/login`
  → `{ csrfToken }` + cookie `runtime_admin`；非 GET 带 `x-admin-csrf`。

## 检查

```sh
npm run typecheck   # vue-tsc
npm run build       # typecheck + vite build
```
