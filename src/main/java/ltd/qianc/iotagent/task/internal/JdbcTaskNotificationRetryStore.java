package ltd.qianc.iotagent.task.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskNotificationRetryStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Finds terminal tasks whose inbox notification was never persisted. Retrying
 * only re-projects the notification row from durable terminal facts; it never
 * re-dispatches a phase or repeats a device action.
 */
public final class JdbcTaskNotificationRetryStore implements TaskNotificationRetryStore {
    private final JdbcTemplate jdbc;

    public JdbcTaskNotificationRetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void markPending(String taskId, String error) {
        attempt(taskId, error);
    }

    @Override
    public List<TaskFact> pending(int limit) {
        int bounded = Math.max(1, Math.min(limit, 25));
        return jdbc.query("SELECT t.task_id,t.version,t.status,t.payload_json,t.idempotency_key "
                        + "FROM task_facts t LEFT JOIN task_notification_attempts a ON a.task_id=t.task_id "
                        + "WHERE t.status IN ('COMPLETED','PARTIAL','FAILED','UNCONFIRMED','INTERRUPTED','MISSED','SATISFIED','CANCELLED') "
                        + "AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'$.sourceType')),JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'$.source.type')),'') <> 'immediate_draft' "
                        + "AND NOT EXISTS (SELECT 1 FROM task_notifications n WHERE n.task_id=t.task_id) "
                        + "AND (a.task_id IS NULL OR (a.attempts<5 AND (a.next_attempt_at IS NULL OR a.next_attempt_at<=?))) "
                        + "ORDER BY t.update_time LIMIT ?",
                (rs, row) -> new TaskFact(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)), Timestamp.from(Instant.now()), bounded);
    }

    @Override
    public void failed(String taskId, String error) {
        attempt(taskId, error);
    }

    private void attempt(String taskId, String error) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO task_notification_attempts(task_id,status,attempts,last_error,next_attempt_at,create_time,update_time) "
                        + "VALUES (?, 'PENDING', 1, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status='PENDING',attempts=attempts+1,last_error=VALUES(last_error),"
                        + "next_attempt_at=TIMESTAMPADD(MINUTE, attempts*5, VALUES(update_time)),update_time=VALUES(update_time)",
                taskId, truncate(error), Timestamp.from(Instant.now().plusSeconds(300)), now, now);
    }

    @Override
    public void succeeded(String taskId) {
        jdbc.update("DELETE FROM task_notification_attempts WHERE task_id=?", taskId);
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
