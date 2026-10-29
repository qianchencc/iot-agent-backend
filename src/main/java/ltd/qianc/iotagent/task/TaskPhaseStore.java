package ltd.qianc.iotagent.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Phase state machine storage. Claiming is a single durable CAS operation. */
public interface TaskPhaseStore {
    List<TaskPhase> due(Instant now, int limit);
    boolean claim(String phaseId, long expectedVersion, Instant claimedAt);
    Optional<TaskPhase> find(String phaseId);
    boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt);
    int recoverExecuting(Instant at);

    /**
     * Returns whether no phase for the task is still pending or executing. Durable
     * stores override this to prevent early terminal notifications.
     */
    default boolean allTerminal(String taskId) { return true; }

    /**
     * Returns all durable phase outcomes for task-level aggregation. Implementations
     * that do not expose durable phase rows may return an empty list.
     */
    default List<String> outcomes(String taskId) { return List.of(); }

    record TaskPhase(String taskId, String phaseId, long version, String source,
                     String payloadJson, Instant dueAt, String status) {
        public TaskPhase {
            if (taskId == null || taskId.isBlank() || phaseId == null || phaseId.isBlank()) {
                throw new IllegalArgumentException("task and phase are required");
            }
            if (version < 1 || source == null || source.isBlank() || payloadJson == null || payloadJson.isBlank()
                    || dueAt == null || status == null || status.isBlank()) {
                throw new IllegalArgumentException("phase is incomplete");
            }
        }
    }
}
