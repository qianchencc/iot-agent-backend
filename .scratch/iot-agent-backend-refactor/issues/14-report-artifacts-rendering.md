# 14: Report Artifact 与 PDF/XLSX

**What to build:** Company 能耗/观察报告在 Java 中本地生成、持久化、授权恢复和下载，重启后不重新读取客户上游，也不把 Redis 当作报告事实。

**Blocked by:** 11: Company 多源设备目录与 Control Reference；08: Task、Plan、Notification 与 Durable Scheduler

**Status:** implemented-local-core-conversation-generation-open-runtime-fixtures

- [x] Report artifact 保存 metadata、origin、objective、status、固定设备集合、内容 blocks 和 generatedAt。
- [x] 当前授权目录变化、缺失设备或跨用户引用时拒绝报告读取/恢复。
- [x] GET report 与 Runtime response shape 一致，历史 report 不被后续计算覆盖。
- [x] PDF 和 XLSX 下载支持格式校验、中文文件名、Content-Disposition、no-store 和失败错误体。
- [x] partial/completed/preparing/failed 报告语义与 Runtime 一致。
- [ ] 迁移 Runtime report-summary、company-checkpoint-reports、company-energy-report-attachments 回归行为；conversation-generated report flow and block-level device authorization are ticket 20.

## Comments

- Terminal task checkpoint→report producer, MySQL snapshot persistence, authorization, and PDF/XLSX renderers are implemented. Conversation generation and nested block authorization now have a Java seam; Runtime report-summary/attachment fixtures and complete public contract evidence remain open in ticket 20/21.
