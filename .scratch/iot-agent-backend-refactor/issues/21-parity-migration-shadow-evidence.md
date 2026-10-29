# 21: Behavioral Parity, Migration, Shadow, and Recovery Evidence

**What to build:** Replace source-presence/static checks with local behavioral evidence for the complete Java backend replacement while keeping production Runtime untouched and authoritative.

**Blocked by:** 04, 06, 08, 09, 14, 15, 16, 18, 19, 20

**Status:** partial-local-behavior-evidence-with-migration-shadow-planner

- [ ] A local fixture/oracle harness compares all 66 paths / 69 operations for request headers, source rules, status, error body, payload, and NDJSON sequence/terminal semantics.
- [x] A local MockMvc fixture proves representative conversation path/header/source/status/error/NDJSON sequence/terminal behavior without Runtime or production access. See `docs/migration/ticket-21-local-evidence.md`.
- [x] `tools/migration-ticket-21.mjs` provides read-only JSONL dry-run validation with identity/version/sequence checks, stop-on-error, optional idempotent retry API, append-only recovery planning, and source non-overwrite output.
- [x] The same local module provides normalized shadow comparison with an explicit denylist and deterministic owner/restart trace output; it does not dispatch live requests or authorize writes.
- [x] Local evidence runbook records the proven slice and residual gaps; no production URLs, tokens, or secrets are included.
- [ ] Process-level execution tracing proves one control/scanner/notification owner and no double-run during local shadow/restart scenarios; current trace is deterministic planning evidence only.
- [ ] Local evidence bundle covers health, config redaction, Pi Session backup/restore, migration, parity, shadow, logs, rollback, and stop conditions.
- [ ] The runbook identifies production permissions, deployment volume, supervisor handoff, approval, monitoring, cutover, and rollback steps without executing them.

## Comments

- `RuntimeContractGateTest` now provides a representative local MockMvc behavior fixture; it is not a complete 66/69-operation parity gate and must not be presented as one.
- This ticket produces evidence and local fixtures only. It MUST NOT call customer control, scanner, notification, or production cutover endpoints.
- `tools/migration-ticket-21.mjs` is a local planning/normalization aid, not a live shadow dispatcher, database importer, or process supervisor.
