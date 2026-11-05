# web/admin — 物联 Agent 管理控制台

Vite + Vue 3 + Element Plus + Pinia + Vue Router + Axios（纯 JavaScript，无 TypeScript）。
技术栈按客户《项目技术选型清单》（`docs/reference/项目技术选型清单.docx`）。

开发规范（组件用法、页面步骤、验证门槛）见 [`AGENTS.md`](./AGENTS.md)；
部署、环境变量、排障、升级见 [`docs/maintenance.md`](./docs/maintenance.md)。

## 本地开发

固定开发环境从仓根执行一次：

```sh
/Users/qianchen/anaconda3/bin/python tools/start-fixed-backend.py
# → Java3381、Node3382、console5185，均常驻 tmux fixed-backend
```

- 默认部署配置需包含 `console` 段，结构见[仓根启动说明](../../README.md#固定开发后端tmux-常驻)。首次安装依赖执行 `npm ci`；正常启动无需再单独运行前端。
- 控制台监听 `0.0.0.0:5185`，浏览器使用 `http://127.0.0.1:5185/login` 或本机局域网IP；仅用于可信开发网络，不作为生产公网服务。
- 单独调试仍可在本目录 `npm run dev`，但应先停止自己的控制台窗口；5185被占用会直接失败，不切换到其他端口。
- Vite仅将 `/api/admin` 代理到 `127.0.0.1:3381`（`ADMIN_API_TARGET` 可覆盖）。合法的同源 HTTP Origin 规范化到 `ADMIN_PROXY_ORIGIN`（默认 `http://127.0.0.1:5185`，必须与 Java 配置一致）；外部 Origin 保持原值，由 Java 拒绝。
- 认证契约：`POST /api/admin/login` → `{ csrfToken }` + cookie `runtime_admin`；非 GET 带 `x-admin-csrf`。没有放宽管理员鉴权或 CSRF。

## 检查

```sh
npm run build       # vite build
```
