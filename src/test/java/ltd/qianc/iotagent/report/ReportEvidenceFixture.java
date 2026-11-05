package ltd.qianc.iotagent.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperationEnum;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import reactor.core.publisher.Mono;

/** Public-seam observation double for report HTTP and generation contract tests. */
public final class ReportEvidenceFixture {
    private ReportEvidenceFixture() { }

    public static Map<String, Object> energy(String meterId, boolean compare) {
        return energy("2026-09-01T08:00:00+08:00", "2026-10-01T08:00:00+08:00", meterId, compare);
    }

    public static Map<String, Object> energy(String from, String to, String meterId, boolean compare) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "energy");
        content.put("title", "公司用电报告");
        content.put("period", Map.of("kind", "absolute", "from", from, "to", to));
        content.put("scope", Map.of("level", "selection", "meterIds", List.of(meterId)));
        if (compare) content.put("comparisons", List.of("previousPeriod"));
        return content;
    }

    public static Map<String, Object> safety() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "safety");
        content.put("period", Map.of("kind", "absolute", "from", "2026-10-01T08:00:00+08:00",
                "to", "2026-10-02T08:00:00+08:00"));
        content.put("types", List.of("smoke", "gas"));
        return content;
    }

    public static ObservationAdapter evidence() {
        return new Evidence();
    }

    private static final class Evidence implements ObservationAdapter {
        @Override
        public Mono<ObservationResponse> read(ObservationRequest request) {
            if (request.operation() == ObservationOperationEnum.SAFETY_EVENTS) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("items", List.of(Map.of("eventId", "event-" + request.parameters().get("type"),
                        "ledger", request.parameters().get("type"), "deviceId", "sensor-1",
                        "deviceName", "传感器", "occurredAt", "2026-10-01T09:00:00+08:00", "alarm", true)));
                payload.put("total", 1);
                payload.put("scanned", 1);
                if ("gas".equals(request.parameters().get("type"))) payload.put("coverageComplete", false);
                else payload.put("coverageComplete", true);
                return Mono.just(ObservationResponse.available(payload));
            }
            List<String> ids = request.parameters().get("meterIds") == null
                    ? List.of("meter-1")
                    : List.of(request.parameters().get("meterIds").split(","));
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("requested", Map.of("from", request.parameters().get("from"),
                    "to", request.parameters().get("to")));
            params.put("span", span);
            params.put("scope", Map.of("level", "selection", "label", "test", "basis", "deviceIds",
                    "meterCount", ids.size()));
            params.put("axis", request.parameters().get("axis"));
            if (request.parameters().containsKey("baseFrom")) {
                Map<String, Object> baseline = new LinkedHashMap<>();
                baseline.put("requested", Map.of("from", request.parameters().get("baseFrom"),
                        "to", request.parameters().get("baseTo")));
                params.put("baseline", baseline);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "meter-report");
            payload.put("kind", request.parameters().get("kind"));
            payload.put("params", params);
            payload.put("total", Map.of("value", 10.0d, "unit", "度"));
            if (request.parameters().containsKey("baseFrom")) {
                payload.put("diff", Map.of("value", 5.0d, "pct", 100.0d, "basis", "total", "unit", "度"));
            }
            payload.put("coverage", Map.of("meters", ids.size(), "dataMeters", ids.size(), "noData", List.of(),
                    "partial", List.of(), "truncated", false));
            List<Map<String, Object>> meters = new java.util.ArrayList<>();
            for (String id : ids) {
                meters.add(Map.of("id", id, "name", "Meter " + id, "areaName", "26楼",
                        "value", 10.0d, "status", "ok", "a", Map.of("value", 10.0d, "status", "ok"),
                        "b", Map.of("value", 5.0d, "status", "ok")));
            }
            payload.put("meters", meters);
            payload.put("authorizationMeterIds", ids);
            return Mono.just(ObservationResponse.available(payload));
        }
    }
}
