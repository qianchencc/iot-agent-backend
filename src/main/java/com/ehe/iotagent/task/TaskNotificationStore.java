package com.ehe.iotagent.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Durable inbox seam. Implementations enforce (taskId, recipientKey) idempotency and
 * Runtime cursor semantics: pages are ordered by (endedAt, row) descending and the
 * cursor is the notification id, so equal timestamps keep a stable order.
 */
public interface TaskNotificationStore {
    TaskNotificationFact record(TaskNotificationFact notification);

    /**
     * One page strictly after the {@code beforeId} cursor (null reads the newest
     * page). Returns at most {@code limit} rows ordered by (endedAt, row) desc.
     */
    List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source, int limit, String beforeId);

    /** Every unread fact of the recipient, newest first; callers apply task visibility. */
    default List<TaskNotificationFact> unread(String tenantCode, String recipientKey, String source) {
        return list(tenantCode, recipientKey, source, 100, null).stream()
                .filter(item -> item.readAt() == null).toList();
    }

    Optional<TaskNotificationFact> find(String notificationId);

    default TaskNotificationFact markSeen(String tenantCode, String recipientKey, String source,
                                          String notificationId, Instant at) {
        throw new UnsupportedOperationException("Task notification store is not configured");
    }

    /** Marks exactly the given visible notifications; invisible rows never change. */
    default int markAllSeen(String tenantCode, String recipientKey, String source,
                            List<String> notificationIds, Instant at) {
        throw new UnsupportedOperationException("Task notification store is not configured");
    }

}
