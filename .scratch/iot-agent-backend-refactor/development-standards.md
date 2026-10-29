# Phase 2 Development Standards

## Source of truth

- Runtime OpenAPI and NDJSON schema define the external contract.
- Runtime module `AGENTS.md`, source behavior, and regression tests define business semantics.
- Existing Java package-root interfaces define module seams; implementations remain under `internal`.
- Runtime behavior must be ported, not approximated with empty success, generic upstream passthrough, or newly invented client semantics.

## Module and API discipline

- Public cross-module dependencies import only package-root entry points.
- Controllers are HTTP adapters only; authorization, CAS, persistence, and composition belong behind module-root services.
- Java/Node Tool Bridge is private and authenticated; it never becomes a public mini-program API.
- Preserve `Authorization`, `Tenantcode`, `source`, status codes, error body, NDJSON sequence, idempotency, and terminal state semantics.
- Additive internal/tool routes are allowed only when they do not replace or alter Runtime routes.

## Persistence and state

- MySQL is the authority for business facts; Redis is rebuildable coordination only.
- New MySQL tables follow repository/customer rules: no foreign keys/cascades, required identity/time fields, explicit unique/CAS indexes, decimal for decimals, and string transport for values beyond JavaScript safe integer range.
- Every state transition names its expected version, legal predecessor state, terminal replay behavior, and recovery behavior.
- No production-grade in-memory fallback for facts.
- Report artifacts, task changes, cancellation proposals, seen markers, and notifications are durable facts.

## Upstream and security

- Customer gateway requests use the verified principal headers; never accept client/model device permissions or control references.
- Use Runtime-derived endpoint and envelope rules. Unknown or incomplete scope, binding, pagination, or authorization fails closed.
- Never log or return bearer tokens, API keys, control references, hub IDs, internal authorization keys, passwords, or raw upstream credentials.
- Read-only smoke may use the current local ego-lite identity; no production control, task scanner, notification, configuration write, or shadow execution.
- Video URLs are `no-store`, short-lived, authorization-bound, and never persisted as business facts.

## Testing

- Test at the highest observable seam: HTTP/NDJSON first, module-root contracts second.
- Port Runtime production-protocol fixtures into in-process Java/WebClient fixtures; tests never contact a real customer device.
- Cover normal, empty, partial, unauthorized, out-of-scope, malformed, timeout, duplicate, stale version, expiry, restart, and recovery cases.
- Do not test SQL strings, framework wiring, mock echoes, source text, non-empty defaults, or bare no-throw behavior.
- Per slice: targeted test/contract test; after all slices: `mvn -q test`, route/NDJSON gate, local MySQL/Redis health, and the affected business smoke.

## Git and delivery

- Before broad changes, record branch/status/log/diff baseline.
- One semantic business slice per commit; tests/docs/build fixes belong with that slice.
- Before commit: `git diff --check`, inspect changed paths, and verify no secrets, generated output, temporary logs, or production artifacts entered the commit.
- Never run `git push --force`, `git reset --hard`, `git clean -fd`, or production deployment in this phase.
- Production cloud remains untouched until an explicitly approved cutover phase.
