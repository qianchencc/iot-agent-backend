package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import ltd.qianc.iotagent.task.TaskNotificationFact;
import ltd.qianc.iotagent.task.TaskNotificationStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskNotificationStore extends JdbcFactSupport implements TaskNotificationStore {
    public JdbcTaskNotificationStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public TaskNotificationFact record(TaskNotificationFact notification) {
        try {
            jdbc.update("INSERT INTO task_notifications(notification_id,task_id,tenant_code,recipient_key,source,outcome,ended_at,read_at,payload_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)", notification.notificationId(), notification.taskId(), notification.tenantCode(), notification.recipientKey(), notification.source(), notification.outcome(), time(notification.endedAt()), notification.readAt() == null ? null : time(notification.readAt()), notification.payloadJson(), time(Instant.now()), time(Instant.now()));
            return notification;
        } catch (DuplicateKeyException duplicate) { return list(notification.tenantCode(), notification.recipientKey(), notification.source(), 100, null).stream().filter(n -> n.taskId().equals(notification.taskId())).findFirst().orElseThrow(() -> duplicate); }
    }
    @Override public List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source, int limit, String before) {
        String sql = "SELECT notification_id,task_id,tenant_code,recipient_key,source,outcome,ended_at,read_at,payload_json FROM task_notifications WHERE tenant_code=? AND recipient_key=? AND source=? " + (before == null ? "" : "AND ended_at<? ") + "ORDER BY ended_at DESC,id DESC LIMIT ?";
        return before == null ? jdbc.query(sql, this::map, tenantCode, recipientKey, source, limit) : jdbc.query(sql, this::map, tenantCode, recipientKey, source, time(Instant.parse(before)), limit);
    }
    @Override public TaskNotificationFact markSeen(String tenantCode, String recipientKey, String source, String notificationId, Instant at) { jdbc.update("UPDATE task_notifications SET read_at=?,update_time=? WHERE notification_id=? AND tenant_code=? AND recipient_key=? AND source=?", time(at), time(at), notificationId, tenantCode, recipientKey, source); return list(tenantCode, recipientKey, source, 100, null).stream().filter(n -> n.notificationId().equals(notificationId)).findFirst().orElseThrow(); }
    @Override public int markAllSeen(String tenantCode, String recipientKey, String source, Instant at) { return jdbc.update("UPDATE task_notifications SET read_at=?,update_time=? WHERE tenant_code=? AND recipient_key=? AND source=? AND read_at IS NULL", time(at), time(at), tenantCode, recipientKey, source); }
    private TaskNotificationFact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new TaskNotificationFact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), rs.getString(9)); }
}
