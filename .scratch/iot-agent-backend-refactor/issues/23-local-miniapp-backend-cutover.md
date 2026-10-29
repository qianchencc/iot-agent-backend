# 23: Local Miniapp Backend Cutover

**What to build:** Add a reversible develop-only mini-program endpoint profile that points only Agent traffic to the local Java backend while login, SSO, gateway, and unrelated platform APIs retain their existing customer routes.

**Blocked by:** 18, 22

**Status:** resolved-local-cutover-and-devtools-transport

- [x] Define a non-production `develop.local` endpoint profile with `agentBaseUrl=http://127.0.0.1:3205/api`; do not change trial/release endpoints.
- [x] Verify headers from the mini-program (`Authorization`, `Tenantcode`, source query/body) match the Java backend boundary.
- [x] Verify WeChat Developer Tools can reach the local HTTP endpoint with legal-domain validation disabled; no production endpoint or cloud deployment is changed.
- [x] Keep the change reversible and isolated from customer delivery output.

## Comments

- `mp/config/settings.js` was temporarily switched to `develop.local` for the acceptance build; source is restored to the safe non-local default after evidence capture. Trial/release profiles were not changed.
- WeChat Developer Tools delivered real `Authorization` and `Tenantcode` headers to `127.0.0.1:3205`; gateway/SSO remained customer upstreams. Real-device HTTPS and production release remain separate gates.
