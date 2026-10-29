package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Restart-safe read model derived from MySQL task facts and phases. It never
 * trusts a process-local projection and strips credentials before returning data.
 */
public final class JdbcTaskProjectionStore implements TaskProjectionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcTaskProjectionStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override public List<Map<String, Object>> list(PrincipalInfo principal, String source, Instant from, Instant to) {
        return facts(principal, source).stream().filter(row -> overlaps(row.payload, from, to)).map(row -> summary(row, from, to)).toList();
    }

    @Override public List<Map<String, Object>> regionTimeline(PrincipalInfo principal, String source, String regionId, Instant from, Instant to) {
        return list(principal, source, from, to).stream().filter(row -> hasRegion(row, regionId)).toList();
    }

    @Override public List<Map<String, Object>> regionCalendar(PrincipalInfo principal, String source, String regionId, Instant from, Instant to) {
        return calendar(regionTimeline(principal, source, regionId, from, to), from, to);
    }

    @Override public List<Map<String, Object>> deviceTimeline(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FactRow row : facts(principal, source)) {
            JsonNode phases = row.payload.path("phases");
            if (!phases.isArray()) continue;
            for (JsonNode phase : phases) {
                Instant at = parse(phase.path("at").asText(null));
                if (at == null || at.isBefore(from) || at.isAfter(to) || !hasDevice(phase, deviceId)) continue;
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("taskId", row.taskId); event.put("phaseId", phase.path("phaseId").asText());
                event.put("at", at.toString()); event.put("status", row.status);
                event.put("deviceId", deviceId);
                result.add(event);
            }
        }
        return result;
    }

    @Override public List<Map<String, Object>> deviceCalendar(PrincipalInfo principal, String source, String deviceId, Instant from, Instant to) {
        return calendar(deviceTimeline(principal, source, deviceId, from, to), from, to);
    }

    private List<FactRow> facts(PrincipalInfo principal, String source) {
        List<FactRow> result = new ArrayList<>();
        for (FactRow row : jdbc.query("SELECT task_id,version,status,payload_json FROM task_facts ORDER BY update_time DESC",
                (rs, n) -> new FactRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), null, null))) {
            JsonNode payload;
            try { payload = mapper.readTree(row.payloadJson); } catch (Exception ignored) { continue; }
            if (!principal.tenantCode().equals(payload.path("tenantCode").asText())
                    || !principal.ownerKey().equals(payload.path("ownerKey").asText())
                    || !source.equals(payload.path("source").asText("office"))) continue;
            JsonNode safe = TaskPayloadSafety.withoutCredentials(mapper, payload);
            result.add(new FactRow(row.taskId, row.version, row.status, row.payloadJson, payload, safe));
        }
        return result;
    }

    private Map<String, Object> summary(FactRow row, Instant from, Instant to) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskId", row.taskId); view.put("version", row.version); view.put("status", row.status);
        List<Map<String, Object>> phases = new ArrayList<>();
        for (JsonNode phase : row.payload.path("phases")) {
            Instant at = parse(phase.path("at").asText(null));
            if (at == null || at.isBefore(from) || at.isAfter(to)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", phase.path("phaseId").asText()); item.put("at", at.toString());
            item.put("status", row.status); item.put("devices", List.of());
            phases.add(item);
        }
        view.put("phases", phases);
        view.put("payload", mapper.convertValue(row.safePayload, Object.class));
        return view;
    }

    private boolean overlaps(JsonNode payload, Instant from, Instant to) {
        for (JsonNode phase : payload.path("phases")) {
            Instant at = parse(phase.path("at").asText(null));
            if (at != null && !at.isBefore(from) && !at.isAfter(to)) return true;
        }
        return false;
    }

    private boolean hasRegion(Map<String, Object> view, String regionId) {
        Object payload = view.get("payload");
        return payload instanceof Map<?, ?> map && contains(map.get("regionIds"), regionId);
    }

    private boolean hasDevice(JsonNode phase, String deviceId) {
        for (JsonNode action : phase.path("actions")) if (deviceId.equals(action.path("deviceId").asText())) return true;
        for (JsonNode device : phase.path("read").path("devices")) if (deviceId.equals(device.path("id").asText())) return true;
        return false;
    }

    private List<Map<String, Object>> calendar(List<Map<String, Object>> events, Instant from, Instant to) {
        List<Map<String, Object>> days = new ArrayList<>();
        for (Instant day = from.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
             !day.isAfter(to); day = day.plusSeconds(86400)) {
            String date = day.toString().substring(0, 10);
            long count = events.stream().filter(event -> String.valueOf(event.getOrDefault("at", "")).startsWith(date)).count();
            if (count > 0) days.add(Map.of("date", date, "count", count));
        }
        return days;
    }

    private static boolean contains(Object value, String expected) {
        return value instanceof List<?> list && list.stream().anyMatch(item -> expected.equals(String.valueOf(item)));
    }

    private static Instant parse(String value) { try { return value == null ? null : Instant.parse(value); } catch (RuntimeException ignored) { return null; } }

    private record FactRow(String taskId, long version, String status, String payloadJson, JsonNode payload, JsonNode safePayload) {
        FactRow(String taskId, long version, String status, String payloadJson) {
            this(taskId, version, status, payloadJson, null, null);
        }
    }
}
