package ltd.qianc.iotagent.action;

/** Durable gateway receipt evidence for one action in a confirmed draft. */
public record ActionReceiptFact(
        String draftId,
        long draftRevision,
        String actionId,
        ActionReceiptOutcomeEnum outcome,
        String payloadJson) {
}
