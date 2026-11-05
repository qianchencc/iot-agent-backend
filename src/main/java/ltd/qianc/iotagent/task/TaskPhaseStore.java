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
     * Recovers executing phases and returns their new terminal rows so the scanner
     * can also close the parent task and emit its durable notification.
     */
    default List<TaskPhase> recoverInterrupted(Instant at) {
        recoverExecuting(at);
        return List.of();
    }

    /**
     * Marks one still-pending phase as missed without claiming or dispatching
     * it (Runtime missPhase). Used for one-off tasks whose checkpoint left the
     * ten-minute catch-up window.
     */
    default boolean miss(String phaseId, Instant at, String reason) {
        return false;
    }

    /**
     * Marks every already-elapsed pending phase of recurring-plan occurrences
     * as missed on recovery (Runtime recoverInterrupted), so a restart never
     * dispatches a checkpoint whose scheduled moment passed while the service
     * was down. Returns the missed terminal rows for task reconciliation.
     */
    default List<TaskPhase> missElapsed(Instant at) {
        return List.of();
    }

    /**
     * Cancels the still-pending later phases of one task after an execution
     * invalidation (authorization/binding/identity), Runtime
     * disablePlanForPhase. Returns how many phases were cancelled.
     */
    default int cancelPending(String taskId, String reason, Instant at) {
        return 0;
    }

    /**
     * Active tasks whose phases are all terminal and whose optional interval
     * has ended. Reconciliation also repairs a process stopped after the last
     * phase committed but before the parent task and notification committed.
     */
    default List<String> readyToFinalize(Instant now, int limit) {
        return List.of();
    }

    /**
     * Scheduled interval tasks whose window already started (Runtime
     * advanceIntervals: interval_start <= now flips the task to executing).
     * The scanner promotes each to EXECUTING through the task fact CAS.
     */
    default List<String> startedIntervals(Instant now, int limit) {
        return List.of();
    }


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

    /**
     * Returns terminal phase facts with their preserved definition and actual
     * result. The scanner uses this for a whole-task report without re-executing
     * any checkpoint.
     */
    default List<PhaseResult> terminalPhases(String taskId) { return List.of(); }

    /**
     * Returns every phase row of the task in scheduled order regardless of
     * status (Runtime detail projection): pending and executing phases keep
     * their preserved definition with an empty result, so consumers can show
     * future checkpoints while the task runs.
     */
    default List<PhaseResult> allPhases(String taskId) { return List.of(); }

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

    /**
     * Original phase definition and the result produced by execution. Definitions
     * are never overwritten by terminal result JSON.
     */
    record PhaseResult(String taskId, String phaseId, Instant dueAt, String status,
                       String definitionJson, String resultJson, Instant startedAt, Instant endedAt,
                       String failureReason) {
        public PhaseResult {
            if (taskId == null || taskId.isBlank() || phaseId == null || phaseId.isBlank()
                    || dueAt == null || status == null || status.isBlank()) {
                throw new IllegalArgumentException("phase result is incomplete");
            }
            definitionJson = definitionJson == null || definitionJson.isBlank() ? "{}" : definitionJson;
            resultJson = resultJson == null || resultJson.isBlank() ? "{}" : resultJson;
        }

        /** Pre-failureReason call sites keep compiling; an absent reason stays null. */
        public PhaseResult(String taskId, String phaseId, Instant dueAt, String status,
                           String definitionJson, String resultJson, Instant startedAt, Instant endedAt) {
            this(taskId, phaseId, dueAt, status, definitionJson, resultJson, startedAt, endedAt, null);
        }
    }
}
