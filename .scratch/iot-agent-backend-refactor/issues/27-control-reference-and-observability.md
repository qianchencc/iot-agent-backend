# 27: Control Reference Reliability and Langfuse Diagnostics

**What to build:** Ensure the independent sidecar preserves Runtime control-tool argument schemas and candidate-set/device references across model → Node → Java, so an empty or invented device reference cannot reach state/control code as a business request. Add Runtime-compatible Langfuse tracing for model turns, tool calls, errors, and the control preflight path.

**Blocked by:** 07: Action Draft、控制安全与 Readback；18: Independent Node Pi Agent Sidecar；22: Sidecar Event、Tool、Process Parity

**Status:** in-progress-reproduction-and-fix

- [x] Reproduce the user-visible `INVALID_DEVICE_REFERENCE` with the exact conversation path and capture the empty `read_device_states` argument shape.
- [x] Sidecar tool schemas require candidate-set/device references for discovery, state, capability, and action-draft tools.
- [x] Java bridge accepts Runtime `candidateSets` state requests and rejects empty/duplicate references before the gateway.
- [x] Flat Runtime control action fields are normalized into Java Action Draft `{kind, deviceId, command}` records; no direct execution occurs at proposal time.
- [x] Empty Company smart-control catalogs remain explicitly unavailable; no fake success is generated.
- [x] Sidecar uses the Runtime-compatible Langfuse v5 OTLP stack and imports the encrypted Runtime configuration into the ignored local `.env` only.
- [x] Sidecar continues multi-step tool turns, exposes a configurable Java-tool timeout, and converts non-HTTP-error/timeout responses into structured unavailable tool results instead of terminating the whole run.
- [ ] Re-run the original mini-program/Agent flow and prove a valid candidate → state → proposal/clarification sequence for an actually authorized controllable device.
- [ ] Customer production control remains excluded; confirmation and physical execution require an approved test scope.

## Evidence

- Before fix: the exact local stream ended `message.completed={"code":"INVALID_DEVICE_REFERENCE","message":"设备引用无效"}` after `read_device_states` was called with `{}`.
- Customer Company smart-family probes returned HTTP `204`; local Company catalog contains only environment, energy, and safety observations. It must not claim Company lighting control.
- Runtime encrypted config was decrypted locally using `var/config-secret-key`; only `LANGFUSE_BASE_URL`, public key, and secret key were written to the ignored backend `.env`. No key was printed.
- Langfuse smoke trace was accepted by `https://langfuse2.qianc.ltd`; recent observations include `agent-run` and `model-turn`.
- Local Office read-only path was verified end-to-end: non-empty candidate set → real device IDs → state readback; the main lighting device returned `false` (off) and association states were `null`.
- The subsequent proposal path reached the real `propose_action_draft` call with normalized Runtime-style flat fields but did not produce a completed proposal. The local Java tool call returned no usable response before the run failed; proposal is not marked complete.
- The upstream identity endpoint itself returned HTTP 200 for the configured mini-program token when probed directly. A later Java-authenticated conversation returned `ACCOUNT_IDENTITY_UNAVAILABLE`; local service-level identity verification remains an environment gate and was not bypassed in the acceptance claim.
- The local model path also required `NODE_USE_ENV_PROXY=1`; without it Node's native `fetch` could not reach the configured model proxy, producing empty model messages or `Agent Engine 暂不可用`. The sidecar now reads `AGENT_THINKING_LEVEL` and matches Runtime reasoning/max-token compatibility; the local service is started with the proxy switch.
- Java gateway and identity clients now accept an optional `IOT_GATEWAY_PROXY` for this workstation-only network path. Production remains unchanged when the variable is empty. With the proxy enabled, identity verification returned HTTP 503 before the final proxy-client adjustment; candidate scope loading still needs one final live run.
