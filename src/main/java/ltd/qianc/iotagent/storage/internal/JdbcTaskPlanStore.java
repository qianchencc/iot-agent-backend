package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ltd.qianc.iotagent.task.TaskPlanFact;
import ltd.qianc.iotagent.task.TaskPlanOccurrence;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskRecurrence;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskPlanStore extends AbstractJdbcFactSupport implements TaskPlanStore {
    public JdbcTaskPlanStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    private static final String MANUAL_PAUSE_REASON = "用户手动停用";

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
        Instant updatedAt = Instant.now();
        Pointer pointer = Set.of("DELETED", "PAUSED").contains(replacement.status()) || !payload.has("repeat")
                ? new Pointer(null, null) : nextPointer(replacement.planId(), replacement.payloadJson(),
                        repeatJson(payload.get("repeat")), updatedAt);
        // Runtime pause/resume/delete transitions maintain pause_reason in the
        // same write: manual pause annotates the user action, resume and delete clear it.
        String pauseReason = "PAUSED".equals(replacement.status()) ? MANUAL_PAUSE_REASON : null;
        int changed = jdbc.update("UPDATE task_plans SET version=?,status=?,next_at=?,next_date=?,repeat_json=?,payload_json=?,pause_reason=?,update_time=? "
                        + "WHERE plan_id=? AND version=?",
                replacement.version(), replacement.status(),
                pointer.at() == null ? null : time(pointer.at()),
                pointer.date() == null ? null : java.sql.Date.valueOf(pointer.date()),
                repeatJson(payload.get("repeat")), replacement.payloadJson(), pauseReason, time(updatedAt),
                replacement.planId(), expectedVersion);
        return changed == 1 ? Optional.of(replacement) : Optional.empty();
    }

    @Override public java.util.List<TaskPlanFact> listByTenant(String tenantCode, String source) {
        return jdbc.query("SELECT plan_id,version,status,payload_json,idempotency_key FROM task_plans WHERE tenant_code=? AND source=? ORDER BY update_time DESC",
                this::map, tenantCode, source);
    }

    @Override public Optional<TaskPlanFact> pauseForInvalidation(String planId, String reason, Instant now) {
        if (planId == null || planId.isBlank() || now == null) return Optional.empty();
        String annotation = "自动停用：" + (reason == null || reason.isBlank() ? "授权或绑定失效" : reason);
        return transactions.execute(status -> {
            CancelRow row = jdbc.query("SELECT version,status,payload_json,idempotency_key FROM task_plans WHERE plan_id=? FOR UPDATE",
                    (rs, index) -> new CancelRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), planId)
                    .stream().findFirst().orElse(null);
            if (row == null) return Optional.<TaskPlanFact>empty();
            TaskPlanFact current = new TaskPlanFact(planId, row.version(), row.status(), row.payloadJson(), row.idempotencyKey());
            if (!"ENABLED".equals(row.status())) return Optional.of(current);
            int changed = jdbc.update("UPDATE task_plans SET status='PAUSED',pause_reason=?,next_at=NULL,next_date=NULL,"
                            + "version=version+1,update_time=? WHERE plan_id=? AND version=? AND status='ENABLED'",
                    annotation, time(now), planId, row.version());
            if (changed != 1) {
                status.setRollbackOnly();
                return Optional.<TaskPlanFact>empty();
            }
            return Optional.of(new TaskPlanFact(planId, row.version() + 1, "PAUSED", row.payloadJson(), row.idempotencyKey()));
        });
    }

    @Override public Optional<String> pauseReason(String planId) {
        return jdbc.query("SELECT pause_reason FROM task_plans WHERE plan_id=?",
                (rs, row) -> rs.getString(1), planId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    @Override public Optional<TaskPlanStore.PlanPointer> pointer(String planId) {
        return one("SELECT next_at,next_date,update_time FROM task_plans WHERE plan_id=?", (rs, row) -> {
            java.sql.Timestamp nextAt = rs.getTimestamp(1);
            java.sql.Timestamp updatedAt = rs.getTimestamp(3);
            return new TaskPlanStore.PlanPointer(nextAt == null ? null : nextAt.toInstant(),
                    rs.getDate(2) == null ? null : rs.getDate(2).toLocalDate(),
                    updatedAt == null ? Instant.now() : updatedAt.toInstant());
        }, planId);
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
    @Override public Optional<TaskPlanFact> cancelAllIfVersion(String planId, long expectedVersion, Instant now) {
        if (planId == null || planId.isBlank() || now == null) return Optional.empty();
        return transactions.execute(status -> {
            CancelRow row = jdbc.query("SELECT version,status,payload_json,idempotency_key FROM task_plans WHERE plan_id=? FOR UPDATE",
                    (rs, index) -> new CancelRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), planId)
                    .stream().findFirst().orElse(null);
            if (row == null || row.version() != expectedVersion || "DELETED".equals(row.status())) return Optional.empty();
            List<String> taskIds = jdbc.query("SELECT task_id FROM task_plan_occurrences WHERE plan_id=? AND task_id IS NOT NULL",
                    (rs, index) -> rs.getString(1), planId);
            for (String taskId : taskIds) {
                jdbc.update("UPDATE task_phases SET status='CANCELLED',version=version+1,ended_at=?,update_time=? "
                                + "WHERE task_id=? AND status='PENDING'",
                        time(now), time(now), taskId);
                // Runtime store.cancelPlan flips every still-scheduled/executing
                // materialized task to CANCELLED unconditionally — a partially
                // completed occurrence is cancelled too; completed or unknown
                // checkpoints stay as history and an executing phase keeps
                // settling under the scanner's cancelled-first rule.
                jdbc.update("UPDATE task_facts SET status='CANCELLED',version=version+1,update_time=? "
                                + "WHERE task_id=? AND status IN ('SCHEDULED','EXECUTING')", time(now), taskId);
            }
            int changed = jdbc.update("UPDATE task_plans SET status='DELETED',version=?,next_at=NULL,next_date=NULL,pause_reason=NULL,update_time=? "
                            + "WHERE plan_id=? AND version=? AND status <> 'DELETED'",
                    expectedVersion + 1, time(now), planId, expectedVersion);
            if (changed != 1) { status.setRollbackOnly(); return Optional.empty(); }
            return Optional.of(new TaskPlanFact(planId, expectedVersion + 1, "DELETED",
                    row.payloadJson(), row.idempotencyKey()));
        });
    }
    @Override public Optional<TaskPlanOccurrence> overrideOccurrenceIfVersion(String planId, LocalDate occurrenceDate,
                                                                                long expectedPlanVersion, String payloadJson,
                                                                                Instant now) {
        if (occurrenceDate == null || now == null) return Optional.empty();
        return transactions.execute(status -> {
            PlanRow row = jdbc.query("SELECT version,status,next_at,next_date,repeat_json,payload_json FROM task_plans WHERE plan_id=? FOR UPDATE",
                    (rs, index) -> new PlanRow(rs.getLong(1), rs.getString(2),
                            rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                            rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(), rs.getString(5), rs.getString(6)), planId)
                    .stream().findFirst().orElse(null);
            if (row == null || row.version() != expectedPlanVersion || "DELETED".equals(row.status())) return Optional.empty();
            JsonNode original = readPayload(row.payloadJson());
            Instant anchor = anchor(original);
            TaskRecurrence.RepeatRule repeat = repeat(row.repeatJson(), TaskRecurrence.localDate(anchor));
            if (occurrenceDate.isBefore(TaskRecurrence.localDate(anchor)) || !TaskRecurrence.matches(repeat, occurrenceDate)) return Optional.empty();
            JsonNode requested = payloadJson == null ? shifted(original, occurrenceDate, TaskRecurrence.localDate(anchor)) : readPayload(payloadJson);
            if (!effectiveAt(requested).isAfter(now)) return Optional.empty();
            ExistingOccurrence previous = jdbc.query("SELECT task_id,status,version FROM task_plan_occurrences WHERE plan_id=? AND occurrence_date=?",
                    (rs, index) -> new ExistingOccurrence(rs.getString(1), rs.getString(2), rs.getLong(3)), planId, occurrenceDate)
                    .stream().findFirst().orElse(null);
            if (previous != null && (previous.taskId() != null || Set.of("CLAIMED", "MATERIALIZED").contains(previous.status()))) return Optional.empty();
            long occurrenceVersion = previous == null ? 1L : previous.version() + 1L;
            String occurrenceStatus = payloadJson == null ? "SKIPPED" : "OVERRIDDEN";
            int occurrenceChanged = jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,status,override_payload_json,version,create_time,update_time) "
                            + "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),override_payload_json=VALUES(override_payload_json),version=VALUES(version),update_time=VALUES(update_time)",
                    planId, occurrenceDate, occurrenceStatus, payloadJson, occurrenceVersion, time(now), time(now));
            if (occurrenceChanged != 1) { status.setRollbackOnly(); return Optional.empty(); }
            Pointer pointer = nextPointer(planId, row.payloadJson(), row.repeatJson(), now);
            int planChanged = jdbc.update("UPDATE task_plans SET next_at=?,next_date=?,version=?,update_time=? "
                            + "WHERE plan_id=? AND version=? AND status <> 'DELETED'",
                    pointer.at() == null ? null : time(pointer.at()), pointer.date(), expectedPlanVersion + 1,
                    time(now), planId, expectedPlanVersion);
            if (planChanged != 1) { status.setRollbackOnly(); return Optional.empty(); }
            return Optional.of(new TaskPlanOccurrence(planId, occurrenceDate, previous == null ? null : previous.taskId(),
                    occurrenceStatus, payloadJson, occurrenceVersion, expectedPlanVersion + 1));
        });
    }
    @Override public Optional<TaskPlanOccurrence> findOccurrence(String planId, LocalDate occurrenceDate) {
        return jdbc.query("SELECT o.plan_id,o.occurrence_date,o.task_id,o.status,o.override_payload_json,o.version,p.version "
                        + "FROM task_plan_occurrences o JOIN task_plans p ON p.plan_id=o.plan_id WHERE o.plan_id=? AND o.occurrence_date=?",
                (rs, index) -> new TaskPlanOccurrence(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)), planId, occurrenceDate)
                .stream().findFirst();
    }
    @Override public List<TaskPlanOccurrence> occurrences(String planId) {
        return jdbc.query("SELECT o.plan_id,o.occurrence_date,o.task_id,o.status,o.override_payload_json,o.version,p.version "
                        + "FROM task_plan_occurrences o JOIN task_plans p ON p.plan_id=o.plan_id WHERE o.plan_id=? "
                        + "ORDER BY o.occurrence_date",
                (rs, index) -> new TaskPlanOccurrence(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7)), planId);
    }
    @Override public List<TaskPlanFact> list(String tenantCode, String ownerKey, String source) { return jdbc.query("SELECT plan_id,version,status,payload_json,idempotency_key FROM task_plans WHERE tenant_code=? AND owner_key=? AND source=? ORDER BY update_time DESC", this::map, tenantCode, ownerKey, source); }
    @Override public Optional<String> latestTaskId(String planId) {
        return jdbc.query("SELECT o.task_id FROM task_plan_occurrences o JOIN task_facts t ON t.task_id=o.task_id "
                        + "WHERE o.plan_id=? AND o.task_id IS NOT NULL ORDER BY t.update_time DESC, o.occurrence_date DESC LIMIT 1",
                (rs, row) -> rs.getString(1), planId).stream().findFirst();
    }

    private Pointer nextPointer(String planId, String payloadJson, String repeatJson, Instant now) {
        JsonNode payload = readPayload(payloadJson);
        Instant anchor = anchor(payload);
        TaskRecurrence.RepeatRule repeat = repeat(repeatJson, TaskRecurrence.localDate(anchor));
        Map<LocalDate, ExistingOccurrence> occurrences = new HashMap<>();
        jdbc.query("SELECT occurrence_date,task_id,status,override_payload_json,version FROM task_plan_occurrences WHERE plan_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> occurrences.put(rs.getDate(1).toLocalDate(),
                        new ExistingOccurrence(rs.getString(2), rs.getString(3), rs.getLong(5), rs.getString(4))), planId);
        LocalDate anchorDate = TaskRecurrence.localDate(anchor);
        Instant candidateAt = null; LocalDate candidateDate = null;
        for (int offset = 0; offset <= 4096; offset++) {
            LocalDate date = anchorDate.plusDays(offset);
            if (!TaskRecurrence.matches(repeat, date)) continue;
            ExistingOccurrence occurrence = occurrences.get(date);
            if (occurrence != null && (occurrence.taskId() != null || Set.of("CLAIMED", "MATERIALIZED", "SKIPPED").contains(occurrence.status()))) continue;
            JsonNode effective = occurrence != null && occurrence.overridePayloadJson() != null
                    ? readPayload(occurrence.overridePayloadJson()) : shifted(payload, date, anchorDate);
            Instant at = firstFutureAt(effective, now, effectiveAt(effective));
            if (!at.isAfter(now)) continue;
            if (candidateAt == null || at.isBefore(candidateAt) || (at.equals(candidateAt) && date.isBefore(candidateDate))) {
                candidateAt = at; candidateDate = date;
            }
        }
        return new Pointer(candidateAt, candidateDate);
    }
    private Instant firstFutureAt(JsonNode effective, Instant now, Instant fallback) {
        JsonNode interval = effective.path("interval");
        JsonNode phases = effective.path("phases");
        if (!interval.isObject() || !phases.isArray() || phases.size() <= 1) return fallback;
        try {
            Instant start = Instant.parse(interval.path("startsAt").asText());
            Instant end = Instant.parse(interval.path("endsAt").asText());
            if (start.isAfter(now) || !now.isBefore(end)) return fallback;
            Instant first = null;
            for (JsonNode phase : phases) {
                Instant at = Instant.parse(phase.path("at").asText());
                if (at.isAfter(now) && (first == null || at.isBefore(first))) first = at;
            }
            return first == null ? fallback : first;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
    private JsonNode shifted(JsonNode original, LocalDate date, LocalDate anchorDate) {
        JsonNode copy = original.deepCopy();
        if (!copy.isObject()) throw new IllegalArgumentException("周期任务内容无效");
        ObjectNode root = (ObjectNode) copy;
        JsonNode interval = root.get("interval");
        if (interval != null && interval.isObject()) {
            ObjectNode mutable = (ObjectNode) interval;
            mutable.put("startsAt", shift(mutable.path("startsAt").asText(""), date, anchorDate));
            mutable.put("endsAt", shift(mutable.path("endsAt").asText(""), date, anchorDate));
        }
        JsonNode phases = root.get("phases");
        if (phases == null || !phases.isArray()) throw new IllegalArgumentException("周期任务阶段无效");
        for (JsonNode phase : phases) if (phase.isObject()) ((ObjectNode) phase).put("at",
                shift(phase.path("at").asText(""), date, anchorDate));
        return root;
    }
    private String shift(String value, LocalDate date, LocalDate anchorDate) {
        if (value.isBlank()) throw new IllegalArgumentException("周期任务时间无效");
        return Instant.parse(value).plus(java.time.Duration.ofDays(java.time.temporal.ChronoUnit.DAYS.between(anchorDate, date))).toString();
    }
    private Instant effectiveAt(JsonNode payload) {
        String value = payload.path("interval").path("startsAt").asText("");
        if (value.isBlank()) value = payload.path("phases").path(0).path("at").asText("");
        if (value.isBlank()) throw new IllegalArgumentException("周期任务缺少时间锚点");
        return Instant.parse(value);
    }
    private Instant anchor(JsonNode payload) { return effectiveAt(payload); }
    private TaskRecurrence.RepeatRule repeat(String json, LocalDate anchorDate) {
        JsonNode value = readPayload(json);
        String frequency = value.path("frequency").asText("");
        Set<Integer> weekdays = Set.of();
        if (value.has("weekdays")) {
            JsonNode values = value.get("weekdays");
            if (!"weekly".equals(frequency) || !values.isArray() || values.isEmpty()) throw new IllegalArgumentException("重复规则无效");
            var parsed = new ArrayList<Integer>();
            for (JsonNode item : values) parsed.add(item.isIntegralNumber() ? item.intValue() : -1);
            weekdays = TaskRecurrence.weekdays(parsed);
        }
        Integer dayOfMonth = value.has("dayOfMonth") ? (value.get("dayOfMonth").isIntegralNumber() ? value.get("dayOfMonth").intValue() : -1) : null;
        LocalDate until = value.has("until") ? LocalDate.parse(value.get("until").asText()) : null;
        if (until != null && until.isBefore(anchorDate)) throw new IllegalArgumentException("截止日无效");
        return new TaskRecurrence.RepeatRule(frequency, weekdays, dayOfMonth, until);
    }
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
            Instant now = Instant.now();
            LocalDate date = TaskRecurrence.next(rule, anchor, now.minusNanos(1));
            if (date == null) throw new IllegalArgumentException("截止日前没有未来发生");
            Instant checkpoint = firstFutureAt(body, now, TaskRecurrence.occurrenceAt(anchor, date));
            if (checkpoint != null && !checkpoint.equals(TaskRecurrence.occurrenceAt(anchor, date))) {
                return new Pointer(checkpoint, TaskRecurrence.localDate(now));
            }
            return new Pointer(TaskRecurrence.occurrenceAt(anchor, date), date);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("周期规则无效", error);
        }
    }

    private record Pointer(Instant at, LocalDate date) { }
    private record CancelRow(long version, String status, String payloadJson, String idempotencyKey) { }
    private record PlanRow(long version, String status, Instant nextAt, LocalDate nextDate, String repeatJson, String payloadJson) { }
    private record ExistingOccurrence(String taskId, String status, long version, String overridePayloadJson) {
        private ExistingOccurrence(String taskId, String status, long version) { this(taskId, status, version, null); }
    }
}
