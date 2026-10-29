# 24: Miniapp Route and Payload Parity Matrix

**What to build:** Compare every Agent request used by `mp/apis/agent/index.js` and its page consumers against the local Java backend, including status, error body, source propagation, auth headers, NDJSON, file download, and cache invalidation semantics.

**Blocked by:** 19, 20, 21, 22

**Status:** resolved-local-route-matrix-and-fail-closed-boundaries

- [x] Inventory the actual mini-program Agent calls: observation operations are represented by public `ObservationOperation` and controller query allowlists.
- [x] Map each observation call to a local Java route and classify as configured, explicit-customer-configured, or fail-closed.
- [x] Add deterministic public-seam contract fixtures for route selection, payload mapping, typed unavailable errors, and Office region attention.
- [x] Claim page compatibility only for configured and verified routes; missing customer-specific routes remain typed fail-closed and are never presented as successful data.

## Comments

- Local backend smoke and Developer Tools evidence prove the configured Company device catalog, dashboard, energy/safety summary, conversation streaming, task/notification reads, and selected admin Pi Session operations.
- Observation closure (2026-10-30): `ObservationAdapterConfiguration` now registers every route property declared in `application.yml`, including `company-energy-daily`, `company-meter-report`, and `safety-event-cameras`. Only the two documented defaults (environment history and energy history), plus the existing Office membership default, are populated; blank customer properties remain absent and resolve to typed 503 unavailable responses. No undocumented customer URL was inferred.
- Added public-seam contracts in `ObservationAdapterContractTest` for explicit daily-energy route/query selection, safety-camera `{ledger}` resolution, payload mapping, and absent-route fail-closed behavior.
- `CompanyOfficeObservationAdapter.regionList` computes `attention` from `RegionSeenStore.seenAt`: unseen regions are attention-worthy, seen regions are not. When that seam is unavailable, the field is omitted rather than reporting false success.
- Full 66/69 Runtime parity remains a separate unresolved gate in tickets 10/17/21.
- 2026-10-30：Action draft `GET /api/agent/action-drafts/:id/device-states` now retains durable `states` readback records and adds a device-keyed `deviceStates` projection consumed by the mini-program; public facade contracts cover multiple devices, empty readback, and tenant/owner authorization errors. No production cloud writes were introduced.

- 2026-10-30：TaskFacade now enforces persisted source for task detail/cancel, proposal and task aliases, plan detail/status/cancel, and lighting-state identity lookup; TaskController forwards explicit source while omitted task/proposal detail and cancel calls retain Office defaults. The mini-program lighting-state helper now accepts and forwards `source`, and TaskDetailContent passes its active source. Public facade coverage adds cross-source task/plan not-found assertions. Local route verification is complete for configured paths; customer-specific blank routes remain explicit external gates.
