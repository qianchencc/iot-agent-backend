package ltd.qianc.iotagent.task;

import java.util.List;

/**
 * Durable retry boundary for terminal tasks that requested a server-evidenced
 * report but have no persisted report yet. Recovery MUST NOT dispatch any task
 * phase again.
 */
public interface TaskReportRetryStore {
    List<TaskFact> pending(int limit);

    /** Records a failed report attempt so repeated scans can be bounded later. */
    default void failed(String taskId, String error) { }

    default void succeeded(String taskId) { }
}
