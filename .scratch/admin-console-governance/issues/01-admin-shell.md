# 01: 管理员登录与控制台壳

**What to build:** 管理员使用独立部署凭据登录控制台，并进入包含白名单、参数配置、用户审计三个入口的Element Plus控制台。

**Blocked by:** None (can start immediately)

**Status:** completed（2026-11-05，实现及隔离管理端验证；边界见[05](05-console-acceptance.md)）

- [x] 启动方式使用明确外置配置，不加载`.env`；部署凭据不进入浏览器可读存储
- [x] 管理员登录、会话失效、CSRF/Origin校验和未配置安全失败有明确反馈；契约与实际独立登录分别验证
- [x] 控制台完成Arco到Element Plus的干净切换，保留纯JavaScript，三个业务入口可访问
- [x] 未接通功能不显示成功按钮；保存与采用分别显示，模型草稿检查不保存
- [x] Java、sidecar、启动工具和开发说明迁移；Java聚焦62项、Node34项及类型／构建检查通过
