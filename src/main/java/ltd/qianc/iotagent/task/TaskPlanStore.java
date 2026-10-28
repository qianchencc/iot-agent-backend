package ltd.qianc.iotagent.task;

import java.util.Optional;

/** Durable recurring-plan store. It owns occurrence/date uniqueness and plan CAS. */
public interface TaskPlanStore {
    TaskPlanFact save(TaskPlanFact plan);
    Optional<TaskPlanFact> find(String planId);
    Optional<TaskPlanFact> findByIdempotencyKey(String idempotencyKey);
    default Optional<TaskPlanFact> updateIfVersion(TaskPlanFact replacement, long expectedVersion) {
        throw new UnsupportedOperationException("Task plan CAS store is not configured");
    }
    default boolean claimOccurrence(String planId, String occurrenceDate, long expectedVersion) {
        throw new UnsupportedOperationException("Task occurrence CAS store is not configured");
    }
    default java.util.List<TaskPlanFact> list(String tenantCode, String ownerKey, String source) {
        throw new UnsupportedOperationException("Task plan list store is not configured");
    }
}
