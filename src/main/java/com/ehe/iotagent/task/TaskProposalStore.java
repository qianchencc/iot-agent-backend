package com.ehe.iotagent.task;

import java.util.Optional;

/** Durable proposal store; no process-local fallback is a valid implementation. */
public interface TaskProposalStore {
    TaskProposalFact save(TaskProposalFact proposal);
    Optional<TaskProposalFact> find(String proposalId);
    Optional<TaskProposalFact> findByIdempotencyKey(String idempotencyKey);
    default Optional<TaskProposalFact> updateIfVersion(TaskProposalFact replacement, long expectedVersion) {
        throw new UnsupportedOperationException("Task proposal CAS store is not configured");
    }
    default java.util.List<TaskProposalFact> retireForConversation(String conversationId, String tenantCode,
            String ownerKey, String source) {
        throw new UnsupportedOperationException("Task proposal store is not configured");
    }

    /** Read-only conversation listing for confirmation context; never mutates state. */
    default java.util.List<TaskProposalFact> listByConversation(String conversationId, String tenantCode,
            String ownerKey, String source) {
        throw new UnsupportedOperationException("Task proposal store is not configured");
    }

    /** Read-only lookup by conversation + turn for audit correlation; never mutates state. */
    default Optional<TaskProposalFact> findByConversationAndTurn(String conversationId, String turnId) {
        throw new UnsupportedOperationException("Task proposal store is not configured");
    }
}
