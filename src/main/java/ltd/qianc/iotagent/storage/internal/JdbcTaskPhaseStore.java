package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.task.TaskPhaseStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskPhaseStore extends JdbcFactSupport implements TaskPhaseStore {
    public JdbcTaskPhaseStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public List<TaskPhase> due(Instant now, int limit) {
        int bounded = Math.max(1, Math.min(limit, 4));
        return jdbc.query("SELECT task_id,phase_id,version,source,payload_json,due_at,status FROM task_phases WHERE status='PENDING' AND due_at<=? ORDER BY due_at,id LIMIT ?", this::map, time(now), bounded);
    }
    @Override public boolean claim(String phaseId, long expectedVersion, Instant claimedAt) { return jdbc.update("UPDATE task_phases SET status='EXECUTING',version=version+1,claimed_at=?,update_time=? WHERE phase_id=? AND version=? AND status='PENDING'", time(claimedAt), time(claimedAt), phaseId, expectedVersion) == 1; }
    @Override public Optional<TaskPhase> find(String phaseId) { return one("SELECT task_id,phase_id,version,source,payload_json,due_at,status FROM task_phases WHERE phase_id=?", this::map, phaseId); }
    @Override public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt) {
        return jdbc.update("UPDATE task_phases SET status=?,version=version+1,payload_json=?,ended_at=?,update_time=? WHERE phase_id=? AND version=? AND status='EXECUTING'", status, payloadJson, time(endedAt), time(endedAt), phaseId, expectedVersion) == 1;
    }
    @Override public int recoverExecuting(Instant at) {
        return jdbc.update("UPDATE task_phases SET status='INTERRUPTED',version=version+1,payload_json='{\"outcome\":\"unknown\",\"reason\":\"service_restart\"}',ended_at=?,update_time=? WHERE status='EXECUTING'", time(at), time(at));
    }
    @Override public boolean allTerminal(String taskId) {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM task_phases WHERE task_id=? AND status IN ('PENDING','RETRY','EXECUTING')", Integer.class, taskId);
        return active == null || active == 0;
    }
    private TaskPhase map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new TaskPhase(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(), rs.getString(7)); }
}
