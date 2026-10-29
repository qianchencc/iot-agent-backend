# 06: Company Observation、Report、Video 只读闭环

**What to build:** Agent 和小程序可以通过 Java 主服务读取 Company/Office 的环境、能耗、安全、区域、视频和报告事实，并保留 Runtime 的授权、缺数、陈旧和部分结果语义。

**Blocked by:** 02: Runtime 身份核验与稳定 Principal；03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环

**Status:** partially-implemented (environment/energy direct read adapters and redaction pass; dashboard/safety composition and report/video require remaining Runtime route/protocol migration)

- [ ] 迁移 Company dashboard、environment、energy history/comparison、safety、region、video 和 report 只读接口。
- [x] Observation adapter 依据 Runtime 路由、请求参数和 `{code,content}` 解析真实上游响应。
- [x] 保留 source、principal、scope、时间窗口和报告引用校验，不能跨用户或跨来源读取。
- [x] 明确区分 missing、stale、unverified、partial、preparing、unavailable 和授权失败。
- [ ] 视频只返回经授权且短时效的入口，不持久化凭据和流地址。
- [ ] Report 元数据、PDF/XLSX 下载和快照事实落 MySQL，不把 Redis 当作报告事实来源。
- [x] 使用客户唯一生产上游执行脱敏只读验证；不控制设备、不创建任务、不发送通知。
- [ ] 现有小程序消费的 payload、错误体和状态码通过外部 contract tests。

## Comments

- 2026-10-29：Real customer read-only smoke: `/api/agent/environment-history?source=company` returned 200 with the upstream page envelope. Adapter maps Runtime date/page/sort parameters and recursively redacts secret-shaped fields. Dashboard/safety composition and report/video are explicit 503 until their Runtime-derived route/action protocols and MySQL report snapshot path are migrated; no empty success fallback.
