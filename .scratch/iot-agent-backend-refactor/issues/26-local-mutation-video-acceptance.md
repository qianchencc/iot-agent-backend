# 26: Local Mutation, Video, and Full Business Acceptance

**What to build:** Close the remaining business acceptance gap after read-only local mini-program validation: confirmation-gated control/task flows, report/video payloads, readback, and failure recovery.

**Blocked by:** 24, 25

**Status:** open-external-safety-gate

- [ ] Use an explicitly approved test identity/device scope and maintenance window before any real control or task confirmation; production-only upstream means this cannot be silently executed.
- [ ] Verify action draft preview, confirmation, cancellation, task proposal/change/cancellation, readback, duplicate confirmation, and failure recovery from the mini-program.
- [ ] Verify report summary/checkpoint/attachment/download and video playback only where local backend and sidecar payloads are complete.
- [ ] Record external-only gaps separately from local code failures; never use a fake success, source-presence result, or production Runtime shadow as acceptance evidence.

## Comments

- Current local evidence intentionally stops before customer physical control and real video playback.
- This ticket requires an approved safe acceptance scope, not a code question. Until then, read-only acceptance is the maximum safe business test.
