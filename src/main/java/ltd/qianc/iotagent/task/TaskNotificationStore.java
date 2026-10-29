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
}
