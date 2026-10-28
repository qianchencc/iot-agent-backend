package ltd.qianc.iotagent.task.internal;

import java.util.Optional;
import ltd.qianc.iotagent.task.TaskProposalFact;
import ltd.qianc.iotagent.task.TaskProposalStore;

public final class FailClosedTaskProposalStore implements TaskProposalStore {
    private final String reason;
    public FailClosedTaskProposalStore(String reason) { this.reason = reason; }
    @Override public TaskProposalFact save(TaskProposalFact proposal) { throw unavailable(); }
    @Override public Optional<TaskProposalFact> find(String proposalId) { throw unavailable(); }
    @Override public Optional<TaskProposalFact> findByIdempotencyKey(String idempotencyKey) { throw unavailable(); }
    @Override public Optional<TaskProposalFact> updateIfVersion(TaskProposalFact replacement, long expectedVersion) { throw unavailable(); }
    @Override public void retireForConversation(String conversationId) { throw unavailable(); }
    private IllegalStateException unavailable() { return new IllegalStateException(reason); }
}
