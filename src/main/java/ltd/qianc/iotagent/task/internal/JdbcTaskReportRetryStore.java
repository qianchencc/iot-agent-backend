package ltd.qianc.iotagent.task.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskReportRetryStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Finds terminal tasks whose server-evidenced report was requested but is not
 * persisted yet. Retrying only calls the report module; it never re-dispatches
 * a phase or repeats a device action.
 */
public final class JdbcTaskReportRetryStore implements TaskReportRetryStore {
    private final JdbcTemplate jdbc;

    public JdbcTaskReportRetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TaskFact> pending(int limit) {
        int bounded = Math.max(1, Math.min(limit, 25));
        return jdbc.query("SELECT t.task_id,t.version,t.status,t.payload_json,t.idempotency_key "
                        + "FROM task_facts t LEFT JOIN task_report_attempts a ON a.task_id=t.task_id "
                        + "WHERE t.status IN ('COMPLETED','PARTIAL','FAILED','UNCONFIRMED','INTERRUPTED') "
                        + "AND (JSON_EXTRACT(t.payload_json,'$.reportRequest') IS NOT NULL "
                        + "OR (JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'$.source'))='company' "
                        + "AND EXISTS (SELECT 1 FROM task_phases p WHERE p.task_id=t.task_id "
                        + "AND (JSON_UNQUOTE(JSON_EXTRACT(p.result_json,'$.observations[0].type'))='ac_inspection' "
                        + "OR JSON_UNQUOTE(JSON_EXTRACT(p.definition_json,'$.read.ac.mode')) IS NOT NULL)))) "
                        + "AND NOT EXISTS (SELECT 1 FROM report_facts r "
                        + "WHERE JSON_UNQUOTE(JSON_EXTRACT(r.payload_json,'$.taskId'))=t.task_id) "
                        + "AND (a.task_id IS NULL OR (a.attempts<5 AND (a.next_attempt_at IS NULL OR a.next_attempt_at<=?))) "
                        + "ORDER BY t.update_time LIMIT ?",
                (rs, row) -> new TaskFact(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)), Timestamp.from(Instant.now()), bounded);
    }

    @Override
    public void failed(String taskId, String error) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO task_report_attempts(task_id,status,attempts,last_error,next_attempt_at,create_time,update_time) "
                        + "VALUES (?, 'FAILED', 1, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE status='FAILED',attempts=attempts+1,last_error=VALUES(last_error),"
                        + "next_attempt_at=TIMESTAMPADD(MINUTE, attempts*5, VALUES(update_time)),update_time=VALUES(update_time)",
                taskId, truncate(error), Timestamp.from(Instant.now().plusSeconds(300)), now, now);
    }

    @Override
    public void succeeded(String taskId) {
        jdbc.update("DELETE FROM task_report_attempts WHERE task_id=?", taskId);
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
