package com.ehe.iotagent.task.internal;

import java.time.Instant;
import com.ehe.iotagent.task.TaskScheduler;

/** No fake production scheduler or process-local timer is permitted. */
public final class FailClosedTaskScheduler implements TaskScheduler {
    private final String reason;
    public FailClosedTaskScheduler(String reason) { this.reason = reason; }
    @Override public void schedule(String taskId, String phaseId, Instant at) { throw new IllegalStateException(reason); }
    @Override public void cancel(String taskId, String phaseId) { throw new IllegalStateException(reason); }
}
