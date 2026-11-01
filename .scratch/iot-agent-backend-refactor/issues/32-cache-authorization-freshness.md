# 32: 权限与授权范围缓存的新鲜度

**Type:** grilling
**Status:** resolved
**Blocked by:** 31

## Question

权限不另行扩张为独立缓存设计。现有认证/授权校验作为既有业务不变量保留；本地图不新增撤权缓存机制、权限日级快照或额外失效通道。后续只有在实际业务验收暴露权限问题时，才另立具体票据。

## Answer

用户明确认为撤权等边缘情况不应在当前缓存地图中单独设计；本票据不进入实现路线，相关复杂度不继续扩张。

## Evidence to use

- `mp/utils/agent-session.js`
- `mp/apis/agent/AGENTS.md`
- `docs/acceptance/internal/2026-10-27-acceptance-test-cases.md` 的撤权与跨身份场景
- Runtime `authorized-device-scope`、目录缓存说明
