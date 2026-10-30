# 28: Local Mini-program Backend Acceptance

**What to build:** Make the customer mini-program Developer Tools build exercise the local `iot-agent-backend` contract without changing trial/release routes or authentication semantics, then complete a real dashboard and Agent acceptance.

**Blocked by:** Local Java WebClient reaching the customer upstream from this workstation.

**Status:** blocked-local-network

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

Observed:

- Public Runtime: HTTP 200 with `environment`, `energy`, `alerts`, `devices`, `automation`, and `errors` fields.
- Local Java backend: HTTP 503 `IOT_UPSTREAM_UNAVAILABLE` while loading the Company authorized device scope.
- Direct shell `curl` to the customer gateway succeeds through the workstation's existing network transport; direct Java WebClient access fails. `IOT_GATEWAY_PROXY` was tested temporarily and removed; it is not part of the backend configuration.

## Acceptance

- [ ] Start local Java backend with normal identity verification and local MySQL/Redis.
- [ ] Build the develop mini-program with `USE_LOCAL_DEVELOP_AGENT=true` only for the local acceptance artifact; restore it to `false` afterward.
- [ ] In Developer Tools, complete customer login and district initialization.
- [ ] Dashboard returns real environment, energy, safety, and device data instead of the unavailable state.
- [ ] Company Agent conversation creates, reads, streams NDJSON to a terminal event, and preserves the same customer auth headers.
- [ ] Office candidate → state readback → action draft path is verified without executing physical control.
- [ ] No production cloud Runtime or customer gateway deployment is changed.
- [ ] After all tickets close, run the single requested two-axis review.
