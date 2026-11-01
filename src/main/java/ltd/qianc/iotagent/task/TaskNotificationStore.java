package ltd.qianc.iotagent.task;

import java.time.Instant;
import java.util.List;

/** Durable inbox seam. Implementations enforce (taskId, recipientKey) idempotency. */
public interface TaskNotificationStore {
    TaskNotificationFact record(TaskNotificationFact notification);
    List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source, int limit, String before);
    default long unreadCount(String tenantCode, String recipientKey, String source) {
        return list(tenantCode, recipientKey, source, 100, null).stream().filter(item -> item.readAt() == null).count();
    }
    default TaskNotificationFact markSeen(String tenantCode, String recipientKey, String source,
                                          String notificationId, Instant at) {
        throw new UnsupportedOperationException("Task notification store is not configured");
    }
    default int markAllSeen(String tenantCode, String recipientKey, String source, Instant at) {
        throw new UnsupportedOperationException("Task notification store is not configured");
    }
    /**
     * Refreshes a terminal notification after a report retry succeeds or fails.
     * Execution history is not rewritten; only the notification projection moves
     * from a recoverable failure to its evidence-backed terminal state.
     */
    default TaskNotificationFact refresh(String taskId, String recipientKey, String outcome,
                                         String payloadJson, Instant at) {
        throw new UnsupportedOperationException("Task notification refresh is not configured");
    }
}
