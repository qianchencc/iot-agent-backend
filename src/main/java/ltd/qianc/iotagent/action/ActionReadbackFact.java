package ltd.qianc.iotagent.action;

/** Durable post-dispatch device readback evidence for one action. */
public record ActionReadbackFact(
        String draftId,
        long draftRevision,
        String actionId,
        String outcome,
        String observedStateJson,
        String observedAt) {
}
