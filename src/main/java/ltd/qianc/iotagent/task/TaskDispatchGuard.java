package ltd.qianc.iotagent.task;

import java.time.Instant;
import java.util.Set;

/**
 * Durable per-task device dispatch facts. Conditional AC checkpoints reserve a
 * device atomically immediately before each single dispatch, so a cancel, an
 * operator change or a restart can never make the same task adjust the same
 * device twice. Reservations survive restarts and later checkpoints of the
 * same task; a new task occurrence starts with a fresh set.
 */
public interface TaskDispatchGuard {

    /** Devices this task has already reserved (attempted dispatch or yielded). */
    Set<String> reservedDevices(String taskId);

    /**
     * Whether the phase is still executing, the task is still dispatchable and
     * the task window has not ended. Checked immediately before each device.
     */
    boolean dispatchable(String taskId, String phaseId, Instant now, Instant windowEnd);

    /**
     * Atomically records the dispatch reservation. Returns false when the task
     * is no longer dispatchable or the device was already reserved by an
     * earlier attempt, checkpoint or process; the caller must not dispatch.
     */
    boolean reserve(String taskId, String phaseId, String deviceId, Instant now, Instant windowEnd);

    /**
     * Records a durable yield fact: the checkpoint consciously let the device
     * go (state changed, unreadable or already satisfied) without dispatching.
     * Idempotent; later checkpoints of the same task will not re-attempt.
     */
    void yield(String taskId, String phaseId, String deviceId, Instant at);
}
