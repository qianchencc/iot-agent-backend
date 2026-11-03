package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Restart-safe read model derived from MySQL task facts, durable phase results
 * and persisted report facts. It never trusts a process-local projection and
 * strips credentials before returning data.
 */
public final class JdbcTaskProjectionStore implements TaskProjectionStore {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Instant MIN = Instant.EPOCH;
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59Z");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcTaskProjectionStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public List<Map<String, Object>> list(PrincipalInfo principal, String source, Instant from, Instant to) {
        return listByOwner(principal.tenantCode(), principal.ownerKey(), source, from, to);
    }

    @Override public List<Map<String, Object>> listByOwner(String tenantCode, String ownerKey, String source, Instant from, Instant to) {
        Instant start = from == null ? MIN : from;
        Instant end = to == null ? MAX : to;
        return facts(tenantCode, ownerKey, source).stream()
                .map(row -> summary(row, start, end))
                .filter(row -> row != null)
                .toList();
    }

    @Override public List<Map<String, Object>> regionTimeline(PrincipalInfo principal, String source, String regionId, Instant from, Instant to) {
        return list(principal, source, from, to).stream().filter(row -> hasRegion(row, regionId)).toList();
    }

    @Override public List<Map<String, Object>> regionCalendar(PrincipalInfo principal, String source, String regionId, Instant from, Instant to) {
        return calendar(regionTimeline(principal, source, regionId, from, to), from, to);
    }

    @Override public List<Map<String, Object>> deviceTimeline(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to) {
        Instant start = from == null ? MIN : from;
        Instant end = to == null ? MAX : to;
        List<Map<String, Object>> result = new ArrayList<>();
        for (FactRow row : facts(principal.tenantCode(), principal.ownerKey(), source)) {
            for (PhaseRow phase : phases(row.taskId)) {
                if (phase.at().isBefore(start) || phase.at().isAfter(end)) continue;
                for (Map<String, Object> device : phase.devices()) {
                    if (!deviceId.equals(String.valueOf(device.get("id")))) continue;
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("taskId", row.taskId);
                    event.put("taskStatus", row.status);
                    event.put("phaseId", phase.id());
                    event.put("at", phase.at().toString());
                    event.put("status", phase.status());
                    event.put("deviceId", deviceId);
                    event.put("device", device);
                    if (phase.result().path("energyEstimate").isObject()) {
                        event.put("energyEstimate", mapper.convertValue(publicPayload(phase.result().path("energyEstimate")), Object.class));
                    }
                    result.add(event);
                }
            }
        }
        return result;
    }

    @Override public List<Map<String, Object>> deviceCalendar(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to) {
        return calendar(deviceTimeline(principal, source, deviceId, from, to), from, to);
    }

    private List<FactRow> facts(String tenantCode, String ownerKey, String source) {
        List<FactRow> result = new ArrayList<>();
        for (FactRow row : jdbc.query("SELECT task_id,version,status,payload_json FROM task_facts "
                        + "WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.tenantCode'))=? "
                        + "AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.ownerKey'))=? "
                        + "AND JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.source'))=? ORDER BY update_time DESC",
                (rs, n) -> new FactRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                tenantCode, ownerKey, source)) {
            JsonNode payload;
            try { payload = mapper.readTree(row.payloadJson()); }
            catch (Exception invalid) { throw new IllegalStateException("任务事实内容无效", invalid); }
            if (!tenantCode.equals(payload.path("tenantCode").asText())
                    || !ownerKey.equals(payload.path("ownerKey").asText())
                    || !source.equals(payload.path("source").asText("office"))) continue;
            result.add(new FactRow(row.taskId(), row.version(), row.status(), row.payloadJson(), payload));
        }
        return result;
    }

    private Map<String, Object> summary(FactRow row, Instant from, Instant to) {
        List<PhaseRow> phases = phases(row.taskId()).stream()
                .filter(phase -> !phase.at().isBefore(from) && !phase.at().isAfter(to))
                .toList();
        if (phases.isEmpty() && !row.payload().path("reportRequest").isObject()) return null;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", row.taskId());
        view.put("version", row.version());
        view.put("status", row.status());
        row.payload().fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!Set.of("tenantCode", "ownerKey", "phases", "report").contains(key)) {
                view.putIfAbsent(key, mapper.convertValue(publicPayload(entry.getValue()), Object.class));
            }
        });
        List<Map<String, Object>> phaseViews = new ArrayList<>();
        boolean exception = false;
        Instant firstAt = null;
        for (PhaseRow phase : phases) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", phase.id());
            item.put("phaseId", phase.id());
            item.put("at", phase.at().toString());
            item.put("status", phase.status());
            item.put("startedAt", phase.startedAt() == null ? null : phase.startedAt().toString());
            item.put("endedAt", phase.endedAt() == null ? null : phase.endedAt().toString());
            item.put("failureReason", phase.failureReason());
            item.put("outcome", phase.result().path("outcome").asText(phase.status()));
            item.put("devices", phase.devices());
            if (phase.result().path("observations").isArray()) {
                item.put("observations", mapper.convertValue(publicPayload(phase.result().path("observations")), Object.class));
            }
            if (phase.result().path("energyEstimate").isObject()) {
                item.put("energyEstimate", mapper.convertValue(publicPayload(phase.result().path("energyEstimate")), Object.class));
            }
            boolean phaseException = Set.of("PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "MISSED")
                    .contains(phase.status());
            item.put("hasException", phaseException);
            exception |= phaseException;
            phaseViews.add(item);
            if (firstAt == null || phase.at().isBefore(firstAt)) firstAt = phase.at();
        }
        view.put("at", firstAt == null ? null : firstAt.toString());
        view.put("phases", phaseViews);
        view.put("hasException", exception);
        view.put("exception", exception);
        JsonNode report = report(row.taskId(), row.payload());
        view.put("report", report == null ? null : mapper.convertValue(publicPayload(report), Object.class));
        view.put("reportId", report == null ? null : report.path("id").asText(null));
        view.put("hasReport", report != null);
        view.put("reportAt", report == null ? null : report.path("generatedAt").asText(null));
        view.put("payload", mapper.convertValue(publicPayload(row.payload()), Object.class));
        return view;
    }

    private JsonNode report(String taskId, JsonNode payload) {
        JsonNode embedded = payload.path("report");
        if (embedded.isObject()) return embedded;
        String reportId = payload.path("reportId").asText("");
        try {
            var rows = reportId.isBlank()
                    ? jdbc.query("SELECT payload_json FROM report_facts WHERE "
                            + "JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.taskId'))=? ORDER BY update_time DESC LIMIT 1",
                            (rs, n) -> rs.getString(1), taskId)
                    : jdbc.query("SELECT payload_json FROM report_facts WHERE report_id=? LIMIT 1",
                            (rs, n) -> rs.getString(1), reportId);
            if (rows.isEmpty()) return null;
            JsonNode stored;
            try { stored = mapper.readTree(rows.get(0)); }
            catch (Exception invalid) { throw new IllegalStateException("报表事实内容无效", invalid); }
            return stored.path("report").isObject() ? stored.path("report") : stored;
        } catch (IllegalStateException invalid) {
            throw invalid;
        }
    }

    private List<PhaseRow> phases(String taskId) {
        return jdbc.query("SELECT phase_id,due_at,status,COALESCE(definition_json,payload_json),"
                        + "COALESCE(result_json,'{}'),claimed_at,ended_at,failure_reason FROM task_phases "
                        + "WHERE task_id=? ORDER BY due_at,id",
                (rs, n) -> {
                    JsonNode definition = read(rs.getString(4));
                    JsonNode result = read(rs.getString(5));
                    return new PhaseRow(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                            definition, result,
                            rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                            rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                            rs.getString(8), devices(definition, result));
                }, taskId);
    }

    private List<Map<String, Object>> devices(JsonNode definition, JsonNode result) {
        List<Map<String, Object>> devices = new ArrayList<>();
        JsonNode resultDevices = result.path("devices");
        if (resultDevices.isObject()) {
            resultDevices.fields().forEachRemaining(entry -> {
                Map<String, Object> view = new LinkedHashMap<>();
                JsonNode value = entry.getValue();
                view.put("id", value.path("deviceId").asText(entry.getKey()));
                view.put("kind", value.path("kind").asText(""));
                view.put("name", value.path("name").asText(entry.getKey()));
                view.put("location", value.path("location").asText(""));
                view.put("outcome", value.path("outcome").asText(""));
                if (value.has("reason")) view.put("reason", value.path("reason").asText());
                if (value.has("observed")) view.put("observed", mapper.convertValue(publicPayload(value.path("observed")), Object.class));
                if (value.has("afterSetpoint")) view.put("afterSetpoint", value.path("afterSetpoint").asDouble());
                devices.add(view);
            });
            return devices;
        }
        JsonNode actions = definition.path("actions");
        for (JsonNode action : actions) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", action.path("deviceId").asText());
            view.put("kind", action.path("kind").asText(""));
            view.put("name", action.path("deviceName").asText(action.path("name").asText(action.path("deviceId").asText())));
            view.put("location", action.path("location").asText(""));
            view.put("outcome", "pending");
            devices.add(view);
        }
        for (JsonNode device : definition.path("read").path("devices")) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", device.path("id").asText());
            view.put("kind", device.path("kind").asText(""));
            view.put("name", device.path("name").asText(device.path("id").asText()));
            view.put("location", device.path("location").asText(""));
            view.put("outcome", "pending");
            devices.add(view);
        }
        return devices;
    }

    private boolean hasRegion(Map<String, Object> view, String regionId) {
        Object payload = view.get("payload");
        return payload instanceof Map<?, ?> map && contains(map.get("regionIds"), regionId);
    }

    private List<Map<String, Object>> calendar(List<Map<String, Object>> events, Instant from, Instant to) {
        Instant start = from == null ? MIN : from;
        Instant end = to == null ? MAX : to;
        LocalDate first = start.atZone(SHANGHAI).toLocalDate();
        LocalDate last = end.atZone(SHANGHAI).toLocalDate();
        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            final LocalDate day = date;
            List<Map<String, Object>> daily = events.stream()
                    .filter(event -> calendarDates(event).contains(day))
                    .toList();
            if (daily.isEmpty()) continue;
            boolean exception = daily.stream().anyMatch(event -> {
                if (Boolean.TRUE.equals(event.get("hasException"))) return true;
                if (Set.of("PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "MISSED")
                        .contains(String.valueOf(event.get("status")))) return true;
                Object phases = event.get("phases");
                return phases instanceof List<?> values && values.stream().anyMatch(value -> value instanceof Map<?, ?> phase
                        && (Boolean.TRUE.equals(phase.get("hasException"))
                        || Set.of("PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "MISSED")
                        .contains(String.valueOf(phase.get("status")))));
            });
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("date", date.toString());
            view.put("count", daily.size());
            view.put("hasException", exception);
            view.put("exception", exception);
            days.add(view);
        }
        return days;
    }

    private static List<LocalDate> calendarDates(Map<String, Object> event) {
        List<LocalDate> result = new ArrayList<>();
        Object phases = event.get("phases");
        if (phases instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> phase) {
                    LocalDate date = localDate(String.valueOf(phase.get("at")));
                    if (date != null) result.add(date);
                }
            }
        }
        if (result.isEmpty()) {
            LocalDate date = localDate(String.valueOf(event.get("at")));
            if (date != null) result.add(date);
        }
        return result;
    }

    private static LocalDate localDate(String value) {
        try { return Instant.parse(value).atZone(SHANGHAI).toLocalDate(); }
        catch (RuntimeException ignored) { return null; }
    }

    private static boolean contains(Object value, String expected) {
        return value instanceof List<?> list && list.stream().anyMatch(item -> expected.equals(String.valueOf(item)));
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) return mapper.createObjectNode();
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new IllegalStateException("任务阶段内容无效", error); }
    }

    private JsonNode publicPayload(JsonNode value) {
        JsonNode safe = TaskPayloadSafety.withoutCredentials(mapper, value);
        if (!safe.isObject()) return safe;
        ObjectNode result = (ObjectNode) safe;
        result.remove("tenantCode");
        result.remove("ownerKey");
        return result;
    }

    private record FactRow(String taskId, long version, String status, String payloadJson, JsonNode payload) {
        FactRow(String taskId, long version, String status, String payloadJson) {
            this(taskId, version, status, payloadJson, null);
        }
    }

    private record PhaseRow(String id, Instant at, String status, JsonNode definition, JsonNode result,
                            Instant startedAt, Instant endedAt, String failureReason,
                            List<Map<String, Object>> devices) { }
}
