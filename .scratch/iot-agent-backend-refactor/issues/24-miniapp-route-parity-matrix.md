# 24: Miniapp Route and Payload Parity Matrix

**What to build:** Compare every Agent request used by `mp/apis/agent/index.js` and its page consumers against the local Java backend, including status, error body, source propagation, auth headers, NDJSON, file download, and cache invalidation semantics.

**Blocked by:** 19, 20, 21, 22

**Status:** open-local-frontend-contract-gap

- [ ] Inventory the actual mini-program Agent calls: conversation, dashboard, company devices, tasks/plans/notifications, region, safety, environment, energy, video, reports, action drafts, and cancellation flows.
- [ ] Map each call to a local Java route and classify as verified, implementable locally, fail-closed, or external-only.
- [ ] Add deterministic contract fixtures for the routes consumed by the local pages; no source-presence checks.
- [ ] Do not claim page compatibility while any page-used route is missing or has incompatible payload/error/stream semantics.

## Comments

- Current local backend smoke proves Company device catalog, dashboard, conversation streaming, and selected admin Pi Session operations only.
- Full 66/69 Runtime parity remains a separate unresolved gate in tickets 10/17/21.
