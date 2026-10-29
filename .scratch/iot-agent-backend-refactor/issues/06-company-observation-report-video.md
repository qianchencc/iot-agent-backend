# 06: Company Observation、Report、Video 只读闭环

**What to build:** Agent 和小程序可以通过 Java 主服务读取 Company/Office 的环境、能耗、安全、区域、视频和报告事实，并保留 Runtime 的授权、缺数、陈旧和部分结果语义。

**Blocked by:** 02: Runtime 身份核验与稳定 Principal；03: MySQL / Redis 业务事实基础设施；04: Conversation 与 Pi Agent 生产闭环

**Status:** ready-for-agent

- [ ] 迁移 Company dashboard、environment、energy history/comparison、safety、region、video 和 report 只读接口。
- [ ] Observation adapter 依据 Runtime 路由、请求参数和 `{code,content}` 解析真实上游响应。
- [ ] 保留 source、principal、scope、时间窗口和报告引用校验，不能跨用户或跨来源读取。
- [ ] 明确区分 missing、stale、unverified、partial、preparing、unavailable 和授权失败。
- [ ] 视频只返回经授权且短时效的入口，不持久化凭据和流地址。
- [ ] Report 元数据、PDF/XLSX 下载和快照事实落 MySQL，不把 Redis 当作报告事实来源。
- [ ] 使用客户唯一生产上游执行脱敏只读验证；不控制设备、不创建任务、不发送通知。
- [ ] 现有小程序消费的 payload、错误体和状态码通过外部 contract tests。

## Comments

- Runtime observation、energy、safety、video、report 和 region 行为源已在原仓库；当前 Java 是 fail-closed observation shell，不再把“客户未提供 schema”作为前置阻塞。
