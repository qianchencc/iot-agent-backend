# 12: Dashboard 与 Region 本地组合

**What to build:** Company Dashboard 和 Office Region 接口在 Java 中按 Runtime 语义组合当前授权目录、观察事实、任务摘要和已读标记，不再把本地组合误当成客户上游单一接口。

**Blocked by:** 11: Company 多源设备目录与 Control Reference；03: MySQL / Redis 业务事实基础设施

**Status:** ready-for-agent

- [ ] Dashboard 支持 summary、environment、energy、alerts、automation 分区和 source 校验。
- [ ] Dashboard 各分区独立读取，部分上游失败保留已取得事实并返回错误/缺数语义。
- [ ] Company read cache 的 fresh/stale/授权撤销边界与 Runtime 一致。
- [ ] Region devices、device-states、seen 和 attention 读取/写入均使用当前授权 scope。
- [ ] 未授权区域、跨用户事实、重复参数和上游不可用返回 Runtime 兼容状态码和错误体。
- [ ] 迁移 Runtime company-dashboard、regions、region-devices 回归行为到本地 Java contract tests。

## Comments

- Dashboard 和 Region 的主要逻辑属于 Java 本地组合，不等待新的客户观察 endpoint。
- `region_seen` 是 MySQL 业务事实，Redis 不能作为唯一来源。
