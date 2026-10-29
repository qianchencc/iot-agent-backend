# web/admin — 控制台开发规范

物联 Agent 后端管理控制台（Vue 3 + Arco Design Vue）。本地启动见 `README.md`；部署、升级、排障见 `docs/maintenance.md`。本文件是改代码时必须遵守的规范。

## 铁律（违反 = 后端拒绝或泄露面扩大）

- **所有请求走 `src/api/admin.ts` 的 `http` 单例**，组件/视图禁止 `fetch`、禁止新建 axios 实例、禁止写 `/api/admin` 前缀以外的路径。
- **CSRF token 只存内存**（Pinia session store），不落 `localStorage`/`sessionStorage`/cookie。token 丢失 = 调 `GET /session` 重建，绝不自行生成。
- **secret 字段只支持替换/清空，后端永不回读**。UI 不得显示已存 secret 值；输入框留空表示"不改动"。
- **超过 2^53 的 ID/时间戳一律按 string 处理**（`JSON.parse` 后会丢精度），类型标注 `string`，不做数值运算。
- **业务判断在前端只做展示态**：是否可保存、是否冲突、错误文案以服务端返回为准；前端校验只做即时体验，不当作正确性保证。

## 组件规范

- **控件只用 Arco Design Vue**（`<a-*>`，已全局注册）。已有样式体系（tokens/global）覆盖不到的场景才写样式，且必须用 `var(--color-*)` 系列 token——禁止硬编码 hex。
- **主题通过 `src/styles/tokens.css` 改 Arco token**（subdued green，`--arcoblue-*` 映射 runtime admin 的 `#275e47` 绿）。要换色/调肤，改这一处；不在组件里覆盖颜色。
- **表单用 `<a-form>` + `@submit-success`**，提交方法 `async`，`busy` 期间锁按钮（`:loading`）；每个输入 `allow-clear`。
- **错误反馈用 `<a-alert type="error">` 就地展示**（不弹 toast 刷屏）；成功用 `Message.success`；破坏性操作（apply/restore/logout 放弃草稿）用 `Modal.confirm`/`a-popconfirm` 二次确认。
- **文案全部中文**，直接给操作者看的句子（"管理员密码不正确"）不展示后端 `code`；`code` 只进 console/日志。
- **会话页结构**：左侧固定侧栏 + 顶部条 + `page-content` 内容区，对齐 runtime admin 布局（232px 侧栏、`max-width: 1120px` 内容）。新页面沿用 `HomeView.vue` 的 `<a-layout>` 骨架，不另起布局。
- **草稿语义**（对标 runtime）：未保存修改跨路由保留；离开/登出前二次确认放弃；`expectedRevision` 提交防并发覆盖。新配置页必须实现这三点，不能只 PATCH 不管冲突。

## 新增页面步骤（顺序执行）

1. `src/api/` 按域补 `adminApi` 方法（`adminApi.xxx()`，返回类型写明）。
2. `src/views/` 加 `XxxView.vue`，用 `HomeView` 同款 `<a-layout>` + `<a-card>`。
3. `src/router/index.ts` 注册路由；`beforeEach` 守卫已统一处理登录态，页面内不再判。
4. 需要跨页状态才进 `stores/`；纯页面状态用 `ref`/`reactive`，不进 Pinia。
5. 起后端 + `npm run dev`，**用 ego-browser 实际走一遍**：登录 → 进新页 → 触发成功态与至少一个错误态 → 刷新验证续存。

## 验证门槛（改动后必过）

- `npm run typecheck`（vue-tsc strict，无 any 逃逸）
- `npm run build`
- ego-browser 真实走一遍受影响路径（不拿 typecheck 当 UI 验证）

## 文件布局

```
src/api/      接口封装与错误规整（admin.ts 单例 http + errorOf）
src/stores/   Pinia；session.ts = 登录态 + CSRF（内存）
src/router/   路由 + 登录守卫
src/views/    一页一文件，Layout 复用
src/components/ 跨页复用控件（尚无；页内私有组件就近放）
src/styles/   global.css（reset/布局）+ tokens.css（Arco 主题色）
```

## 已知约束（不要绕）

- 后端 `AdminBoundaryFilter` 对 `/api/admin/*` 强制：非 GET 必须 `Origin` 精确匹配 `ADMIN_ORIGIN` + `x-admin-csrf`；cookie `SameSite=Strict` + `path=/api/admin`。跨域调试别试着绕，改 `ADMIN_ORIGIN` 或代理。
- 登录限流：同 IP 5 次/5 分钟失败后 429；测试别连续试错密码。
- 密码改成功 = 所有会话立即失效，前端要接 401 跳登录（拦截器已处理）。
- `ADMIN_PASSWORD` 环境变量存在时改密功能被禁（返回 `ADMIN_PASSWORD_DEPLOYMENT_MANAGED`），UI 对 409 要显示部署提示而非失败。
