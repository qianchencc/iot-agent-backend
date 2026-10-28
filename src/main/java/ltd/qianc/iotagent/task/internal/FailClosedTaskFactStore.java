package ltd.qianc.iotagent.task.internal;

import java.util.Optional;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskFactStore;

/** No process-local fallback is allowed for task business facts. */
public final class FailClosedTaskFactStore implements TaskFactStore {
    private final String reason;
    public FailClosedTaskFactStore(String reason) { this.reason = reason; }
    @Override public TaskFact save(TaskFact task) { throw unavailable(); }
    @Override public Optional<TaskFact> find(String taskId) { throw unavailable(); }
    @Override public Optional<TaskFact> findByIdempotencyKey(String idempotencyKey) { throw unavailable(); }
    @Override public Optional<TaskFact> updateIfVersion(TaskFact replacement, long expectedVersion) { throw unavailable(); }
    private IllegalStateException unavailable() { return new IllegalStateException(reason); }
}
