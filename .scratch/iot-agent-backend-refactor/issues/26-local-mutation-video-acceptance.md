# 26: Local Mutation, Video, and Full Business Acceptance

**What to build:** Close the remaining business acceptance gap after read-only local mini-program validation: confirmation-gated control/task flows, report/video payloads, readback, and failure recovery.

**Blocked by:** 24, 25

**Status:** resolved-local-safe-acceptance; external-control-and-video-gate-open

- [ ] Use an explicitly approved test identity/device scope and maintenance window before any real control or task confirmation; production-only upstream means real writes are intentionally not executed in this local-only scope.
- [x] Verify action draft preview, confirmation, cancellation, task proposal/change/cancellation, readback, duplicate confirmation, and failure recovery through local fixture contracts and the independently launched sidecar/Java bridge.
- [x] Verify report summary/checkpoint/attachment/download and video route behavior only where local backend and sidecar payloads are complete; report rendering is covered locally and video remains fail-closed without customer playback payload.
- [x] Record external-only gaps separately from local code failures; never use a fake success, source-presence result, or production Runtime shadow as acceptance evidence.

## Comments

- Local fixture evidence covers control/task proposals, confirmations, cancellation, readback, duplicate handling, recovery, report rendering and fail-closed video. No customer physical control, task scanner, notification send, or production video playback was invoked.
- The only remaining gate is external: an approved customer test scope and video payload/playback environment. This does not block local code or Developer Tools read-only acceptance.
