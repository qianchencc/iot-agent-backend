package com.ehe.iotagent.task;

import java.time.Instant;
import java.time.LocalDate;
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
    /**
     * Applies one future occurrence override or skip while incrementing the parent
     * plan version in the same CAS transaction. A null payload means skip.
     */
    default Optional<TaskPlanOccurrence> overrideOccurrenceIfVersion(String planId, LocalDate occurrenceDate,
                                                                       long expectedPlanVersion, String payloadJson,
                                                                       Instant now) {
        throw new UnsupportedOperationException("Task occurrence override store is not configured");
    }
    default Optional<TaskPlanOccurrence> findOccurrence(String planId, LocalDate occurrenceDate) {
        throw new UnsupportedOperationException("Task occurrence store is not configured");
    }
    /** All occurrence rows of one plan (overrides, skips, claims, materialized). */
    default java.util.List<TaskPlanOccurrence> occurrences(String planId) {
        throw new UnsupportedOperationException("Task occurrence list store is not configured");
    }
    /**
     * Cancels all future materialized work for a plan while retaining executing,
     * terminal, and unknown history. Implementations MUST apply the plan CAS and
     * pending-phase cancellation in one durable transaction.
     */
    default Optional<TaskPlanFact> cancelAllIfVersion(String planId, long expectedVersion, Instant now) {
        throw new UnsupportedOperationException("Task plan cancellation store is not configured");
    }
    default java.util.List<TaskPlanFact> list(String tenantCode, String ownerKey, String source) {
        throw new UnsupportedOperationException("Task plan list store is not configured");
    }
    /**
     * Tenant-wide plan listing (Runtime plans are tenant-scoped); visibility and
     * manageability trimming belongs to the read model, not the store.
     */
    default java.util.List<TaskPlanFact> listByTenant(String tenantCode, String source) {
        throw new UnsupportedOperationException("Task plan tenant list store is not configured");
    }
    /**
     * Runtime plans.invalidate / store.disablePlanForPhase: pauses an enabled
     * plan with an automatic-invalidation reason in one CAS transaction. Does
     * not touch occurrence rows or phase history; cancelling the still-pending
     * phases of the invalid occurrence stays with the phase store. Returns the
     * current fact when the plan exists but is not ENABLED, empty when missing.
     */
    default Optional<TaskPlanFact> pauseForInvalidation(String planId, String reason, Instant now) {
        throw new UnsupportedOperationException("Task plan invalidation store is not configured");
    }
    /** Durable pause annotation (Runtime pause_reason), null when none. */
    default Optional<String> pauseReason(String planId) {
        throw new UnsupportedOperationException("Task plan pause reason store is not configured");
    }
    /** Recurrence pointer columns; the plan payload itself carries no nextAt/nextDate. */
    default Optional<PlanPointer> pointer(String planId) {
        throw new UnsupportedOperationException("Task plan pointer store is not configured");
    }
    /** Latest materialized occurrence task, used by the plan read model. */
    default java.util.Optional<String> latestTaskId(String planId) {
        return java.util.Optional.empty();
    }

    /** Next-occurrence pointer projected from the durable plan row. */
    record PlanPointer(Instant nextAt, LocalDate nextDate, Instant updatedAt) {
        public PlanPointer {
            if (updatedAt == null) throw new IllegalArgumentException("updatedAt is required");
        }
    }
}
