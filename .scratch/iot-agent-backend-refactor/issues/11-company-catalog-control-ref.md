# 11: Company 多源设备目录与 Control Reference

**What to build:** Company 来源能够依据当前 principal 读取 Runtime 规定的环境、能耗、安全、照明、门禁、空调和视频设备范围，并为后续 Dashboard、Region、Video 和控制流程提供可信的设备绑定事实。

**Blocked by:** 02: Runtime 身份核验与稳定 Principal；03: MySQL / Redis 业务事实基础设施；05: Office scope 与只读工具

**Status:** implemented-local (Runtime-derived Company catalog, binding model, local contract fixture and ego-lite read-only smoke passed)

- [x] Company catalog 遍历 Runtime 规定的 observation、AC、lighting、access、monitor 多源目录和区域树，只使用叶子区域成员。
- [x] 支持裸数组、Spring page、204、分页 `last`、去重和不完整分页 fail-closed 语义。
- [x] Authorized Device Scope 包含 Runtime 所需的 `controlRef`、`hubDeviceId`、区域绑定和 observation-only 标记；视频不能进入控制范围。
- [x] Company catalog 返回不泄露 token、hub binding、内部授权键或客户凭据。
- [x] 同一 principal 的 Company 目录只读失败、撤权、绑定缺失和重复冲突返回明确错误，不返回空成功。
- [x] 通过 Runtime production-protocol fixture 验证多源目录、分页、去重、授权隔离和 binding 完整性。

## Comments

- Runtime 已提供完整上游路径、payload、分页和测试夹具；本票不等待客户补充协议。
- 2026-10-29：Company catalog now traverses Runtime-derived observation model-code pages, smart lighting/access/monitor area trees, AC area/device pages and AC provider bindings. `IotAdapterContractTest` and the full Maven suite passed; local ego-lite read-only smoke against the customer gateway returned HTTP 200 with 47 devices (`environment`/`energy`/`safety`), while public device views contained only `id/name/kind/location`.
- 本票是 Phase 2 第一条 ready ticket，完成后解除 Dashboard/Region、Video 和真实控制 adapter 的代码依赖。
- 禁止对客户生产上游发送控制；本票只实现目录和绑定读取。
