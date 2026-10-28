# 08: Company / Office 只读业务闭环

**What to build:** 迁移小程序依赖的 dashboard、environment、safety、energy、region、video 和 report 只读能力，保持事实、缺数、授权和部分结果语义。

**Blocked by:** 04: Conversation 流式闭环；05: IoT 身份与 Authorized Device Scope

**Status:** ready-for-agent

- [ ] Company dashboard 和 Office region 读取接口保持 source 与授权边界。
- [ ] 环境历史、能耗统计/对比、告警台账和视频取流保持原参数、错误和数据时间语义。
- [ ] 报告事实、PDF/XLSX 下载和报告引用保持授权裁剪。
- [ ] 缺数、陈旧、未核验、部分覆盖和读取失败不能被改写为普通空成功。
- [ ] 小程序现有 observation 卡片和报告预览无需修改即可消费返回值。
- [ ] 只读 parity 可在旧 Runtime 与 Java 结果之间进行，不触发真实控制。
