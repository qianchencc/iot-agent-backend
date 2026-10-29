# 14: Report Artifact 与 PDF/XLSX

**What to build:** Company 能耗/观察报告在 Java 中本地生成、持久化、授权恢复和下载，重启后不重新读取客户上游，也不把 Redis 当作报告事实。

**Blocked by:** 11: Company 多源设备目录与 Control Reference；08: Task、Plan、Notification 与 Durable Scheduler

**Status:** ready-for-agent

- [ ] Report artifact 保存 metadata、origin、objective、status、固定设备集合、内容 blocks 和 generatedAt。
- [ ] 当前授权目录变化、缺失设备或跨用户引用时拒绝报告读取/恢复。
- [ ] GET report 与 Runtime response shape 一致，历史 report 不被后续计算覆盖。
- [ ] PDF 和 XLSX 下载支持格式校验、中文文件名、Content-Disposition、no-store 和失败错误体。
- [ ] partial/completed/preparing/failed 报告语义与 Runtime 一致。
- [ ] 迁移 Runtime report-summary、company-checkpoint-reports、company-energy-report-attachments 回归行为。

## Comments

- Runtime 报告是本地事实和渲染，不依赖客户 Report 下载 endpoint。
- Java 可选用受项目规范允许的 PDF/XLSX 库，但对外结构和授权语义必须以 Runtime 为准。
