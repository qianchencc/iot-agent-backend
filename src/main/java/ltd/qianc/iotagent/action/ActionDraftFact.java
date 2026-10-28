package ltd.qianc.iotagent.action;

public record ActionDraftFact(
        String draftId,
        long revision,
        String status,
        String payloadJson,
        String idempotencyKey) {
}
