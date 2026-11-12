package com.ehe.iotagent.task;

/** Durable, user-confirmable task proposal. Secrets are never part of payloadJson. */
public record TaskProposalFact(
        String proposalId,
        long version,
        String status,
        String payloadJson,
        String idempotencyKey,
        String kind,
        String targetJson,
        String beforeJson,
        String afterJson,
        String operation,
        String tenantCode,
        String ownerKey,
        String source,
        java.time.Instant expiresAt) {
    public TaskProposalFact(String proposalId, long version, String status, String payloadJson, String idempotencyKey) {
        this(proposalId, version, status, payloadJson, idempotencyKey, "TASK", "{}", "{}", payloadJson,
                "create", "", "", "office", null);
    }
    public TaskProposalFact {
        require(proposalId, "proposalId");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        require(status, "status");
        require(payloadJson, "payloadJson");
        require(idempotencyKey, "idempotencyKey");
        require(kind, "kind");
        require(targetJson, "targetJson");
        require(beforeJson, "beforeJson");
        require(afterJson, "afterJson");
        require(operation, "operation");
        require(source, "source");
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
