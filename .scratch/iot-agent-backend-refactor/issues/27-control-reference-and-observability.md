# 27: Control Reference Reliability and Langfuse Diagnostics

**What to build:** Ensure the independent sidecar preserves Runtime control-tool argument schemas and candidate-set/device references across model → Node → Java, so an empty or invented device reference cannot reach state/control code as a business request. Add Runtime-compatible Langfuse tracing for model turns, tool calls, errors, and the control preflight path.

**Blocked by:** 07: Action Draft、控制安全与 Readback；18: Independent Node Pi Agent Sidecar；22: Sidecar Event、Tool、Process Parity

**Status:** resolved-local-control-reference-and-observability

- [x] Reproduce the user-visible `INVALID_DEVICE_REFERENCE` with the exact conversation path and capture the empty `read_device_states` argument shape.
- [x] Sidecar tool schemas require candidate-set/device references for discovery, state, capability, and action-draft tools.
- [x] Java bridge accepts Runtime `candidateSets` state requests and rejects empty/duplicate references before the gateway.
- [x] Flat Runtime control action fields are normalized into Java Action Draft `{kind, deviceId, command}` records; no direct execution occurs at proposal time.
- [x] Empty Company smart-control catalogs remain explicitly unavailable; no fake success is generated.
- [x] Sidecar uses the Runtime-compatible Langfuse v5 OTLP stack and imports the encrypted Runtime configuration into the ignored local `.env` only.
- [x] Sidecar continues multi-step tool turns, exposes a configurable Java-tool timeout, and converts non-HTTP-error/timeout responses into structured unavailable tool results instead of terminating the whole run.
- [x] Re-run the original mini-program/Agent flow and prove a valid candidate → state → proposal/clarification sequence for an actually authorized controllable device.
- [x] Customer production control remains excluded; confirmation and physical execution require an approved test scope.

## Evidence

- Before fix: the exact local stream ended `message.completed={"code":"INVALID_DEVICE_REFERENCE","message":"设备引用无效"}` after `read_device_states` was called with `{}`.
- Customer Company smart-family probes returned HTTP `204`; local Company catalog contains only environment, energy, and safety observations. It must not claim Company lighting control.
- Runtime encrypted config was decrypted locally using `var/config-secret-key`; only `LANGFUSE_BASE_URL`, public key, and secret key were written to the ignored backend `.env`. No key was printed.
- Langfuse smoke trace was accepted by `https://langfuse2.qianc.ltd`; recent observations include `agent-run` and `model-turn`.
- Local Office read-only path was verified end-to-end: non-empty candidate set → real device IDs → state readback; the main lighting device returned `false` (off) and association states were `null`.
- The first proposal attempt exposed a conversation-event sequence collision: Action Draft emitted an event using the same per-turn sequence allocator as the Java bridge. The fix serializes sequence allocation on the shared event store and has a regression test.
- Final local Office Agent path completed `action.proposed`, `tool.completed`, `message.completed`, and `run.awaiting_confirmation`; the returned draft was `PENDING_CONFIRMATION` for the real authorized lighting device. No physical control was executed.
- Final local Company Agent path completed environment history and energy summary read-only tools after sidecar source injection and source-aware energy operation mapping.
- The original `INVALID_DEVICE_REFERENCE` path is no longer reproducible: action tools require explicit `kind`, preserve candidate/device references, and Java normalizes lighting actions to `{kind:"lighting", command:{power:"on|off"}}`.
