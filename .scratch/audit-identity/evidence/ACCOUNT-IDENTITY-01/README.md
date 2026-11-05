# ACCOUNT-IDENTITY-01：真实账号资料进入审计成员投影

- 真实上游：`GET /sso-api/account/me`
- 真实控制台：`http://127.0.0.1:5185/audit`
- 真实园区：XSJ
- 真实页面结果：成员列表显示上游姓名与手机号；事件成员列同步显示姓名。
- 脱敏结果：姓名 `刘*龙`，手机号 `172****1949`；不保存完整账号资料、Token 或原始响应。
- 实际接口状态：`GET /api/admin/audit/members?tenantCode=XSJ&principalId=<target>&limit=1` 返回200，成员投影含 `displayName`、`mobileNumber`、`principalId`，事件数量与来源聚合保持正常。
- 旧历史事件没有被改写。只要该主体的任一新审计事件带有资料，成员聚合即可展示该主体资料；后续事件由已核验账号资料自动写入。

工程回归：`AccountPrincipalVerifierTest`、`AuditAdminApiTest` 通过；控制台生产构建通过；固定Java仅替换本次隔离构建产物，Node与其他运行组件未替换。
