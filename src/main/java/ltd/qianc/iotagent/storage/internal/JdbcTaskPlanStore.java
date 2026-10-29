package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.task.TaskPlanFact;
import ltd.qianc.iotagent.task.TaskPlanStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskPlanStore extends JdbcFactSupport implements TaskPlanStore {
    public JdbcTaskPlanStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public TaskPlanFact save(TaskPlanFact plan) {
        var body = json(plan.payloadJson());
        try {
            jdbc.update("INSERT INTO task_plans(plan_id,tenant_code,owner_key,source,version,status,next_at,next_date,repeat_json,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", plan.planId(), text(body, "tenantCode"), text(body, "ownerKey"), text(body, "source"), plan.version(), plan.status(), instant(body.get("nextAt")), date(body.get("nextDate")), jsonText(body.get("repeat")), plan.payloadJson(), plan.idempotencyKey(), time(java.time.Instant.now()), time(java.time.Instant.now()));
            return plan;
        } catch (DuplicateKeyException duplicate) { return findByIdempotencyKey(plan.idempotencyKey()).orElseThrow(() -> duplicate); }
    }
    @Override public Optional<TaskPlanFact> find(String id) { return one("SELECT plan_id,version,status,payload_json,idempotency_key FROM task_plans WHERE plan_id=?", this::map, id); }
    @Override public Optional<TaskPlanFact> findByIdempotencyKey(String key) { return one("SELECT plan_id,version,status,payload_json,idempotency_key FROM task_plans WHERE idempotency_key=?", this::map, key); }
    @Override public Optional<TaskPlanFact> updateIfVersion(TaskPlanFact replacement, long expectedVersion) { int changed = jdbc.update("UPDATE task_plans SET version=?,status=?,payload_json=?,update_time=? WHERE plan_id=? AND version=?", replacement.version(), replacement.status(), replacement.payloadJson(), time(java.time.Instant.now()), replacement.planId(), expectedVersion); return changed == 1 ? Optional.of(replacement) : Optional.empty(); }
    @Override public boolean claimOccurrence(String planId, String occurrenceDate, long expectedVersion) { return jdbc.update("UPDATE task_plans SET next_date=?,version=version+1,update_time=? WHERE plan_id=? AND version=? AND status='enabled'", occurrenceDate, time(java.time.Instant.now()), planId, expectedVersion) == 1; }
    @Override public List<TaskPlanFact> list(String tenantCode, String ownerKey, String source) { return jdbc.query("SELECT plan_id,version,status,payload_json,idempotency_key FROM task_plans WHERE tenant_code=? AND owner_key=? AND source=? ORDER BY update_time DESC", this::map, tenantCode, ownerKey, source); }
    private TaskPlanFact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new TaskPlanFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)); }
}
