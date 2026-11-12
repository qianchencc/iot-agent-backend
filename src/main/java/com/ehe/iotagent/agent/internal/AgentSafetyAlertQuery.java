package com.ehe.iotagent.agent.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import com.ehe.iotagent.error.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Completes authorized ledger pagination before publishing one window-level alert result. */
final class AgentSafetyAlertQuery {
    private static final int PREVIEW_LIMIT = 8;
    private static final Comparator<Alert> NEWEST_FIRST = Comparator.comparing(Alert::time).reversed()
            .thenComparing(Alert::key);

    private AgentSafetyAlertQuery() { }

    static Map<String, Object> read(List<String> types, Map<String, Object> window, boolean unreportedOnly,
            Function<Map<String, Object>, Map<String, Object>> readPage) {
        List<Ledger> ledgers = Flux.fromIterable(types)
                .flatMapSequential(type -> Mono.fromCallable(() -> readLedger(type, window, readPage))
                        .subscribeOn(Schedulers.boundedElastic()), 3)
                .collectList().block();
        Set<String> seen = new HashSet<>();
        List<Alert> recent = new ArrayList<>(PREVIEW_LIMIT);
        Map<String, DeviceSummary> devices = new LinkedHashMap<>();
        List<Map<String, Object>> byType = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        boolean complete = true;
        boolean available = false;
        int scanned = 0, excluded = 0, unknown = 0;
        for (Ledger ledger : ledgers) {
            complete &= ledger.complete;
            available |= ledger.available;
            scanned += ledger.scanned;
            excluded += ledger.excluded;
            unknown += ledger.unknown;
            if (ledger.failure != null) failures.add(Map.of("type", ledger.type, "message", ledger.failure));
            int count = 0;
            Alert latest = null;
            Set<String> typeDevices = new HashSet<>();
            for (Map<?, ?> row : ledger.rows) {
                if (unreportedOnly && !Boolean.FALSE.equals(row.get("report"))) continue;
                String key = row.get("ledger") + ":" + row.get("eventId") + ":" + row.get("deviceId");
                if (!seen.add(key)) continue;
                Alert alert = new Alert(row, Instant.parse(String.valueOf(row.get("occurredAt"))), key);
                count++;
                String deviceId = String.valueOf(row.get("deviceId"));
                typeDevices.add(deviceId);
                devices.computeIfAbsent(deviceId, ignored -> new DeviceSummary()).add(alert);
                if (latest == null || NEWEST_FIRST.compare(alert, latest) < 0) latest = alert;
                int index = 0;
                while (index < recent.size() && NEWEST_FIRST.compare(alert, recent.get(index)) >= 0) index++;
                if (index < PREVIEW_LIMIT) {
                    recent.add(index, alert);
                    if (recent.size() > PREVIEW_LIMIT) recent.removeLast();
                }
            }
            if (ledger.available) {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("type", ledger.type);
                summary.put("total", count);
                summary.put("deviceCount", typeDevices.size());
                summary.put("latestTime", latest == null ? null : latest.row.get("occurredAt"));
                summary.put("coverageComplete", ledger.complete);
                if (ledger.cache != null) summary.put("cache", ledger.cache);
                byType.add(summary);
            }
        }
        List<DeviceSummary> rankedDevices = new ArrayList<>(devices.values());
        rankedDevices.sort(Comparator.comparingInt((DeviceSummary device) -> device.total).reversed()
                .thenComparing(device -> device.latest, NEWEST_FIRST));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("types", types);
        result.put("unreportedOnly", unreportedOnly);
        result.put("window", Map.of("from", window.get("from"), "to", window.get("to"), "timezone", "Asia/Shanghai"));
        result.put("summaryStatus", !available ? "unavailable" : complete ? "complete" : "partial");
        result.put("coverageComplete", complete);
        result.put("total", available ? seen.size() : null);
        result.put("uniqueDevices", available ? devices.size() : null);
        result.put("byType", byType);
        result.put("byDevice", rankedDevices.stream().map(DeviceSummary::view).toList());
        result.put("records", recent.stream().map(Alert::row).toList());
        if (recent.isEmpty()) result.put("windowLatest", null);
        else {
            Map<?, ?> latest = recent.getFirst().row;
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("time", latest.get("occurredAt"));
            fact.put("deviceName", latest.get("deviceName"));
            fact.put("type", latest.get("subtype"));
            fact.put("report", latest.get("report"));
            result.put("windowLatest", fact);
        }
        result.put("scanned", scanned);
        result.put("excludedCount", excluded);
        result.put("unknownCount", unknown);
        result.put("failures", failures);
        return result;
    }

    private static Ledger readLedger(String type, Map<String, Object> window,
            Function<Map<String, Object>, Map<String, Object>> readPage) {
        Ledger ledger = new Ledger(type);
        Set<String> cursors = new HashSet<>();
        Map<String, Object> parameters = new LinkedHashMap<>(window);
        parameters.put("type", type);
        parameters.put("limit", 100);
        try {
            while (true) {
                Map<String, Object> page = readPage.apply(parameters);
                // A later fresh page cannot make already consumed stale pages fresh.
                if (page.get("cache") instanceof Map<?, ?> cache
                        && (ledger.cache == null || !"stale".equals(ledger.cache.get("status")))) {
                    ledger.cache = cache;
                }
                ledger.available = true;
                ledger.scanned += number(page.get("scanned"));
                ledger.excluded += number(page.get("excludedCount"));
                ledger.unknown += number(page.get("unknownCount"));
                if (page.get("items") instanceof List<?> items) {
                    for (Object row : items) if (row instanceof Map<?, ?> value) ledger.rows.add(value);
                }
                if (Boolean.TRUE.equals(page.get("coverageComplete"))) {
                    ledger.complete = true;
                    break;
                }
                if (!(page.get("nextCursor") instanceof String cursor) || cursor.isBlank() || !cursors.add(cursor)) {
                    ledger.failure = "台账分页未完成，未取得有效续页";
                    break;
                }
                parameters.put("cursor", cursor);
            }
        } catch (BusinessException error) {
            ledger.failure = error.getMessage();
        }
        return ledger;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private record Alert(Map<?, ?> row, Instant time, String key) { }

    private static final class Ledger {
        private final String type;
        private final List<Map<?, ?>> rows = new ArrayList<>();
        private boolean available;
        private boolean complete;
        private String failure;
        private Map<?, ?> cache;
        private int scanned, excluded, unknown;

        private Ledger(String type) { this.type = type; }
    }

    private static final class DeviceSummary {
        private int total;
        private Alert latest;
        private final List<Map<String, Object>> eventRefs = new ArrayList<>();

        private void add(Alert alert) {
            total++;
            if (latest == null || NEWEST_FIRST.compare(alert, latest) < 0) latest = alert;
            if (eventRefs.size() < PREVIEW_LIMIT) {
                eventRefs.add(Map.of("eventId", alert.row.get("eventId"), "ledger", alert.row.get("ledger"),
                        "deviceId", alert.row.get("deviceId")));
            }
        }

        private Map<String, Object> view() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("deviceId", latest.row.get("deviceId"));
            result.put("deviceName", latest.row.get("deviceName"));
            result.put("location", latest.row.get("location"));
            result.put("total", total);
            result.put("latestTime", latest.row.get("occurredAt"));
            result.put("eventRefs", eventRefs);
            return result;
        }
    }
}
