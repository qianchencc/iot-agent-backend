package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ltd.qianc.iotagent.task.TaskPlanFact;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskRecurrence;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskPlanStore extends JdbcFactSupport implements TaskPlanStore {
    public JdbcTaskPlanStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public TaskPlanFact save(TaskPlanFact plan) {
        var body = json(plan.payloadJson());
        JsonNode payloadNode = readPayload(plan.payloadJson());
        java.sql.Timestamp storedNextAt = instant(body.get("nextAt"));
        java.sql.Date storedNextDate = date(body.get("nextDate"));
        Instant nextAt = storedNextAt == null ? null : storedNextAt.toInstant();
        LocalDate nextDate = storedNextDate == null ? null : storedNextDate.toLocalDate();
        if (payloadNode.has("repeat") && (nextAt == null || nextDate == null)) {
            Pointer pointer = initialPointer(payloadNode);
            nextAt = pointer.at();
            nextDate = pointer.date();
        }
        try {
            jdbc.update("INSERT INTO task_plans(plan_id,tenant_code,owner_key,source,version,status,next_at,next_date,repeat_json,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    plan.planId(), text(body, "tenantCode"), text(body, "ownerKey"), text(body, "source"), plan.version(), plan.status(),
                    nextAt == null ? null : time(nextAt), nextDate == null ? null : java.sql.Date.valueOf(nextDate), repeatJson(payloadNode.get("repeat")),
                    plan.payloadJson(), plan.idempotencyKey(), time(Instant.now()), time(Instant.now()));
            return plan;
        } catch (DuplicateKeyException duplicate) { return findByIdempotencyKey(plan.idempotencyKey()).orElseThrow(() -> duplicate); }
    }
    @Override public Optional<TaskPlanFact> find(String id) { return one("SELECT plan_id,version,status,payload_json,idempotency_key FROM task_plans WHERE plan_id=?", this::map, id); }
    @Override public Optional<TaskPlanFact> findByIdempotencyKey(String key) { return one("SELECT plan_id,version,status,payload_json,idempotency_key FROM task_plans WHERE idempotency_key=?", this::map, key); }
    @Override public Optional<TaskPlanFact> updateIfVersion(TaskPlanFact replacement, long expectedVersion) {
        JsonNode payload = readPayload(replacement.payloadJson());
        Pointer pointer = "DELETED".equals(replacement.status()) || !payload.has("repeat")
                ? new Pointer(null, null) : initialPointer(payload);
        int changed = jdbc.update("UPDATE task_plans SET version=?,status=?,next_at=?,next_date=?,repeat_json=?,payload_json=?,update_time=? "
                        + "WHERE plan_id=? AND version=?",
                replacement.version(), replacement.status(),
                pointer.at() == null ? null : time(pointer.at()),
                pointer.date() == null ? null : java.sql.Date.valueOf(pointer.date()),
                repeatJson(payload.get("repeat")), replacement.payloadJson(), time(Instant.now()),
                replacement.planId(), expectedVersion);
        return changed == 1 ? Optional.of(replacement) : Optional.empty();
    }
    @Override public boolean claimOccurrence(String planId, String occurrenceDate, long expectedVersion) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int inserted = jdbc.update("INSERT IGNORE INTO task_plan_occurrences(plan_id,occurrence_date,status,create_time,update_time) VALUES (?,?, 'CLAIMED',?,?)",
                    planId, occurrenceDate, time(Instant.now()), time(Instant.now()));
            if (inserted != 1) return false;
            boolean updated = jdbc.update("UPDATE task_plans SET next_date=?,version=version+1,update_time=? WHERE plan_id=? AND version=? AND status='ENABLED'",
                    occurrenceDate, time(Instant.now()), planId, expectedVersion) == 1;
            if (!updated) status.setRollbackOnly();
            return updated;
        }));
    }
    @Override public List<TaskPlanFact> list(String tenantCode, String ownerKey, String source) { return jdbc.query("SELECT plan_id,version,status,payload_json,idempotency_key FROM task_plans WHERE tenant_code=? AND owner_key=? AND source=? ORDER BY update_time DESC", this::map, tenantCode, ownerKey, source); }
    private JsonNode readPayload(String value) {
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new IllegalArgumentException("任务内容无效", error); }
    }
    private String repeatJson(Object value) {
        if (value == null) return "{}";
        if (value instanceof String string) return string;
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("重复规则无效", error); }
    }
    private TaskPlanFact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new TaskPlanFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)); }

    private Pointer initialPointer(JsonNode body) {
        try {
            String at = body.path("interval").path("startsAt").asText("");
            if (at.isBlank()) at = body.path("phases").path(0).path("at").asText("");
            Instant anchor = Instant.parse(at);
            JsonNode repeat = body.path("repeat");
            String frequency = repeat.path("frequency").asText("");
            Set<Integer> weekdays = Set.of();
            if (repeat.has("weekdays")) {
                if (!repeat.get("weekdays").isArray()) throw new IllegalArgumentException("重复规则无效");
                var values = new java.util.ArrayList<Integer>();
                for (JsonNode item : repeat.get("weekdays")) values.add(item.isIntegralNumber() ? item.intValue() : -1);
                if (!"weekly".equals(frequency)) throw new IllegalArgumentException("重复规则无效");
                weekdays = TaskRecurrence.weekdays(values);
            }
            Integer day = repeat.has("dayOfMonth") ? repeat.path("dayOfMonth").asInt(-1) : null;
            LocalDate until = repeat.has("until") ? LocalDate.parse(repeat.path("until").asText()) : null;
            if (until != null && until.isBefore(TaskRecurrence.localDate(anchor))) throw new IllegalArgumentException("截止日无效");
            TaskRecurrence.RepeatRule rule = new TaskRecurrence.RepeatRule(frequency, weekdays, day, until);
            LocalDate date = TaskRecurrence.next(rule, anchor, Instant.now().minusNanos(1));
            if (date == null) throw new IllegalArgumentException("截止日前没有未来发生");
            return new Pointer(TaskRecurrence.occurrenceAt(anchor, date), date);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("周期规则无效", error);
        }
    }

    private record Pointer(Instant at, LocalDate date) { }
}
