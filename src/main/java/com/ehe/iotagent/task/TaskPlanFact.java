package com.ehe.iotagent.task;

/** Durable recurring-plan fact. Future occurrences are projections, not separate tasks. */
public record TaskPlanFact(
        String planId,
        long version,
        String status,
        String payloadJson,
        String idempotencyKey) {
    public TaskPlanFact {
        if (planId == null || planId.isBlank()) throw new IllegalArgumentException("planId is required");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        if (payloadJson == null || payloadJson.isBlank()) throw new IllegalArgumentException("payloadJson is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
    }
}
