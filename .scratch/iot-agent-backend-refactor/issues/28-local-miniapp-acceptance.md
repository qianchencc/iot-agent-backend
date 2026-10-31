# 28: Local Mini-program Backend Acceptance

**What to build:** Make the customer mini-program Developer Tools build exercise the local `iot-agent-backend` contract without changing trial/release routes or authentication semantics, then complete a real dashboard and Agent acceptance.

**Blocked by:** none

**Status:** resolved-local-backend-and-agent-acceptance

## Contract facts

- `mp/config/settings.js` already has a develop-only `develop.local` profile: Agent `http://127.0.0.1:3205/api`; login, SSO, customer gateway, and video remain on their existing origins.
- The local switch is `USE_LOCAL_DEVELOP_AGENT`; it is currently `false` by default and must not be changed for trial/release.
- Agent requests use the existing customer session headers from `utils/agent-session.js`: `Authorization` and `Tenantcode`. No frontend login implementation is allowed.
- Runtime-compatible Java routes were checked against the client methods, including `/agent/dashboard`, `/agent/company-devices`, tasks, notifications, and conversation streaming.

## Red feedback loop

```sh
set -a; . ./.env; set +a
for base in 'https://mp-agent.qianc.ltd/api' 'http://127.0.0.1:3205/api'; do
  curl -sS --max-time 45 "$base/agent/dashboard?source=company" \
    -H "Authorization: $IOT_TEST_AUTHORIZATION" \
    -H "Tenantcode: $IOT_TEST_TENANTCODE"
done
```

- Historical red run: before customer-network recovery, public Runtime returned HTTP 200 while local Java returned HTTP 503 `IOT_UPSTREAM_UNAVAILABLE` for the Company dashboard. This was an environment transport failure, not a route or auth mismatch.
- Current local run: with normal identity verification, local Java returns HTTP 200 for Company dashboard, Company devices, environment/alerts sections, energy daily, tasks, and notifications; the same `Authorization` and `Tenantcode` headers are used.
- Direct shell `curl` to the customer gateway and Java WebClient both succeed after network recovery. `IOT_GATEWAY_PROXY` was tested temporarily and removed; it is not part of the backend configuration.

## Acceptance

- [x] Start local Java backend with normal identity verification and local MySQL/Redis.
- [x] Build the develop mini-program with `USE_LOCAL_DEVELOP_AGENT=true` only for the local acceptance artifact; restore source to `false` afterward.
- [x] Open the local compiled artifact in WeChat Developer Tools; existing customer session/district initialization remains unchanged and the home page loads.
- [x] Dashboard and section routes return real environment, energy, safety, and device data instead of the unavailable state; the backend result is 200 with 47 Company devices and no route-level errors.
- [x] Company Agent conversation creates, reads, streams NDJSON to `run.completed`, and preserves the same customer auth headers; final environment and energy read-only tools completed.
- [x] Office candidate → state readback → action draft path is verified without executing physical control; final event is `run.awaiting_confirmation` with `PENDING_CONFIRMATION`.
- [x] No production cloud Runtime or customer gateway deployment is changed.
- [ ] After all tickets close, run the single requested two-axis review.
