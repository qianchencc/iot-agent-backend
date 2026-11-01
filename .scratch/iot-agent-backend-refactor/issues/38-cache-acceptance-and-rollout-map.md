# 38: 缓存验收、故障矩阵与推进顺序

**Type:** grilling
**Status:** resolved
**Blocked by:** 34, 35, 36, 37

## 2026-11-02 执行修订

新增必须通过的业务场景：上游被挂起时普通HTTP仍及时返回已有数据或准备状态，不能用热缓存单次200证明；微信冷准备自动落位、热打开、手动刷新、身份隔离与重启恢复分别记录。已明确不做复杂平台，但必须完成现有Redis快照与后台更新的实际闭环。实施路线见[整改规格](../cache-current-read-spec.md)与47–50号票。


## Question

形成最终实现路线：按哪些票据实施，如何用后端真实业务和微信开发者工具验收；覆盖命中/未命中、深夜刷新、1 分钟刷新、手动刷新、权限撤销、跨身份、Redis down、上游 503、部分数据、重启、多实例、控制确认和 readback。定义每个阶段的停止条件和不影响生产 Runtime 的本地验收方式。

## Answer

实施顺序：

1. 先找业务路径中的明显重复读取、串行等待和错误缓存使用；
2. 只对首屏、常用子页面、设备查询工具做必要 backend/frontend 矫正；
3. 保留现有 frontend 60 秒读快照和事实/事务实时规则；
4. 只有实际慢点证明需要时，才增加 backend 进程内快照/在途合并；
5. 不先建设统一 Redis cache，也不为性能目标引入复杂预热或索引平台；
6. 最后在本机 backend + 微信开发者工具完成业务抽样和体验检查。

验证内容：

- 首屏可见数据、主要子页面、设备查询工具、手动刷新；
- Company/Office dashboard、区域设备、设备状态、能耗、告警、任务/通知；
- 控制草案、确认、readback、视频短地址和报告下载不被普通快照污染；
- 上游慢/部分失败时展示明确状态；
- MySQL/Redis/Node sidecar 健康，生产云 Runtime 不修改。

## Evidence to use

- backend `development-standards.md`
- mp `docs/README.md` 与 `docs/acceptance/`
- Runtime/MP 现有回归及本地 backend 验收记录
