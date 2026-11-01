# 44: 本地小程序后端切换与业务 Smoke

**Type:** task
**Status:** resolved
**Blocked by:** 38, 40, 43

## 2026-11-02 验收范围纠正

下方记录仅为当时构建与接口smoke证据，不证明小程序整体业务验收完成。现已出现页面超过10秒及能耗资料错误，完整页面验收由50号重新执行；不能用HTTP200替代。


## Goal

冻结本地验收链路：开发版小程序 Agent 请求指向本机 backend，trial/release 继续客户网关；后端在本机运行并完成关键业务接口真实 smoke。

## Acceptance

- `mp/config/settings.js` 的 develop.local 使用 `http://127.0.0.1:3205/api`，没有 `IOT_GATEWAY_PROXY` 依赖。
- MP `npm run test:agent`：213 passed；`npm run lint:boundaries` 通过。
- HBuilderX `mp-weixin` 编译成功；已有 Vue sourcemap/export 警告记录，不阻断产物生成。
- 本地 backend 真实接口：healthz、regions、company-devices、dashboard environment/energy、safety-events 均返回 200；Office regions 连续请求约 4.19s → 0.04s，environment 约 2.29s → 0.54s，energy 约 22.44s → 1.38s，safety 约 0.52s。
- 生产云 Runtime 未修改、未切换、未作为本轮验收目标。
