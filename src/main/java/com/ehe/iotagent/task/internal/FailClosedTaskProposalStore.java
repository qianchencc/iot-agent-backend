package com.ehe.iotagent.task.internal;

import java.util.Optional;
import com.ehe.iotagent.task.TaskProposalFact;
import com.ehe.iotagent.task.TaskProposalStore;

public final class FailClosedTaskProposalStore implements TaskProposalStore {
    private final String reason;
    public FailClosedTaskProposalStore(String reason) { this.reason = reason; }
    @Override public TaskProposalFact save(TaskProposalFact proposal) { throw unavailable(); }
    @Override public Optional<TaskProposalFact> find(String proposalId) { throw unavailable(); }
    @Override public Optional<TaskProposalFact> findByIdempotencyKey(String idempotencyKey) { throw unavailable(); }
    @Override public Optional<TaskProposalFact> updateIfVersion(TaskProposalFact replacement, long expectedVersion) { throw unavailable(); }
    @Override public java.util.List<TaskProposalFact> retireForConversation(String conversationId, String tenantCode,
            String ownerKey, String source) { throw unavailable(); }
    @Override public Optional<TaskProposalFact> findByConversationAndTurn(String conversationId, String turnId) { throw unavailable(); }
    private IllegalStateException unavailable() { return new IllegalStateException(reason); }
}
