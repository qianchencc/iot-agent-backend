package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskDetailProjector;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import ltd.qianc.iotagent.task.TaskReadScope;
import ltd.qianc.iotagent.task.TaskRecurrence;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL read model for the public task projections. It ports the Runtime
 * store.list/timeline/calendar/deviceTimeline/deviceCalendar and plans.project
 * queries: rows are tenant-scoped and every device, report block or region is
 * then trimmed against the caller's authorized {@link TaskReadScope}.
 */
public final class JdbcTaskProjectionStore implements TaskProjectionStore {
    private static final int ROW_LIMIT = 500;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TaskReadScope.Provider scopes;
    private final TaskDetailProjector projector;

    public JdbcTaskProjectionStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, (principal, source) -> {
            throw new BusinessException("AUTHORIZED_SCOPE_UNAVAILABLE", "当前设备范围暂不可用", 503);
        });
    }

    public JdbcTaskProjectionStore(JdbcTemplate jdbc, ObjectMapper mapper, TaskReadScope.Provider scopes) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.scopes = scopes;
        this.projector = new TaskDetailProjector(mapper, this::phaseRows);
    }

    // ------------------------------------------------------------------ list

    @Override
    public List<Map<String, Object>> list(PrincipalInfo principal, String source, Instant from, Instant to) {
        String normalized = ltd.qianc.iotagent.iot.IotSourceEnum.parse(source).wireValue();
        TaskReadScope scope = scopes.scope(principal, normalized);
        return summaries(principal.tenantCode(), principal.ownerKey(), normalized, scope, from, to);
    }


    /** Background dashboard composition may prepare its authorized scope before reading local rows. */
    @Override
    public List<Map<String, Object>> listByIdentity(RequestIdentity identity, Instant from, Instant to) {
        TaskReadScope scope = scopes.loadScope(identity);
        return summaries(identity.tenantCode(), identity.ownerKey(), identity.source().wireValue(), scope, from, to);
    }

    private List<Map<String, Object>> summaries(String tenantCode, String viewerKey, String source,
                                                TaskReadScope scope, Instant from, Instant to) {
        Instant windowStart = from == null ? Instant.EPOCH : from;
        Instant windowEnd = to == null ? Instant.parse("9999-12-31T23:59:59Z") : to;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TaskDetailProjector.TaskSource task : windowTasks(tenantCode, source, from, to)) {
            Map<String, Object> detail = projector.detail(task, scope, viewerKey, planMetadata(task));
            if (detail != null) rows.add(projector.listSummary(detail));
        }
        for (TaskDetailProjector.PlanSource plan : planSources(tenantCode, source)) {
            for (LocalDate date : occurrenceDates(plan, windowStart, windowEnd)) {
                Map<String, Object> candidate = projector.virtualOccurrence(plan, date, scope, viewerKey, false);
                if (candidate == null || !inWindow(candidate, windowStart, windowEnd)) continue;
                if (startOf(candidate) == null) continue;
                rows.add(projector.listSummary(candidate));
                if (rows.size() > ROW_LIMIT) throw occurrenceLimit();
            }
        }
        rows.sort((left, right) -> {
            Instant a = startOf(left), b = startOf(right);
            if (a == null || b == null) return 0;
            return a.compareTo(b);
        });
        return rows;
    }

    // -------------------------------------------------------- region views

    @Override
    public List<Map<String, Object>> regionTimeline(PrincipalInfo principal, String source, String regionId, Instant from, Instant to) {
        // Runtime store.timeline is the Office region workbench; Company region reads 404 upstream.
        String normalized = source == null ? "office" : source;
        TaskReadScope scope = scopes.scope(principal, normalized);
        if (!scope.regionIds().contains(regionId)) return List.of();
        Instant windowStart = from == null ? Instant.EPOCH : from;
        Instant windowEnd = to == null ? Instant.parse("9999-12-31T23:59:59Z") : to;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TaskDetailProjector.TaskSource task : tenantTasks(tenantCode(principal), normalized)) {
            if (!overlaps(task, windowStart, windowEnd)) continue;
            Map<String, Object> detail = projector.detail(task, scope, principal.ownerKey(), planMetadata(task));
            if (detail == null || !regionOf(detail, regionId)) continue;
            rows.add(detail);
        }
        for (TaskDetailProjector.PlanSource plan : planSources(tenantCode(principal), normalized)) {
            for (LocalDate date : occurrenceDates(plan, windowStart, windowEnd)) {
                Map<String, Object> candidate = projector.virtualOccurrence(plan, date, scope, principal.ownerKey(), false);
                if (candidate == null || !inWindow(candidate, windowStart, windowEnd)) continue;
                if (!regionOf(candidate, regionId)) continue;
                rows.add(candidate);
                if (rows.size() > ROW_LIMIT) throw occurrenceLimit();
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> regionCalendar(PrincipalInfo principal, String source, String regionId, Instant from, Instant to) {
        return calendar(regionTimeline(principal, source, regionId, from, to), from, to);
    }

    // -------------------------------------------------------- device views

    @Override
    public List<Map<String, Object>> deviceTimeline(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to) {
        TaskReadScope scope = scopes.scope(principal, source);
        if (scope.device(deviceId).isEmpty()) return List.of();
        Instant windowStart = from == null ? Instant.EPOCH : from;
        Instant windowEnd = to == null ? Instant.parse("9999-12-31T23:59:59Z") : to;
        List<Map<String, Object>> events = new ArrayList<>();
        for (TaskDetailProjector.TaskSource task : tenantTasks(tenantCode(principal), source)) {
            List<TaskDetailProjector.PhaseRow> rows = new ArrayList<>();
            boolean touches = false;
            for (TaskDetailProjector.PhaseRow row : phaseRows(task.taskId())) {
                boolean hasDevice = projector.deviceRows(row).stream()
                        .anyMatch(device -> device.id().equals(deviceId));
                if (hasDevice) {
                    rows.add(row);
                    touches = true;
                }
            }
            if (!touches || !spans(rows, task, windowStart, windowEnd)) continue;
            Map<String, Object> metadata = planMetadata(task);
            for (TaskDetailProjector.PhaseRow row : rows) {
                for (TaskDetailProjector.DeviceRow device : projector.deviceRows(row)) {
                    if (!device.id().equals(deviceId)) continue;
                    Map<String, Object> event = new LinkedHashMap<>(metadata);
                    event.put("taskId", task.taskId());
                    event.put("taskLabel", detailLabel(task));
                    event.put("taskStatus", TaskDetailProjector.lowerStatus(task.status()));
                    event.put("sourceType", TaskDetailProjector.sourceTypeOf(task.payload()));
                    event.put("creatorName", task.payload().path("creatorName").asText(""));
                    event.put("interval", publicInterval(task));
                    event.put("executionActive", "EXECUTING".equalsIgnoreCase(row.status()));
                    event.put("phaseId", row.phaseId());
                    event.put("at", row.at().toString());
                    event.put("phaseStatus", TaskDetailProjector.lowerStatus(row.status()));
                    event.put("device", Map.of("id", deviceId, "kind", device.kind(), "name", device.name()));
                    event.put("target", device.target() == null ? Map.of() : publicValue(device.target()));
                    event.put("outcome", device.outcome());
                    event.put("details", device.details());
                    events.add(event);
                }
            }
        }
        for (TaskDetailProjector.PlanSource plan : planSources(tenantCode(principal), source)) {
            for (LocalDate date : occurrenceDates(plan, windowStart, windowEnd)) {
                Map<String, Object> candidate = projector.virtualOccurrence(plan, date, scope, principal.ownerKey(), false);
                if (candidate == null || !inWindow(candidate, windowStart, windowEnd)) continue;
                for (Object phaseObject : (List<?>) candidate.get("phases")) {
                    if (!(phaseObject instanceof Map<?, ?> phase)) continue;
                    for (Object deviceObject : (List<?>) phase.get("devices")) {
                        if (!(deviceObject instanceof Map<?, ?> device) || !deviceId.equals(device.get("id"))) continue;
                        Map<String, Object> event = new LinkedHashMap<>();
                        event.put("taskId", candidate.get("id"));
                        event.put("taskLabel", candidate.get("label"));
                        event.put("taskStatus", candidate.get("status"));
                        event.put("sourceType", "recurring_plan");
                        event.put("creatorName", ((Map<?, ?>) candidate.get("creator")).get("displayName"));
                        event.put("interval", candidate.get("interval"));
                        if (candidate.get("occurrence") != null) event.put("occurrence", candidate.get("occurrence"));
                        if (candidate.get("repeat") != null) event.put("repeat", candidate.get("repeat"));
                        if (candidate.get("nextAt") != null) event.put("nextAt", candidate.get("nextAt"));
                        event.put("executionActive", false);
                        event.put("phaseId", phase.get("id"));
                        event.put("at", phase.get("at"));
                        event.put("phaseStatus", phase.get("status"));
                        event.put("device", Map.of("id", deviceId, "kind", device.get("kind"), "name", device.get("name")));
                        event.put("target", device.get("target"));
                        event.put("outcome", device.get("outcome"));
                        event.put("details", device.get("details"));
                        events.add(event);
                    }
                }
            }
        }
        return events;
    }

    @Override
    public List<Map<String, Object>> deviceCalendar(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to) {
        TaskReadScope scope = scopes.scope(principal, source);
        if (scope.device(deviceId).isEmpty()) return List.of();
        Instant windowStart = from == null ? Instant.EPOCH : from;
        Instant windowEnd = to == null ? Instant.parse("9999-12-31T23:59:59Z") : to;
        Map<LocalDate, Boolean> days = new LinkedHashMap<>();
        for (TaskDetailProjector.TaskSource task : tenantTasks(tenantCode(principal), source)) {
            List<TaskDetailProjector.PhaseRow> rows = new ArrayList<>();
            boolean abnormal = false;
            boolean touches = false;
            for (TaskDetailProjector.PhaseRow row : phaseRows(task.taskId())) {
                boolean hasDevice = false;
                for (TaskDetailProjector.DeviceRow device : projector.deviceRows(row)) {
                    if (!device.id().equals(deviceId)) continue;
                    hasDevice = true;
                    if (deviceAbnormal(device, row)) abnormal = true;
                }
                if (hasDevice) {
                    rows.add(row);
                    touches = true;
                }
            }
            if (!touches || !spans(rows, task, windowStart, windowEnd)) continue;
            markDays(days, rows, task, windowStart, windowEnd, abnormal);
        }
        for (TaskDetailProjector.PlanSource plan : planSources(tenantCode(principal), source)) {
            for (LocalDate date : occurrenceDates(plan, windowStart, windowEnd)) {
                Map<String, Object> candidate = projector.virtualOccurrence(plan, date, scope, principal.ownerKey(), false);
                if (candidate == null || !inWindow(candidate, windowStart, windowEnd)) continue;
                boolean touches = false;
                for (Object phaseObject : (List<?>) candidate.get("phases")) {
                    if (!(phaseObject instanceof Map<?, ?> phase)) continue;
                    for (Object deviceObject : (List<?>) phase.get("devices")) {
                        if (deviceObject instanceof Map<?, ?> device && deviceId.equals(device.get("id"))) {
                            touches = true;
                            break;
                        }
                    }
                }
                if (touches) days.putIfAbsent(date, false);
            }
        }
        return calendarDays(days);
    }

    // ---------------------------------------------------- region attention

    /**
     * Runtime store.regionAttention over durable office facts: one tenant scan
     * for exceptional candidates, then watermark, region membership and current
     * device authorization per authorized region. {@code seenByRegion} carries
     * only existing watermarks, so an absent region counts every exceptional
     * task exactly like the original never-seen comparison.
     */
    @Override
    public Set<String> regionAttention(PrincipalInfo principal, Map<String, Instant> seenByRegion) {
        TaskReadScope scope = scopes.scope(principal, "office");
        Set<String> authorizedRegions = scope.regionIds();
        if (authorizedRegions.isEmpty()) return Set.of();
        Map<String, Instant> watermarks = new LinkedHashMap<>();
        boolean neverSeen = false;
        for (String region : authorizedRegions) {
            Instant seen = seenByRegion == null ? null : seenByRegion.get(region);
            if (seen == null) neverSeen = true;
            else watermarks.put(region, seen);
        }
        Instant earliest = neverSeen ? null
                : watermarks.values().stream().min(Instant::compareTo).orElse(null);
        List<TaskDetailProjector.TaskSource> candidates = tenantTasks(principal.tenantCode(), "office", earliest);
        Set<String> unread = new LinkedHashSet<>();
        for (TaskDetailProjector.TaskSource task : candidates) {
            if (unread.size() == authorizedRegions.size()) break;
            if (task.updatedAt() == null || !TaskDetailProjector.exceptionalStatus(task.status())) continue;
            List<String> pending = new ArrayList<>();
            for (String region : authorizedRegions) {
                if (unread.contains(region) || !touchesRegion(task.payload(), region)) continue;
                Instant seen = watermarks.get(region);
                if (seen == null || task.updatedAt().isAfter(seen)) pending.add(region);
            }
            if (pending.isEmpty()) continue;
            boolean authorizedDevice = false;
            for (TaskDetailProjector.PhaseRow row : phaseRows(task.taskId())) {
                for (TaskDetailProjector.DeviceRow device : projector.deviceRows(row)) {
                    if (scope.device(device.id()).isPresent()) {
                        authorizedDevice = true;
                        break;
                    }
                }
                if (authorizedDevice) break;
            }
            if (authorizedDevice) unread.addAll(pending);
        }
        return Set.copyOf(unread);
    }


    /** Raw payload region membership, mirroring the Runtime region_ids containment check. */
    private static boolean touchesRegion(JsonNode payload, String regionId) {
        JsonNode regions = payload.path("regionIds");
        if (!regions.isArray()) return false;
        for (JsonNode region : regions) {
            if (region.isTextual() && regionId.equals(region.asText())) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- SQL

    private List<TaskDetailProjector.TaskSource> windowTasks(String tenantCode, String source, Instant from, Instant to) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String sql = "SELECT DISTINCT t.task_id FROM task_facts t JOIN task_phases p ON p.task_id=t.task_id "
                + "WHERE JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'$.tenantCode'))=? "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(t.payload_json,'$.source'))=? "
                + (from == null ? "" : "AND p.due_at>=? ")
                + (to == null ? "" : "AND p.due_at<? ")
                + "LIMIT 501";
        Object[] parameters = new Object[2 + (from == null ? 0 : 1) + (to == null ? 0 : 1)];
        parameters[0] = tenantCode;
        parameters[1] = source;
        int next = 2;
        if (from != null) parameters[next++] = Timestamp.from(from);
        if (to != null) parameters[next] = Timestamp.from(to);
        jdbc.query(sql, (rs, row) -> ids.add(rs.getString(1)), parameters);
        if (ids.size() > ROW_LIMIT) throw occurrenceLimit();
        List<TaskDetailProjector.TaskSource> tasks = tenantTasks(tenantCode, source);
        for (TaskDetailProjector.TaskSource task : tasks) {
            JsonNode interval = projector.interval(task.payload());
            if (interval == null) continue;
            try {
                Instant startsAt = Instant.parse(interval.path("startsAt").asText());
                Instant endsAt = Instant.parse(interval.path("endsAt").asText());
                if ((to == null || startsAt.isBefore(to)) && (from == null || !endsAt.isBefore(from))) {
                    ids.add(task.taskId());
                }
            } catch (RuntimeException ignored) {
                continue;
            }
            if (ids.size() > ROW_LIMIT) throw occurrenceLimit();
        }
        Map<String, TaskDetailProjector.TaskSource> byId = new LinkedHashMap<>();
        for (TaskDetailProjector.TaskSource task : tasks) {
            byId.put(task.taskId(), task);
        }
        List<TaskDetailProjector.TaskSource> result = new ArrayList<>();
        for (String id : ids) {
            TaskDetailProjector.TaskSource task = byId.get(id);
            if (task != null) result.add(task);
        }
        return result;
    }

    private List<TaskDetailProjector.TaskSource> tenantTasks(String tenantCode, String source) {
        return tenantTasks(tenantCode, source, null);
    }

    private List<TaskDetailProjector.TaskSource> tenantTasks(String tenantCode, String source, Instant updatedAfter) {
        String sql = "SELECT task_id,version,status,payload_json,create_time,update_time FROM task_facts "
                + "WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.tenantCode'))=? "
                + "AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.source'))=?"
                + (updatedAfter == null ? "" : " AND update_time>?")
                + " ORDER BY create_time,task_id";
        Object[] parameters = updatedAfter == null
                ? new Object[]{tenantCode, source}
                : new Object[]{tenantCode, source, Timestamp.from(updatedAfter)};
        return jdbc.query(sql,
                (rs, row) -> new TaskDetailProjector.TaskSource(rs.getString(1), rs.getLong(2), rs.getString(3),
                        read(rs.getString(4)), toInstant(rs.getTimestamp(5)), toInstant(rs.getTimestamp(6))),
                parameters);
    }

    private List<TaskDetailProjector.PhaseRow> phaseRows(String taskId) {
        return jdbc.query("SELECT phase_id,due_at,status,COALESCE(definition_json,payload_json),"
                        + "COALESCE(result_json,'{}'),claimed_at,ended_at,failure_reason FROM task_phases "
                        + "WHERE task_id=? ORDER BY due_at,id",
                (rs, row) -> new TaskDetailProjector.PhaseRow(rs.getString(1), rs.getTimestamp(2).toInstant(),
                        rs.getString(3), read(rs.getString(4)), read(rs.getString(5)),
                        toInstant(rs.getTimestamp(6)), toInstant(rs.getTimestamp(7)), rs.getString(8)), taskId);
    }

    private List<TaskDetailProjector.PlanSource> planSources(String tenantCode, String source) {
        List<TaskDetailProjector.PlanSource> plans = jdbc.query(
                "SELECT plan_id,version,status,next_at,next_date,payload_json,create_time,update_time FROM task_plans "
                        + "WHERE tenant_code=? AND source=? AND status='ENABLED' AND next_date IS NOT NULL LIMIT 501",
                (rs, row) -> new TaskDetailProjector.PlanSource(rs.getString(1), rs.getLong(2), rs.getString(3),
                        read(rs.getString(6)), toInstant(rs.getTimestamp(4)),
                        rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(),
                        toInstant(rs.getTimestamp(7)), toInstant(rs.getTimestamp(8)), null),
                tenantCode, source);
        if (plans.size() > ROW_LIMIT) throw occurrenceLimit();
        List<TaskDetailProjector.PlanSource> result = new ArrayList<>();
        for (TaskDetailProjector.PlanSource plan : plans) {
            result.add(new TaskDetailProjector.PlanSource(plan.planId(), plan.version(), plan.status(), plan.payload(),
                    plan.nextAt(), plan.nextDate(), plan.createdAt(), plan.updatedAt(), occurrenceMap(plan.planId())));
        }
        return result;
    }

    private Map<LocalDate, TaskDetailProjector.OccurrenceState> occurrenceMap(String planId) {
        Map<LocalDate, TaskDetailProjector.OccurrenceState> occurrences = new LinkedHashMap<>();
        jdbc.query("SELECT occurrence_date,task_id,status,override_payload_json FROM task_plan_occurrences WHERE plan_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> occurrences.put(rs.getDate(1).toLocalDate(),
                        new TaskDetailProjector.OccurrenceState(rs.getDate(1).toLocalDate(), rs.getString(2),
                                rs.getString(3), rs.getString(4) == null ? null : read(rs.getString(4)))),
                planId);
        return occurrences;
    }

    /** Runtime plans.project date walk: candidate occurrence dates whose span may reach the window. */
    private List<LocalDate> occurrenceDates(TaskDetailProjector.PlanSource plan, Instant from, Instant to) {
        Instant anchor = projector.anchor(plan.payload());
        if (anchor == null) return List.of();
        LocalDate anchorDate = TaskRecurrence.localDate(anchor);
        JsonNode interval = projector.interval(plan.payload());
        Instant occurrenceEnd = interval != null && interval.path("endsAt").isTextual()
                ? parseOr(interval.path("endsAt").asText(), null) : null;
        long spanDays = occurrenceEnd == null ? 0
                : Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(anchorDate, TaskRecurrence.localDate(occurrenceEnd)));
        LocalDate earliest = TaskRecurrence.localDate(from).minusDays(spanDays);
        if (earliest.isBefore(anchorDate)) earliest = anchorDate;
        LocalDate latest = TaskRecurrence.localDate(to);
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
        for (LocalDate date = earliest; !date.isAfter(latest) && dates.size() < ROW_LIMIT; date = date.plusDays(1)) {
            dates.add(date);
        }
        for (TaskDetailProjector.OccurrenceState state : plan.occurrences().values()) {
            if ("OVERRIDDEN".equalsIgnoreCase(state.status()) && state.taskId() == null) dates.add(state.date());
        }
        return List.copyOf(dates);
    }

    private Map<String, Object> planMetadata(TaskDetailProjector.TaskSource task) {
        String planId = task.payload().path("sourcePlanId").asText("");
        if (planId.isBlank()) return Map.of();
        Map<String, Object> metadata = new LinkedHashMap<>();
        jdbc.query("SELECT repeat_json,payload_json,next_at,next_date FROM task_plans WHERE plan_id=? LIMIT 1",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    JsonNode repeat = read(rs.getString(1)).path("repeat");
                    if (!repeat.isObject()) repeat = read(rs.getString(2)).path("repeat");
                    if (repeat.isObject()) metadata.put("repeat", publicValue(repeat));
                    Timestamp nextAt = rs.getTimestamp(3);
                    if (nextAt != null) metadata.put("nextAt", nextAt.toInstant().toString());
                }, planId);
        String occurrenceDate = task.payload().path("occurrenceDate").asText("");
        if (!occurrenceDate.isBlank()) {
            metadata.put("occurrence", Map.of("planId", planId, "date", occurrenceDate, "virtual", false));
        }
        return metadata;
    }

    // ------------------------------------------------------------- helpers

    private List<Map<String, Object>> calendar(List<Map<String, Object>> events, Instant from, Instant to) {
        LocalDate first = from == null ? null : from.atZone(TaskDetailProjector.ZONE).toLocalDate();
        LocalDate last = to == null ? null : to.atZone(TaskDetailProjector.ZONE).toLocalDate().minusDays(1);
        Map<LocalDate, Boolean> days = new LinkedHashMap<>();
        for (Map<String, Object> event : events) {
            boolean abnormal = TaskDetailProjector.abnormal(event);
            LocalDate start = localDateOf(startOf(event));
            LocalDate end = localDateOf(endOf(event));
            if (start == null) continue;
            if (end == null) end = start;
            LocalDate fromDay = first == null || start.isAfter(first) ? start : first;
            LocalDate toDay = last == null || end.isBefore(last) ? end : last;
            for (LocalDate date = fromDay; !date.isAfter(toDay); date = date.plusDays(1)) {
                days.merge(date, abnormal, Boolean::logicalOr);
            }
        }
        return calendarDays(days);
    }

    private List<Map<String, Object>> calendarDays(Map<LocalDate, Boolean> days) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocalDate date : days.keySet().stream().sorted().toList()) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", date.toString());
            day.put("abnormal", Boolean.TRUE.equals(days.get(date)));
            result.add(day);
        }
        return result;
    }

    private static void markDays(Map<LocalDate, Boolean> days, List<TaskDetailProjector.PhaseRow> rows,
                                 TaskDetailProjector.TaskSource task, Instant from, Instant to, boolean abnormal) {
        JsonNode interval = projectorInterval(task);
        Instant start = null;
        Instant end = null;
        if (interval != null) {
            start = parseOr(interval.path("startsAt").asText(""), null);
            end = parseOr(interval.path("endsAt").asText(""), null);
        }
        if (start == null) start = rows.stream().map(TaskDetailProjector.PhaseRow::at).min(Instant::compareTo).orElse(null);
        if (end == null) end = rows.stream().map(TaskDetailProjector.PhaseRow::at).max(Instant::compareTo).orElse(null);
        if (start == null || end == null) return;
        LocalDate fromDay = TaskRecurrence.localDate(from);
        LocalDate toDay = TaskRecurrence.localDate(to).minusDays(1);
        LocalDate first = TaskRecurrence.localDate(start);
        LocalDate last = TaskRecurrence.localDate(end);
        for (LocalDate date = first.isBefore(fromDay) ? fromDay : first;
             !date.isAfter(last.isAfter(toDay) ? toDay : last); date = date.plusDays(1)) {
            days.merge(date, abnormal, Boolean::logicalOr);
        }
    }

    private static boolean deviceAbnormal(TaskDetailProjector.DeviceRow device, TaskDetailProjector.PhaseRow row) {
        String outcome = device.outcome();
        if (Set.of("verified", "satisfied", "pending").contains(outcome)) return false;
        Map<String, Object> details = device.details();
        if ("unconfirmed".equals(outcome)
                && "ACCEPTED".equals(String.valueOf(details.get("receipt")))
                && !Boolean.TRUE.equals(details.get("timedOut"))) {
            return false;
        }
        return !"not_dispatched".equals(outcome) || !"CANCELLED".equalsIgnoreCase(row.status());
    }

    private static boolean spans(List<TaskDetailProjector.PhaseRow> rows, TaskDetailProjector.TaskSource task,
                                 Instant from, Instant to) {
        JsonNode interval = projectorInterval(task);
        if (interval != null) {
            Instant startsAt = parseOr(interval.path("startsAt").asText(""), null);
            Instant endsAt = parseOr(interval.path("endsAt").asText(""), null);
            if (startsAt != null && endsAt != null) return startsAt.isBefore(to) && !endsAt.isBefore(from);
        }
        Instant first = rows.stream().map(TaskDetailProjector.PhaseRow::at).min(Instant::compareTo).orElse(null);
        Instant last = rows.stream().map(TaskDetailProjector.PhaseRow::at).max(Instant::compareTo).orElse(null);
        return first != null && last != null && first.isBefore(to) && !last.isBefore(from);
    }

    private static boolean overlaps(TaskDetailProjector.TaskSource task, Instant from, Instant to) {
        JsonNode interval = projectorInterval(task);
        if (interval != null) {
            Instant startsAt = parseOr(interval.path("startsAt").asText(""), null);
            Instant endsAt = parseOr(interval.path("endsAt").asText(""), null);
            if (startsAt != null && endsAt != null) return startsAt.isBefore(to) && !endsAt.isBefore(from);
        }
        return true;
    }

    private static JsonNode projectorInterval(TaskDetailProjector.TaskSource task) {
        JsonNode interval = task.payload().path("interval");
        return interval.isObject() && interval.hasNonNull("startsAt") && interval.hasNonNull("endsAt")
                ? interval : null;
    }

    private static Instant startOf(Map<String, Object> view) {
        Object interval = view.get("interval");
        if (interval instanceof Map<?, ?> span && span.get("startsAt") != null) {
            return parseOr(String.valueOf(span.get("startsAt")), null);
        }
        Object phases = view.get("phases");
        if (phases instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> phase
                && phase.get("at") != null) {
            return parseOr(String.valueOf(phase.get("at")), null);
        }
        return parseOr(String.valueOf(view.get("at")), null);
    }

    private static Instant endOf(Map<String, Object> view) {
        Object interval = view.get("interval");
        if (interval instanceof Map<?, ?> span && span.get("endsAt") != null) {
            return parseOr(String.valueOf(span.get("endsAt")), null);
        }
        Object phases = view.get("phases");
        if (phases instanceof List<?> rows && !rows.isEmpty()
                && rows.get(rows.size() - 1) instanceof Map<?, ?> phase && phase.get("at") != null) {
            return parseOr(String.valueOf(phase.get("at")), null);
        }
        return startOf(view);
    }

    private static boolean inWindow(Map<String, Object> view, Instant from, Instant to) {
        Instant start = startOf(view);
        Instant end = endOf(view);
        return start != null && end != null && start.isBefore(to) && !end.isBefore(from);
    }

    private static boolean regionOf(Map<String, Object> view, String regionId) {
        return view.get("regionIds") instanceof List<?> regions
                && regions.stream().anyMatch(region -> regionId.equals(region));
    }

    private static LocalDate localDateOf(Instant value) {
        return value == null ? null : TaskRecurrence.localDate(value);
    }

    private static Instant parseOr(String value, Instant fallback) {
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { return fallback; }
    }

    private static String detailLabel(TaskDetailProjector.TaskSource task) {
        for (String field : new String[]{"label", "title", "objective"}) {
            String value = task.payload().path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static boolean hasSourcePlanId(TaskDetailProjector.TaskSource task) {
        return !task.payload().path("sourcePlanId").asText("").isBlank();
    }

    private Object publicInterval(TaskDetailProjector.TaskSource task) {
        JsonNode interval = projectorInterval(task);
        return interval == null ? null : publicValue(interval);
    }

    private static Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String tenantCode(PrincipalInfo principal) {
        return principal.tenantCode();
    }

    private static BusinessException occurrenceLimit() {
        return new BusinessException("OCCURRENCE_QUERY_LIMIT", "周期安排过多，请缩小查询范围", 422);
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) return mapper.createObjectNode();
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new IllegalStateException("任务投影内容无效", error); }
    }

    private Object publicValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        JsonNode safe = TaskPayloadSafety.withoutCredentials(mapper, value);
        return mapper.convertValue(safe, Object.class);
    }
}
