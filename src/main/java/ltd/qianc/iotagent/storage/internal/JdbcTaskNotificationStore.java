package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.task.TaskNotificationFact;
import ltd.qianc.iotagent.task.TaskNotificationStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskNotificationStore extends AbstractJdbcFactSupport implements TaskNotificationStore {
    private static final String COLUMNS = "notification_id,task_id,tenant_code,recipient_key,source,outcome,ended_at,read_at,payload_json";

    public JdbcTaskNotificationStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        super(jdbc, transactions, mapper);
    }

    @Override
    public TaskNotificationFact record(TaskNotificationFact notification) {
        try {
            jdbc.update("INSERT INTO task_notifications(notification_id,task_id,tenant_code,recipient_key,source,outcome,ended_at,read_at,payload_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    notification.notificationId(), notification.taskId(), notification.tenantCode(), notification.recipientKey(), notification.source(), notification.outcome(), time(notification.endedAt()), notification.readAt() == null ? null : time(notification.readAt()), notification.payloadJson(), time(Instant.now()), time(Instant.now()));
            return notification;
        } catch (DuplicateKeyException duplicate) {
            return jdbc.query("SELECT " + COLUMNS + " FROM task_notifications WHERE task_id=? AND recipient_key=?",
                    this::map, notification.taskId(), notification.recipientKey()).stream().findFirst().orElseThrow(() -> duplicate);
        }
    }

    @Override
    public List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source, int limit, String beforeId) {
        if (limit < 1) return List.of();
        if (beforeId == null || beforeId.isBlank()) {
            return jdbc.query("SELECT " + COLUMNS + " FROM task_notifications WHERE tenant_code=? AND recipient_key=? AND source=? "
                            + "ORDER BY ended_at DESC,id DESC LIMIT ?",
                    this::map, tenantCode, recipientKey, source, limit);
        }
        Cursor cursor = jdbc.query("SELECT ended_at,id FROM task_notifications "
                        + "WHERE tenant_code=? AND recipient_key=? AND source=? AND notification_id=?",
                (rs, row) -> new Cursor(rs.getTimestamp(1).toInstant(), rs.getLong(2)),
                tenantCode, recipientKey, source, beforeId).stream().findFirst().orElse(null);
        if (cursor == null) return List.of();
        return jdbc.query("SELECT " + COLUMNS + " FROM task_notifications WHERE tenant_code=? AND recipient_key=? AND source=? "
                        + "AND (ended_at<? OR (ended_at=? AND id<?)) ORDER BY ended_at DESC,id DESC LIMIT ?",
                this::map, tenantCode, recipientKey, source,
                time(cursor.endedAt()), time(cursor.endedAt()), cursor.row(), limit);
    }

    @Override
    public List<TaskNotificationFact> unread(String tenantCode, String recipientKey, String source) {
        return jdbc.query("SELECT " + COLUMNS + " FROM task_notifications "
                        + "WHERE tenant_code=? AND recipient_key=? AND source=? AND read_at IS NULL ORDER BY ended_at DESC,id DESC",
                this::map, tenantCode, recipientKey, source);
    }

    @Override
    public Optional<TaskNotificationFact> find(String notificationId) {
        return one("SELECT " + COLUMNS + " FROM task_notifications WHERE notification_id=?", this::map, notificationId);
    }

    @Override
    public TaskNotificationFact markSeen(String tenantCode, String recipientKey, String source, String notificationId, Instant at) {
        jdbc.update("UPDATE task_notifications SET read_at=COALESCE(read_at,?),update_time=? "
                        + "WHERE notification_id=? AND tenant_code=? AND recipient_key=? AND source=?",
                time(at), time(at), notificationId, tenantCode, recipientKey, source);
        return jdbc.query("SELECT " + COLUMNS + " FROM task_notifications "
                        + "WHERE notification_id=? AND tenant_code=? AND recipient_key=? AND source=?",
                this::map, notificationId, tenantCode, recipientKey, source).stream().findFirst().orElseThrow();
    }

    @Override
    public int markAllSeen(String tenantCode, String recipientKey, String source, List<String> notificationIds, Instant at) {
        if (notificationIds == null || notificationIds.isEmpty()) return 0;
        int changed = 0;
        for (String id : notificationIds) {
            changed += jdbc.update("UPDATE task_notifications SET read_at=?,update_time=? "
                            + "WHERE notification_id=? AND tenant_code=? AND recipient_key=? AND source=? AND read_at IS NULL",
                    time(at), time(at), id, tenantCode, recipientKey, source);
        }
        return changed;
    }

    private TaskNotificationFact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new TaskNotificationFact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), rs.getString(9));
    }

    private record Cursor(Instant endedAt, long row) { }
}
