# 25: WeChat Developer Tools Local Read-only Acceptance

**What to build:** Run the current mini-program against the local backend in WeChat Developer Tools and prove real login-token propagation plus read-only business flows without changing production.

**Blocked by:** 23, 24

**Status:** resolved-wechat-local-readonly-acceptance

- [x] Build the develop mini-program with only the local Agent endpoint override.
- [x] In WeChat Developer Tools, complete existing login/session initialization and prove the local backend receives the real `Authorization` and `Tenantcode` headers.
- [x] Verify dashboard, Company device list, Company Agent streaming, task/notification reads, and the supported energy/safety observation paths; report projection/download is covered by the independent local backend contract smoke.
- [x] Record screenshots/NDJSON/error evidence and restore the develop endpoint profile after acceptance if required.

## Comments

- Evidence: HBuilderX compile passed; Developer Tools dashboard rendered the real principal, 149-degree energy result, 334 devices/7 kinds, safety record, task and notification states; local network capture showed 200 responses and real auth headers. Company Agent stream completed through Java → Node sidecar → Java tool callback.
- The source profile was restored to the non-local default after the acceptance build; the compiled `dist/dev` artifact remains the local acceptance artifact and is not a customer delivery artifact.
