package ltd.qianc.iotagent.report.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Saved sampling evidence shared by PDF and spreadsheet; never fetches or recalculates data. */
final class MeterExportEvidence {
    private static final java.time.Instant SIMULATED_BOUNDARY =
            OffsetDateTime.parse("2025-01-01T00:00:00+08:00").toInstant();

    private MeterExportEvidence() { }

    static List<String> reportNotes(Map<String, Object> report) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        if (report.get("blocks") instanceof List<?> blocks) {
            for (Object value : blocks) {
                if (value instanceof Map<?, ?> block && "meter_report".equals(block.get("type"))
                        && block.get("report") instanceof Map<?, ?> meterReport) {
                    result.addAll(notes(meterReport));
                }
            }
        }
        return List.copyOf(result);
    }

    static List<String> notes(Map<?, ?> report) {
        Map<?, ?> params = map(report.get("params"));
        List<String> notes = new ArrayList<>();
        Map<?, ?> span = map(params.get("span"));
        Map<?, ?> observed = map(span.get("observed"));
        if (observed.get("from") != null && observed.get("to") != null) {
            notes.add("实际采样覆盖：" + observed.get("from") + " 至 " + observed.get("to"));
        }
        Map<?, ?> baseline = map(params.get("baseline"));
        Map<?, ?> baseObserved = map(baseline.get("observed"));
        if (baseObserved.get("from") != null && baseObserved.get("to") != null) {
            notes.add("基期采样覆盖：" + baseObserved.get("from") + " 至 " + baseObserved.get("to"));
        }
        if (includesSimulated(span) || includesSimulated(baseline)) {
            notes.add("所选期间包含客户模拟补数，不作为真实运行或节能证据");
        }
        return notes;
    }

    static List<Object> samples(Map<?, ?> meter, String side) {
        Map<?, ?> row = meter.get(side) instanceof Map<?, ?> value ? value : "a".equals(side) ? meter : Map.of();
        Map<?, ?> observed = map(row.get("observed"));
        Map<?, ?> from = map(observed.get("fromSample"));
        Map<?, ?> to = map(observed.get("toSample"));
        // Null cells remain null: a missing reading is not zero.
        return java.util.Arrays.asList(from.get("readTime"), from.get("value"), to.get("readTime"), to.get("value"));
    }

    static String sampleLine(Map<?, ?> meter, String side) {
        List<Object> values = samples(meter, side);
        if (values.stream().allMatch(java.util.Objects::isNull)) return "";
        return ("a".equals(side) ? "本期" : "基期") + "采样："
                + text(values.get(0)) + " 示数 " + text(values.get(1)) + " → "
                + text(values.get(2)) + " 示数 " + text(values.get(3));
    }

    private static boolean includesSimulated(Map<?, ?> span) {
        Object end = map(span.get("requested")).get("to");
        if (!(end instanceof String value)) return false;
        try { return OffsetDateTime.parse(value).toInstant().isAfter(SIMULATED_BOUNDARY); }
        catch (java.time.DateTimeException invalid) { return false; }
    }

    private static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }
    private static String text(Object value) { return value == null ? "未取得" : String.valueOf(value); }
}
