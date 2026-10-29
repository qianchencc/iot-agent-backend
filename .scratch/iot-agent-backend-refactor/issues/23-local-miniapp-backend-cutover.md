# 23: Local Miniapp Backend Cutover

**What to build:** Add a reversible develop-only mini-program endpoint profile that points only Agent traffic to the local Java backend while login, SSO, gateway, and unrelated platform APIs retain their existing customer routes.

**Blocked by:** 18, 22

**Status:** open-local-cutover-preparation

- [ ] Define a non-production `develop.local` endpoint profile with `agentBaseUrl=http://127.0.0.1:3205/api`; do not change trial/release endpoints.
- [ ] Verify headers from the mini-program (`Authorization`, `Tenantcode`, source query/body) match the Java backend boundary.
- [ ] Verify WeChat Developer Tools can reach the local HTTP endpoint with legal-domain validation disabled; no production endpoint or cloud deployment is changed.
- [ ] Keep the change reversible and isolated from customer delivery output.

## Comments

- Current `mp/config/settings.js` develop profile still points to `https://mp-agent.qianc.ltd/api`; this ticket has not changed the frontend.
- Desktop Developer Tools/local HTTP is a development-only acceptance path. Real-device HTTPS and production release remain separate gates.
