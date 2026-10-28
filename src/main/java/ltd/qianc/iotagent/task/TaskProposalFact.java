package ltd.qianc.iotagent.task;

import java.util.Objects;

/** Durable, user-confirmable task proposal. Secrets are never part of payloadJson. */
public record TaskProposalFact(
        String proposalId,
        long version,
        String status,
        String payloadJson,
        String idempotencyKey) {
    public TaskProposalFact {
        require(proposalId, "proposalId");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        require(status, "status");
        require(payloadJson, "payloadJson");
        require(idempotencyKey, "idempotencyKey");
    }
    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
