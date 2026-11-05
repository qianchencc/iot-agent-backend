package ltd.qianc.iotagent.iot.internal;

import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.round;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runtime meter-report.ts grouping, coverage and parent/peer facts. */
final class MeterReportLayout {
    private MeterReportLayout() { }
    static boolean ok(Map<String, Object> row) { return "ok".equals(row.get("status")); }
    static double value(Map<String, Object> row) { return row.get("value") instanceof Number value ? value.doubleValue() : 0; }
    static double sum(List<Map<String, Object>> rows) { return round(rows.stream().filter(MeterReportLayout::ok).mapToDouble(MeterReportLayout::value).sum()); }
    static String label(Map<String, Object> row) { String area = Objects.toString(row.get("areaName"), ""); return area.isBlank() ? String.valueOf(row.get("name")) : area; }
    static List<Map<String, Object>> locations(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) if (ok(row)) {
            Map<String, Object> group = groups.computeIfAbsent(label(row), name -> {
                Map<String, Object> item = new LinkedHashMap<>(); item.put("label", name); item.put("value", 0d);
                item.put("meterIds", new ArrayList<String>()); return item;
            });
            group.put("value", value(group) + value(row));
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) group.get("meterIds"); ids.add(String.valueOf(row.get("id")));
        }
        List<Map<String, Object>> sorted = new ArrayList<>(groups.values());
        for (Map<String, Object> item : sorted) item.put("value", round(value(item)));
        sorted.sort(Comparator.comparingDouble(MeterReportLayout::value).reversed()); return sorted;
    }
    static Map<String, Object> composition(List<Map<String, Object>> rows, int limit, String highlight) {
        List<Map<String, Object>> all = locations(rows), shown = new ArrayList<>(all.subList(0, Math.min(limit, all.size())));
        if (highlight != null) for (Map<String, Object> item : all) if (highlight.equals(item.get("label"))) {
            item.put("highlight", true); if (!shown.contains(item)) shown.add(item);
        }
        Map<String, Object> rest = null;
        if (all.size() > limit) {
            double restValue = all.stream().filter(item -> !shown.contains(item)).mapToDouble(MeterReportLayout::value).sum();
            rest = Map.of("count", all.size() - shown.size(), "value", round(restValue));
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("kind", "ranking"); result.put("rows", shown); result.put("rest", rest);
        Map<String, Object> excluded = new LinkedHashMap<>();
        excluded.put("noData", rows.stream().filter(row -> "noData".equals(row.get("status"))).count());
        excluded.put("partial", rows.stream().filter(row -> "partial".equals(row.get("status"))).count());
        excluded.put("through", rows.stream().filter(row -> "partial".equals(row.get("status"))).map(row -> sampleTime(row, "toSample"))
                .filter(Objects::nonNull).max(String::compareTo).orElse(null));
        result.put("excluded", excluded); return result;
    }
    static Map<String, Object> context(String level, List<Map<String, Object>> rows, List<Map<String, Object>> catalog, double days) {
        Map<String, Object> result = new LinkedHashMap<>();
        if ("all".equals(level)) {
            Map<String, Map<String, Object>> floors = new LinkedHashMap<>();
            for (Map<String, Object> row : catalog) {
                String floor = Objects.toString(row.get("floor"), "其他");
                Map<String, Object> item = floors.computeIfAbsent(floor, key -> new LinkedHashMap<>(Map.of("floor", key, "value", 0d, "dataMeters", 0, "meters", 0)));
                item.put("meters", ((Number) item.get("meters")).intValue() + 1);
                if (ok(row)) { item.put("value", value(item) + value(row)); item.put("dataMeters", ((Number) item.get("dataMeters")).intValue() + 1); }
            }
            List<Map<String, Object>> sorted = new ArrayList<>(floors.values());
            for (Map<String, Object> item : sorted) item.put("value", round(value(item)));
            sorted.sort(Comparator.comparingDouble(MeterReportLayout::value).reversed());
            result.put("kind", "floorSplit"); result.put("floors", sorted);
        } else if ("location".equals(level)) {
            Map<String, Object> self = rows.get(0);
            List<Map<String, Object>> peers = catalog.stream().filter(row -> Objects.equals(row.get("floor"), self.get("floor"))).toList();
            List<Map<String, Object>> ranked = locations(peers); int rank = 0;
            for (int index = 0; index < ranked.size(); index++) if (label(self).equals(ranked.get(index).get("label"))) { rank = index + 1; break; }
            double total = sum(peers); result.put("kind", "peer"); result.put("parentLabel", Objects.toString(self.get("floor"), "本层"));
            result.put("share", total > 0 && self.get("value") != null ? round(value(self) / total * 100) : null);
            result.put("rank", rank == 0 ? null : rank); result.put("of", ranked.size());
        } else {
            double parent = sum(catalog), total = sum(rows); long n = rows.stream().filter(MeterReportLayout::ok).count(), parentN = catalog.stream().filter(MeterReportLayout::ok).count();
            result.put("kind", "parent"); result.put("parentLabel", "全部电表"); result.put("share", parent > 0 ? round(total / parent * 100) : null);
            result.put("perMeterDaily", n > 0 ? round(total / (n * days)) : null); result.put("parentPerMeterDaily", parentN > 0 ? round(parent / (parentN * days)) : null);
        }
        return result;
    }
    static Map<String, Object> coverage(List<Map<String, Object>> rows, boolean truncated) {
        List<String> missing = new ArrayList<>(); List<Map<String, Object>> partial = new ArrayList<>(); int valid = 0;
        for (Map<String, Object> row : rows) {
            if (ok(row)) valid++;
            else if ("noData".equals(row.get("status"))) missing.add(String.valueOf(row.get("id")));
            else { Map<String, Object> item = new LinkedHashMap<>(); item.put("id", row.get("id")); item.put("through", sampleTime(row, "toSample")); partial.add(item); }
        }
        return Map.of("meters", rows.size(), "dataMeters", valid, "noData", missing, "partial", partial, "truncated", truncated);
    }
    static String sampleTime(Map<String, Object> row, String end) {
        Object observed = row.get("observed");
        if (!(observed instanceof Map<?, ?> span) || !(span.get(end) instanceof Map<?, ?> sample)) return null;
        return sample.get("readTime") instanceof String time ? time : null;
    }
    static Map<String, Object> observed(List<Map<String, Object>> rows) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", rows.stream().filter(MeterReportLayout::ok).map(row -> sampleTime(row, "fromSample")).filter(Objects::nonNull).min(String::compareTo).orElse(null));
        result.put("to", rows.stream().filter(MeterReportLayout::ok).map(row -> sampleTime(row, "toSample")).filter(Objects::nonNull).max(String::compareTo).orElse(null)); return result;
    }
    static Map<String, Object> metric(String key, Object value, String unit) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("key", key); result.put("unit", unit);
        if (value instanceof Map<?, ?> pair) pair.forEach((field, item) -> result.put(String.valueOf(field), item)); else result.put("value", value);
        if ("nightBase".equals(key) || "nightShare".equals(key)) result.put("nightWindow", "20:00-08:00");
        if ("peakDay".equals(key) || "weekdayDailyAvg".equals(key) || "nightShare".equals(key)) result.put("dayWindow", "previous08:00-08:00");
        return result;
    }
    static Map<String, Object> metricPair(String key, String unit, Object a, Object b) {
        Map<String, Object> result = metric(key, null, unit); result.remove("value");
        Map<String, Object> va = metric(key, a, unit), vb = metric(key, b, unit);
        va.keySet().removeAll(List.of("key", "unit", "nightWindow", "dayWindow")); vb.keySet().removeAll(List.of("key", "unit", "nightWindow", "dayWindow"));
        result.put("a", va); result.put("b", vb);
        result.put("change", va.get("value") instanceof Number av && vb.get("value") instanceof Number bv ? round(av.doubleValue() - bv.doubleValue()) : null); return result;
    }
}
