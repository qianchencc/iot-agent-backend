package com.ehe.iotagent.task;

import java.util.Optional;

public interface TaskFactStore {

    TaskFact save(TaskFact task);

    Optional<TaskFact> find(String taskId);

    Optional<TaskFact> findByIdempotencyKey(String idempotencyKey);

    /**
     * Atomic version compare-and-set. Implementations backed by durable storage
     * MUST perform the version predicate in the write itself.
     */
    default Optional<TaskFact> updateIfVersion(TaskFact replacement, long expectedVersion) {
        throw new UnsupportedOperationException("Task fact CAS store is not configured");
    }

    default Optional<TaskFact> findBySource(String sourceId) {
        return Optional.empty();
    }
}
