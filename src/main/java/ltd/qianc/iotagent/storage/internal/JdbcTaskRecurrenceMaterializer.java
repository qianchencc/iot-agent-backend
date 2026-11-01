package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStore;
import ltd.qianc.iotagent.task.TaskOccurrenceMaterializer;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskRecurrence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bounded, restart-safe projection of due plan dates into durable task facts.
 * The occurrence row, task fact, phases and plan pointer commit together.
 */
public final class JdbcTaskRecurrenceMaterializer implements TaskOccurrenceMaterializer {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int batchSize;
    private final TaskExecutionIdentityStore identities;

    public JdbcTaskRecurrenceMaterializer(JdbcTemplate jdbc, TransactionTemplate transactions,
                                          ObjectMapper mapper, Clock clock, int batchSize) {
        this(jdbc, transactions, mapper, clock, batchSize, null);
    }

    public JdbcTaskRecurrenceMaterializer(JdbcTemplate jdbc, TransactionTemplate transactions,
                                          ObjectMapper mapper, Clock clock, int batchSize,
                                          TaskExecutionIdentityStore identities) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("recurrence batch size must be 1..100");
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
        this.batchSize = batchSize;
        this.identities = identities;
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
            LocalDate date = LocalDate.parse(row.nextDate());
            // The scheduler polls within seconds; an occurrence older than the bounded
            // grace window is never replayed on restart. It is recorded as skipped so
            // the next occurrence can be computed without losing the decision.
            if (row.nextAt().isBefore(now.minus(Duration.ofMinutes(10)))) {
                TaskRecurrence.RepeatRule rule = repeat(row.repeatJson(), TaskRecurrence.localDate(anchor(readPayload(row.payloadJson()))));
                Pointer pointer = nextPointer(row, rule, anchor(readPayload(row.payloadJson())), date, now);
                jdbc.update("INSERT IGNORE INTO task_plan_occurrences(plan_id,occurrence_date,status,create_time,update_time) "
                                + "VALUES (?,?, 'SKIPPED',?,?)",
                        row.planId(), date.toString(), Timestamp.from(now), Timestamp.from(now));
                return advance(row, pointer, now, status);
            }
            try {
                JsonNode payload = readPayload(row.payloadJson());
                Instant anchor = anchor(payload);
                LocalDate anchorDate = TaskRecurrence.localDate(anchor);
                TaskRecurrence.RepeatRule repeat = repeat(row.repeatJson(), anchorDate);
                if (date.isBefore(anchorDate) || !TaskRecurrence.matches(repeat, date)) return false;

                OverrideRow override = jdbc.query("SELECT status,override_payload_json,task_id FROM task_plan_occurrences WHERE plan_id=? AND occurrence_date=?",
                        (rs, index) -> new OverrideRow(rs.getString(1), rs.getString(2), rs.getString(3)), row.planId(), date)
                        .stream().findFirst().orElse(null);
                if (override != null && override.taskId() != null) {
                    Pointer pointer = nextPointer(row, repeat, anchor, date, now);
                    if (!advance(row, pointer, now, status)) return false;
                    return true;
                }
                if (override != null && "OVERRIDDEN".equals(override.status()) && override.overridePayloadJson() == null) {
                    status.setRollbackOnly();
                    return false;
                }
                if (override != null && !Set.of("SKIPPED", "OVERRIDDEN", "CLAIMED", "MATERIALIZED").contains(override.status())) {
                    status.setRollbackOnly();
                    return false;
                }
                Instant occurrenceAt = override != null && override.overridePayloadJson() != null
                        ? effectiveAt(readPayload(override.overridePayloadJson())) : TaskRecurrence.occurrenceAt(anchor, date);
                if (occurrenceAt.isAfter(now)) return false;

                if (override != null && "SKIPPED".equals(override.status())) {
                    Pointer pointer = nextPointer(row, repeat, anchor, date, now);
                    return advance(row, pointer, now, status);
                }

                String occurrenceId = TaskRecurrence.occurrenceId(row.planId(), date);
                String taskId = deterministicId("task\0" + occurrenceId);
                JsonNode snapshot = override != null && override.overridePayloadJson() != null
                        ? withPlanIdentity(payload, readPayload(override.overridePayloadJson()))
                        : shifted(payload, date, anchorDate, occurrenceId);
                if (!snapshot.isObject()) throw new IllegalArgumentException("周期任务内容无效");
                ((ObjectNode) snapshot).put("sourcePlanId", row.planId()).put("occurrenceDate", date.toString());
                if (snapshot.path("interval").isObject() && snapshot.path("phases").size() > 1) {
                    Instant intervalStart = Instant.parse(snapshot.path("interval").path("startsAt").asText());
                    if (!intervalStart.isAfter(now)) {
                        ArrayNode remaining = mapper.createArrayNode();
                        for (JsonNode phase : snapshot.path("phases")) {
                            Instant at = Instant.parse(phase.path("at").asText());
                            if (!at.isBefore(now.minus(Duration.ofMinutes(10)))) remaining.add(phase);
                        }
                        if (remaining.isEmpty()) {
                            jdbc.update("INSERT IGNORE INTO task_plan_occurrences(plan_id,occurrence_date,status,create_time,update_time) "
                                            + "VALUES (?,?, 'SKIPPED',?,?)",
                                    row.planId(), date.toString(), Timestamp.from(now), Timestamp.from(now));
                            Pointer skipped = nextPointer(row, repeat, anchor, date, now);
                            return advance(row, skipped, now, status);
                        }
                        ((ObjectNode) snapshot).set("phases", remaining);
                    }
                }
                JsonNode phases = snapshot.path("phases");
                for (int index = 0; index < phases.size(); index++) {
                    if (phases.get(index).isObject()) ((ObjectNode) phases.get(index)).put("phaseId",
                            deterministicId("phase\0" + occurrenceId + "\0" + index));
                }
                String taskPayload = TaskPayloadSafety.withoutCredentials(mapper, snapshot).toString();
                jdbc.update("INSERT IGNORE INTO task_facts(task_id,version,status,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?)",
                        taskId, 1, "SCHEDULED", taskPayload, occurrenceId, Timestamp.from(now), Timestamp.from(now));
                if (identities != null) identities.copyPlanIdentityToTask(row.planId(), taskId);
                insertPhases(snapshot, taskId, text(snapshot, "source", "office"), now);
                int inserted = jdbc.update("INSERT IGNORE INTO task_plan_occurrences(plan_id,occurrence_date,task_id,status,create_time,update_time) "
                                + "VALUES (?,?,?,'MATERIALIZED',?,?)",
                        row.planId(), date.toString(), taskId, Timestamp.from(now), Timestamp.from(now));
                if (inserted == 0) {
                    jdbc.update("UPDATE task_plan_occurrences SET task_id=?,status='MATERIALIZED',update_time=? "
                                    + "WHERE plan_id=? AND occurrence_date=? AND task_id IS NULL",
                            taskId, Timestamp.from(now), row.planId(), date.toString());
                }

                Pointer pointer = nextPointer(row, repeat, anchor, date, now);
                return advance(row, pointer, now, status);
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

    private Pointer nextPointer(PlanRow row, TaskRecurrence.RepeatRule repeat, Instant anchor,
                                LocalDate completedDate, Instant now) {
        Map<LocalDate, OccurrenceState> states = new HashMap<>();
        jdbc.query("SELECT occurrence_date,task_id,status,override_payload_json FROM task_plan_occurrences WHERE plan_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> states.put(rs.getDate(1).toLocalDate(),
                        new OccurrenceState(rs.getString(2), rs.getString(3), rs.getString(4))), row.planId());
        LocalDate date = completedDate;
        Pointer selected = null;
        for (int examined = 0; examined < 4096; examined++) {
            date = TaskRecurrence.next(repeat, anchor, TaskRecurrence.occurrenceAt(anchor, date), date);
            if (date == null) break;
            OccurrenceState state = states.get(date);
            if (state != null && (state.taskId() != null
                    || Set.of("SKIPPED", "CLAIMED", "MATERIALIZED").contains(state.status()))) continue;
            Instant at = state != null && "OVERRIDDEN".equals(state.status())
                    ? firstFutureAt(readPayload(requiredOverride(state)), now, anchor, date)
                    : firstFutureAt(shifted(readPayload(row.payloadJson()), date,
                            TaskRecurrence.localDate(anchor), TaskRecurrence.occurrenceId(row.planId(), date)),
                            now, anchor, date);
            selected = new Pointer(at, date);
            break;
        }
        if (selected == null && date != null) throw new IllegalStateException("周期发生超过安全扫描上限");
        for (Map.Entry<LocalDate, OccurrenceState> entry : states.entrySet()) {
            OccurrenceState state = entry.getValue();
            if (!"OVERRIDDEN".equals(state.status()) || state.taskId() != null
                    || !entry.getKey().isAfter(completedDate) || !TaskRecurrence.matches(repeat, entry.getKey())) continue;
            Instant at = firstFutureAt(readPayload(requiredOverride(state)), now, anchor, entry.getKey());
            if (selected == null || at.isBefore(selected.at())
                    || at.equals(selected.at()) && entry.getKey().isBefore(selected.date())) {
                selected = new Pointer(at, entry.getKey());
            }
        }
        return selected == null ? new Pointer(null, null) : selected;
    }

    private Instant firstFutureAt(JsonNode effective, Instant now, Instant anchor, LocalDate date) {
        Instant fallback = TaskRecurrence.occurrenceAt(anchor, date);
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

    private boolean advance(PlanRow row, Pointer pointer, Instant now,
                            TransactionStatus status) {
        int changed = jdbc.update("UPDATE task_plans SET next_at=?,next_date=?,version=version+1,update_time=? "
                        + "WHERE plan_id=? AND status='ENABLED' AND version=? AND next_at=? AND next_date=?",
                pointer.at() == null ? null : Timestamp.from(pointer.at()), pointer.date() == null ? null : pointer.date().toString(),
                Timestamp.from(now), row.planId(), row.version(), Timestamp.from(row.nextAt()), row.nextDate());
        if (changed != 1) status.setRollbackOnly();
        return changed == 1;
    }
    private String requiredOverride(OccurrenceState state) {
        if (state.overridePayloadJson() == null || state.overridePayloadJson().isBlank()) {
            throw new IllegalArgumentException("周期覆盖内容无效");
        }
        return state.overridePayloadJson();
    }

    private JsonNode withPlanIdentity(JsonNode plan, JsonNode override) {
        if (!override.isObject()) throw new IllegalArgumentException("周期覆盖内容无效");
        ObjectNode result = (ObjectNode) override.deepCopy();
        for (String key : List.of("tenantCode", "ownerKey", "source")) {
            if (plan.has(key)) result.set(key, plan.get(key).deepCopy());
        }
        return result;
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
            jdbc.update("INSERT IGNORE INTO task_phases(phase_id,task_id,source,version,status,due_at,payload_json,definition_json,create_time,update_time) VALUES (?,?,?,1,'PENDING',?,?,?,?,?)",
                    phaseId, taskId, source, Timestamp.from(due), body, body, Timestamp.from(now), Timestamp.from(now));
        }
    }

    private JsonNode readPayload(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("周期计划内容为空");
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new IllegalStateException("周期计划内容无效", error); }
    }

    private TaskRecurrence.RepeatRule repeat(String json, LocalDate anchorDate) {
        JsonNode value = readPayload(json);
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

    private Instant effectiveAt(JsonNode payload) {
        String value = payload.path("interval").path("startsAt").asText("");
        if (value.isBlank()) value = payload.path("phases").path(0).path("at").asText("");
        if (value.isBlank()) throw new IllegalArgumentException("周期任务缺少时间锚点");
        return Instant.parse(value);
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

    private record Pointer(Instant at, LocalDate date) { }

    private record PlanRow(String planId, long version, String status, Instant nextAt, String nextDate,
                           String repeatJson, String payloadJson) { }
    private record OverrideRow(String status, String overridePayloadJson, String taskId) { }
    private record OccurrenceState(String taskId, String status, String overridePayloadJson) { }
}
