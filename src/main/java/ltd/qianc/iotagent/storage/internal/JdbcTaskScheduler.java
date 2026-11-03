package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskScheduler;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL-backed phase scheduler. The phase row is the durable queue; this adapter
 * deliberately has no process-local timer or in-memory fallback.
 */
public final class JdbcTaskScheduler implements TaskScheduler {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcTaskScheduler(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void schedule(String taskId, String phaseId, Instant at) {
        var rows = jdbc.query("SELECT payload_json FROM task_facts WHERE task_id=?", (rs, row) -> rs.getString(1), taskId);
        if (rows.isEmpty()) throw new IllegalStateException("任务事实不存在，无法调度阶段");
        JsonNode task = read(rows.get(0));
        String source = task.path("source").asText("office");
        JsonNode phase = null;
        for (JsonNode candidate : task.path("phases")) {
            if (phaseId.equals(candidate.path("phaseId").asText())) { phase = candidate; break; }
        }
        if (phase == null) throw new IllegalStateException("任务阶段不存在，无法调度");
        String payload = TaskPayloadSafety.withoutCredentials(mapper, phase).toString();
        jdbc.update("INSERT INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,definition_json,create_time,update_time) VALUES (?,?,?,1,'PENDING',?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "version=version+CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN 1 ELSE 0 END, "
                        + "due_at=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN VALUES(due_at) ELSE due_at END, "
                        + "payload_json=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN VALUES(payload_json) ELSE payload_json END, "
                        + "definition_json=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN VALUES(definition_json) ELSE definition_json END, "
                        + "source=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN VALUES(source) ELSE source END, "
                        + "claimed_at=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN NULL ELSE claimed_at END, "
                        + "ended_at=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN NULL ELSE ended_at END, "
                        + "failure_reason=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN NULL ELSE failure_reason END, "
                        + "update_time=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN VALUES(update_time) ELSE update_time END, "
                        + "status=CASE WHEN status IN ('PENDING','CANCELLED') AND result_json IS NULL THEN 'PENDING' ELSE status END",
                phaseId, taskId, source, java.sql.Timestamp.from(at), payload, payload,
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
    }

    @Override
    public void cancel(String taskId, String phaseId) {
        jdbc.update("UPDATE task_phases SET status='CANCELLED',version=version+1,ended_at=?,update_time=? WHERE task_id=? AND phase_id=? AND status='PENDING'",
                java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()), taskId, phaseId);
    }

    private JsonNode read(String value) {
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new IllegalStateException("任务事实内容无效", error); }
    }
}
