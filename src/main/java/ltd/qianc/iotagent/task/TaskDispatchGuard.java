package ltd.qianc.iotagent.task;

import java.time.Instant;
import java.util.Set;

/**
 * Durable per-checkpoint device dispatch facts. A restart or repeated scanner
 * cannot send the same checkpoint twice; a later checkpoint evaluates afresh.
 */
public interface TaskDispatchGuard {

    /** Devices already reserved (attempted or yielded) in this checkpoint. */
    Set<String> reservedDevices(String taskId, String phaseId);

    /**
     * Whether the phase is still executing, the task is still dispatchable and
     * the task window has not ended. Checked immediately before each device.
     */
    boolean dispatchable(String taskId, String phaseId, Instant now, Instant windowEnd);

    /**
     * Atomically records the dispatch reservation. Returns false when the task
     * is no longer dispatchable or the device was already reserved by an
     * earlier attempt of this checkpoint; the caller must not dispatch.
     */
    boolean reserve(String taskId, String phaseId, String deviceId, Instant now, Instant windowEnd);

    /**
     * Records a durable yield fact: the checkpoint consciously let the device
     * go (state changed, unreadable or already satisfied) without dispatching.
     * Idempotent within this checkpoint; later checkpoints may re-evaluate.
     */
    void yield(String taskId, String phaseId, String deviceId, Instant at);
}
