# 25: WeChat Developer Tools Local Read-only Acceptance

**What to build:** Run the current mini-program against the local backend in WeChat Developer Tools and prove real login-token propagation plus read-only business flows without changing production.

**Blocked by:** 23, 24

**Status:** open-wechat-devtools-acceptance

- [ ] Build the develop mini-program with only the local Agent endpoint override.
- [ ] In WeChat Developer Tools, complete existing login/session initialization and prove the local backend receives the real `Authorization` and `Tenantcode` headers.
- [ ] Verify dashboard, Company device list, Company Agent streaming, device state readback, task/notification reads, and report read/download where the route matrix marks them supported.
- [ ] Record screenshots/NDJSON/error evidence and restore the develop endpoint profile after acceptance if required.

## Comments

- This is not production acceptance. Customer gateway/SSO may remain the upstream for login and identity verification.
- Current repository tools can compile and smoke HTTP, but no automated control of the WeChat Developer Tools GUI has been established in this session.
