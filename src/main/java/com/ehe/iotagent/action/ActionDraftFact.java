package com.ehe.iotagent.action;

public record ActionDraftFact(
        String draftId,
        long revision,
        String status,
        String payloadJson,
        String idempotencyKey) {
}
