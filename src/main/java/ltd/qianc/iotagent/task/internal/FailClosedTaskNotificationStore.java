package ltd.qianc.iotagent.task.internal;

import java.time.Instant;
import java.util.List;
import ltd.qianc.iotagent.task.TaskNotificationFact;
import ltd.qianc.iotagent.task.TaskNotificationStore;

public final class FailClosedTaskNotificationStore implements TaskNotificationStore {
    private final String reason;
    public FailClosedTaskNotificationStore(String reason) { this.reason = reason; }
    @Override public TaskNotificationFact record(TaskNotificationFact notification) { throw unavailable(); }
    @Override public List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source, int limit, String before) { throw unavailable(); }
    @Override public TaskNotificationFact markSeen(String tenantCode, String recipientKey, String source, String notificationId, Instant at) { throw unavailable(); }
    @Override public int markAllSeen(String tenantCode, String recipientKey, String source, Instant at) { throw unavailable(); }
    private IllegalStateException unavailable() { return new IllegalStateException(reason); }
}
