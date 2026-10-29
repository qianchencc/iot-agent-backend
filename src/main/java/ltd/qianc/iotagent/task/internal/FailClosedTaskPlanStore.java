package ltd.qianc.iotagent.task.internal;

import java.util.Optional;
import ltd.qianc.iotagent.task.TaskPlanFact;
import ltd.qianc.iotagent.task.TaskPlanStore;

public final class FailClosedTaskPlanStore implements TaskPlanStore {
    private final String reason;
    public FailClosedTaskPlanStore(String reason) { this.reason = reason; }
    @Override public TaskPlanFact save(TaskPlanFact plan) { throw unavailable(); }
    @Override public Optional<TaskPlanFact> find(String planId) { throw unavailable(); }
    @Override public Optional<TaskPlanFact> findByIdempotencyKey(String idempotencyKey) { throw unavailable(); }
    @Override public Optional<TaskPlanFact> updateIfVersion(TaskPlanFact replacement, long expectedVersion) { throw unavailable(); }
    @Override public Optional<TaskPlanFact> cancelAllIfVersion(String planId, long expectedVersion, java.time.Instant now) { throw unavailable(); }
    @Override public boolean claimOccurrence(String planId, String occurrenceDate, long expectedVersion) { throw unavailable(); }
    private IllegalStateException unavailable() { return new IllegalStateException(reason); }
}
