package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskFactStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskFactStore extends AbstractJdbcFactSupport implements TaskFactStore {
    public JdbcTaskFactStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public TaskFact save(TaskFact task) {
        try {
            jdbc.update("INSERT INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?)", task.taskId(), task.version(), task.status(), task.payloadJson(), task.idempotencyKey(), time(java.time.Instant.now()), time(java.time.Instant.now()));
            return task;
        } catch (DuplicateKeyException duplicate) { return findByIdempotencyKey(task.idempotencyKey()).orElseThrow(() -> duplicate); }
    }
    @Override public Optional<TaskFact> find(String taskId) { return one("SELECT task_id,version,status,payload_json,idempotency_key FROM task_facts WHERE task_id=?", this::map, taskId); }
    @Override public Optional<TaskFact> findByIdempotencyKey(String key) { return one("SELECT task_id,version,status,payload_json,idempotency_key FROM task_facts WHERE idempotency_key=?", this::map, key); }
    @Override public Optional<TaskFact> updateIfVersion(TaskFact replacement, long expectedVersion) {
        int changed = jdbc.update("UPDATE task_facts SET version=?,status=?,payload_json=?,idempotency_key=?,update_time=? WHERE task_id=? AND version=?", replacement.version(), replacement.status(), replacement.payloadJson(), replacement.idempotencyKey(), time(java.time.Instant.now()), replacement.taskId(), expectedVersion);
        return changed == 1 ? Optional.of(replacement) : Optional.empty();
    }
    @Override public Optional<TaskFact> findBySource(String sourceId) { return jdbc.query("SELECT task_id,version,status,payload_json,idempotency_key FROM task_facts ORDER BY update_time DESC", this::map).stream().filter(t -> json(t.payloadJson()).getOrDefault("sourceId", "").equals(sourceId)).findFirst(); }
    private TaskFact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new TaskFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)); }
}
