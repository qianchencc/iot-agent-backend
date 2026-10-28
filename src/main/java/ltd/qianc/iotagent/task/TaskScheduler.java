package ltd.qianc.iotagent.task;

import java.time.Instant;

/** Scheduling coordination seam. A production implementation is supplied by deployment infrastructure. */
public interface TaskScheduler {
    void schedule(String taskId, String phaseId, Instant at);
    void cancel(String taskId, String phaseId);
}
