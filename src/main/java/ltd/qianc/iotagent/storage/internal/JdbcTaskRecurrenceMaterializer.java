package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskRecurrence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded, restart-safe projection of due plan dates into durable task facts.
 * The occurrence row, task fact, phases and plan pointer commit together.
 */
public final class JdbcTaskRecurrenceMaterializer {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int batchSize;

    public JdbcTaskRecurrenceMaterializer(JdbcTemplate jdbc, TransactionTemplate transactions,
                                          ObjectMapper mapper, Clock clock, int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("recurrence batch size must be 1..100");
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public int materializeOnce() {
        Instant now = clock.instant();
        List<PlanRow> due = jdbc.query("SELECT plan_id,version,status,next_at,next_date,repeat_json,payload_json "
                        + "FROM task_plans WHERE status='ENABLED' AND next_at IS NOT NULL AND next_at <= ? "
                        + "ORDER BY next_at, plan_id LIMIT ?",
                (rs, row) -> new PlanRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6), rs.getString(7)),
                Timestamp.from(now), batchSize);
        int materialized = 0;
        for (PlanRow candidate : due) if (Boolean.TRUE.equals(materialize(candidate.planId(), now))) materialized++;
        return materialized;
    }

    private Boolean materialize(String planId, Instant now) {
        return transactions.execute(status -> {
            PlanRow row = jdbc.query("SELECT plan_id,version,status,next_at,next_date,repeat_json,payload_json "
                            + "FROM task_plans WHERE plan_id=? FOR UPDATE",
                    (rs, index) -> new PlanRow(rs.getString(1), rs.getLong(2), rs.getString(3),
                            rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6), rs.getString(7)), planId)
                    .stream().findFirst().orElse(null);
            if (row == null || !"ENABLED".equals(row.status()) || row.nextAt().isAfter(now) || row.nextDate() == null) return false;
            try {
                JsonNode payload = mapper.readTree(row.payloadJson());
                Instant anchor = anchor(payload);
                LocalDate anchorDate = TaskRecurrence.localDate(anchor);
                LocalDate date = LocalDate.parse(row.nextDate());
                TaskRecurrence.RepeatRule repeat = repeat(row.repeatJson(), anchorDate);
                if (date.isBefore(anchorDate) || !TaskRecurrence.matches(repeat, date)) return false;
                Instant occurrenceAt = TaskRecurrence.occurrenceAt(anchor, date);
                if (occurrenceAt.isAfter(now)) return false;

                String occurrenceId = TaskRecurrence.occurrenceId(row.planId(), date);
                String taskId = deterministicId("task\0" + occurrenceId);
                JsonNode snapshot = shifted(payload, date, anchorDate, occurrenceId);
                String taskPayload = TaskPayloadSafety.withoutCredentials(mapper, snapshot).toString();
                jdbc.update("INSERT IGNORE INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?)",
                        taskId, 1, "SCHEDULED", taskPayload, occurrenceId, Timestamp.from(now), Timestamp.from(now));
                insertPhases(snapshot, taskId, text(snapshot, "source", "office"), now);
                jdbc.update("INSERT INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,create_time,update_time) "
                                + "VALUES (?,?,?,'MATERIALIZED',?,?) ON DUPLICATE KEY UPDATE task_id=VALUES(task_id),status='MATERIALIZED',update_time=VALUES(update_time)",
                        row.planId(), date.toString(), taskId, Timestamp.from(now), Timestamp.from(now));

                LocalDate nextDate = TaskRecurrence.next(repeat, anchor, occurrenceAt, date);
                Instant nextAt = nextDate == null ? null : TaskRecurrence.occurrenceAt(anchor, nextDate);
                int changed = jdbc.update("UPDATE task_plans SET next_at=?,next_date=?,version=version+1,update_time=? "
                                + "WHERE plan_id=? AND status='ENABLED' AND version=? AND next_at=? AND next_date=?",
                        nextAt == null ? null : Timestamp.from(nextAt), nextDate == null ? null : nextDate.toString(),
                        Timestamp.from(now), row.planId(), row.version(), Timestamp.from(row.nextAt()), row.nextDate());
                if (changed != 1) { status.setRollbackOnly(); return false; }
                return true;
            } catch (RuntimeException malformed) {
                // Invalid/unsupported rules never become executable and are not guessed.
                status.setRollbackOnly();
                return false;
            } catch (Exception malformed) {
                status.setRollbackOnly();
                return false;
            }
        });
    }

    private void insertPhases(JsonNode payload, String taskId, String source, Instant now) {
        JsonNode phases = payload.path("phases");
        if (!phases.isArray()) throw new IllegalArgumentException("周期任务阶段无效");
        for (int index = 0; index < phases.size(); index++) {
            JsonNode phase = phases.get(index);
            String phaseId = phase.path("phaseId").asText("");
            if (phaseId.isBlank()) phaseId = deterministicId("phase\0" + taskId + "\0" + index);
            String at = phase.path("at").asText("");
            if (at.isBlank()) throw new IllegalArgumentException("周期任务阶段时间无效");
            Instant due = Instant.parse(at);
            String body = TaskPayloadSafety.withoutCredentials(mapper, phase).toString();
            jdbc.update("INSERT IGNORE INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,create_time,update_time) VALUES (?,?,?,1,'PENDING',?,?,?,?)",
                    phaseId, taskId, source, Timestamp.from(due), body, Timestamp.from(now), Timestamp.from(now));
        }
    }

    private TaskRecurrence.RepeatRule repeat(String json, LocalDate anchorDate) throws Exception {
        JsonNode value = mapper.readTree(json);
        String frequency = value.path("frequency").asText("");
        Set<Integer> weekdays = Set.of();
        if (value.has("weekdays")) {
            JsonNode values = value.get("weekdays");
            if (!"weekly".equals(frequency) || !values.isArray() || values.isEmpty()) throw new IllegalArgumentException("重复规则无效");
            List<Integer> parsed = new ArrayList<>();
            for (JsonNode item : values) parsed.add(item.isIntegralNumber() ? item.intValue() : -1);
            weekdays = TaskRecurrence.weekdays(parsed);
        }
        Integer dayOfMonth = value.has("dayOfMonth") ? (value.get("dayOfMonth").isIntegralNumber() ? value.get("dayOfMonth").intValue() : -1) : null;
        LocalDate until = value.has("until") ? LocalDate.parse(value.get("until").asText()) : null;
        if (until != null && until.isBefore(anchorDate)) throw new IllegalArgumentException("截止日无效");
        return new TaskRecurrence.RepeatRule(frequency, weekdays, dayOfMonth, until);
    }

    private Instant anchor(JsonNode payload) {
        String value = payload.path("interval").path("startsAt").asText("");
        if (value.isBlank()) value = payload.path("phases").path(0).path("at").asText("");
        if (value.isBlank()) throw new IllegalArgumentException("周期任务缺少时间锚点");
        return Instant.parse(value);
    }

    private JsonNode shifted(JsonNode original, LocalDate date, LocalDate anchorDate, String occurrenceId) {
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
        for (int index = 0; index < phases.size(); index++) {
            ObjectNode phase = (ObjectNode) phases.get(index);
            phase.put("at", shift(phase.path("at").asText(""), date, anchorDate));
            phase.put("phaseId", deterministicId("phase\0" + occurrenceId + "\0" + index));
        }
        root.put("sourcePlanId", occurrenceId.substring("occurrence:".length(), occurrenceId.lastIndexOf(':')));
        root.put("occurrenceDate", date.toString());
        return root;
    }

    private String shift(String value, LocalDate date, LocalDate anchorDate) {
        if (value.isBlank()) throw new IllegalArgumentException("周期任务时间无效");
        return Instant.parse(value).plus(Duration.ofDays(ChronoUnit.DAYS.between(anchorDate, date))).toString();
    }

    private static String text(JsonNode value, String key, String fallback) {
        String text = value.path(key).asText("");
        return text.isBlank() ? fallback : text;
    }

    private static String deterministicId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record PlanRow(String planId, long version, String status, Instant nextAt, String nextDate,
                           String repeatJson, String payloadJson) { }
}
