# web/admin — 物联 Agent 管理控制台

Vite + Vue 3 + Element Plus + Pinia + Vue Router + Axios（纯 JavaScript，无 TypeScript）。
技术栈按客户《项目技术选型清单》（`docs/reference/项目技术选型清单.docx`）。

开发规范（组件用法、页面步骤、验证门槛）见 [`AGENTS.md`](./AGENTS.md)；
部署、环境变量、排障、升级见 [`docs/maintenance.md`](./docs/maintenance.md)。

## 本地开发

后端先按[仓根常规本地启动](../../README.md#常规本地启动)启动 Java（`3381`）；控制台在本目录以常规命令启动：

```sh
npm ci        # 首次
npm run dev   # http://127.0.0.1:5185/login
```

- 控制台监听 `0.0.0.0:5185`（strict port，被占用直接失败，不切换到其他端口），浏览器使用 `http://127.0.0.1:5185/login` 或本机局域网IP；仅用于可信开发网络，不作为生产公网服务。
- Vite仅将 `/api/admin` 代理到 `127.0.0.1:3381`（`ADMIN_API_TARGET` 可覆盖）。合法的同源 HTTP Origin 规范化到 `ADMIN_PROXY_ORIGIN`（默认 `http://127.0.0.1:5185`，必须与 Java `admin.origin` 一致）；外部 Origin 保持原值，由 Java 拒绝。两者均为非秘密 URL，需要覆盖时在启动 `npm run dev` 的 shell 中显式设置。
- 认证契约：`POST /api/admin/login` → `{ csrfToken }` + cookie `runtime_admin`；非 GET 带 `x-admin-csrf`。没有放宽管理员鉴权或 CSRF。
- 历史统一启动器（`tools/start-fixed-backend.py` 的 `console` 窗口）已退役；当时的启动与重入验证记录见[控制台统一启动验证（历史）](../../.scratch/fixed-console-startup/README.md)。

## 检查

```sh
npm run build       # vite build
```
