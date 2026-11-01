package ltd.qianc.iotagent.task;

import java.util.Optional;

/**
 * Protected execution identity for a confirmed task or recurring plan. The
 * authorization value is encrypted at rest and MUST never be projected through
 * task payloads, HTTP views, notifications, reports or model-facing events.
 */
public interface TaskExecutionIdentityStore {

    void saveForTask(String taskId, TaskExecutionIdentity identity);

    /** Saves the creator identity that a future occurrence materialization copies. */
    void saveForPlan(String planId, TaskExecutionIdentity identity);

    Optional<TaskExecutionIdentity> loadForTask(String taskId);

    /** Idempotently copies a plan identity onto a newly materialized occurrence task. */
    default void copyPlanIdentityToTask(String planId, String taskId) { }

    default void deleteForTask(String taskId) { }

    record TaskExecutionIdentity(String tenantCode, String ownerKey, String authorization) {
        public TaskExecutionIdentity {
            require(tenantCode, "tenantCode");
            require(ownerKey, "ownerKey");
            require(authorization, "authorization");
        }

        private static void require(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        }

        @Override
        public String toString() {
            return "TaskExecutionIdentity[tenantCode=" + tenantCode + ", ownerKey=" + ownerKey + ", authorization=redacted]";
        }
    }
}
