package ltd.qianc.iotagent.report.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Saved sampling evidence shared by PDF and spreadsheet; never fetches or recalculates data. */
final class MeterExportEvidence {
    private static final java.time.Instant SIMULATED_BOUNDARY =
            OffsetDateTime.parse("2025-01-01T00:00:00+08:00").toInstant();
    private static final java.util.regex.Pattern TIMESTAMP = java.util.regex.Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})");

    private MeterExportEvidence() { }

    record Section(String label, Map<?, ?> fact, boolean comparison) {
        List<Map<?, ?>> meters() { return maps(fact.get("meters")); }
        Map<?, ?> params() { return map(fact.get("params")); }
        Map<?, ?> diff() { return map(fact.get("diff")); }
        Object total(String side) {
            Map<?, ?> source = comparison ? map(map(fact.get("sides")).get(side)) : fact;
            return map(source.get("total")).get("value");
        }
        String period(String side) {
            return periodText(map(params().get("b".equals(side) ? "baseline" : "span")));
        }
    }

    static List<Section> sections(Map<String, Object> report) {
        List<Section> result = new ArrayList<>();
        for (Map<?, ?> block : maps(report.get("blocks"))) {
            if (!"meter_report".equals(block.get("type")) || !(block.get("report") instanceof Map<?, ?> fact)) continue;
            boolean comparison = "comparison".equals(fact.get("kind"));
            result.add(new Section((comparison ? "用电对比 " : "用电统计 ") + (result.size() + 1), fact, comparison));
        }
        return result;
    }

    /** Suppress only an identical statistics detail already represented by a comparison.
     * Different scopes, periods, values, status or sample evidence stay separate. */
    static List<Section> details(List<Section> sections) {
        return sections.stream().filter(section -> section.comparison() || sections.stream().noneMatch(other ->
                other.comparison() && samePeriod(section, other) && sameMeters(section, other))).toList();
    }

    private static boolean samePeriod(Section a, Section b) {
        Map<?, ?> ar = map(map(a.params().get("span")).get("requested"));
        Map<?, ?> br = map(map(b.params().get("span")).get("requested"));
        return sameTime(ar.get("from"), br.get("from")) && sameTime(ar.get("to"), br.get("to"))
                && java.util.Objects.equals(a.params().get("scope"), b.params().get("scope"));
    }

    private static boolean sameTime(Object a, Object b) {
        if (a == null || b == null) return false;
        try { return OffsetDateTime.parse(a.toString()).toInstant().equals(OffsetDateTime.parse(b.toString()).toInstant()); }
        catch (java.time.DateTimeException invalid) { return a.equals(b); }
    }

    private static boolean sameMeters(Section a, Section b) {
        if (a.meters().isEmpty() || a.meters().size() != b.meters().size()) return false;
        if (!java.util.Objects.equals(a.fact().get("coverage"), b.fact().get("coverage"))) return false;
        Map<Object, Map<?, ?>> byId = new java.util.HashMap<>();
        for (Map<?, ?> row : b.meters()) {
            if (row.get("id") == null || byId.put(row.get("id"), current(row, "a")) != null) return false;
        }
        for (Map<?, ?> row : a.meters()) {
            Map<?, ?> other = byId.get(row.get("id"));
            if (other == null) return false;
            for (String key : List.of("value", "status", "observed")) {
                if (!java.util.Objects.equals(row.get(key), other.get(key))) return false;
            }
        }
        return true;
    }

    static Map<?, ?> current(Map<?, ?> meter, String side) {
        return meter.get(side) instanceof Map<?, ?> value ? value : "a".equals(side) ? meter : Map.of();
    }

    static Object change(Map<?, ?> row) {
        Map<?, ?> a = current(row, "a"), b = current(row, "b");
        if (!"ok".equals(a.get("status")) || !"ok".equals(b.get("status"))
                || !(a.get("value") instanceof Number av) || !(b.get("value") instanceof Number bv)) return null;
        return new java.math.BigDecimal(av.toString()).subtract(new java.math.BigDecimal(bv.toString()));
    }

    static Object rate(Map<?, ?> row) {
        Object delta = change(row);
        Object base = current(row, "b").get("value");
        if (!(delta instanceof java.math.BigDecimal value) || !(base instanceof Number number)
                || number.doubleValue() <= 0) return null;
        return value.multiply(java.math.BigDecimal.valueOf(100))
                .divide(new java.math.BigDecimal(number.toString()), 2, java.math.RoundingMode.HALF_UP);
    }

    static String comparisonNote(Map<?, ?> row) {
        if (change(row) == null) return "两期缺少共同有效数据，不比较";
        return rate(row) == null ? "基期为零或负值，比例不可计算" : "";
    }

    static String status(Object raw) {
        if (raw == null) return "未取得";
        return switch (String.valueOf(raw)) {
            case "ok", "complete", "completed", "COMPLETED", "comparable" -> "完整";
            case "partial", "PARTIAL" -> "部分数据";
            case "noData", "missing", "not_computable" -> "无有效数据";
            case "failed", "FAILED" -> "失败";
            default -> String.valueOf(raw);
        };
    }

    static String time(Object raw) {
        if (raw == null) return "未取得";
        try {
            return OffsetDateTime.parse(raw.toString()).atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (java.time.DateTimeException invalid) { return raw.toString(); }
    }

    static String periodText(Map<?, ?> span) {
        Map<?, ?> requested = map(span.get("requested"));
        return time(requested.get("from")) + " 至 " + time(requested.get("to"));
    }

    static String number(Object raw) {
        return raw instanceof Number value ? new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString() : "—";
    }

    static String metricName(Object raw) {
        if (raw == null) return "指标";
        return switch (raw.toString()) {
            case "dailyAvg" -> "日均用电";
            case "weekdayDailyAvg" -> "工作日日均用电";
            case "nightBase" -> "夜间平均负荷";
            case "nightShare" -> "夜间用电占比";
            case "peakDay" -> "峰值日";
            case "peakHour" -> "峰值小时";
            case "dayTotal" -> "总用电";
            case "perMeterDaily" -> "单表日均";
            case "top3Share" -> "前三用电占比";
            default -> raw.toString();
        };
    }

    static String reportStatus(Object status) {
        return "completed".equals(status) ? "已完成" : "partial".equals(status) ? "部分完成" : status(status);
    }

    static String displayText(String value) {
        return TIMESTAMP.matcher(value).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(time(match.group())));
    }

    static String metricPeriod(Map<?, ?> metric) {
        Object value = metric.get("sub");
        if (value == null) value = metric.get("from");
        return value == null ? "" : value.toString();
    }

    static String bucketStatus(Map<?, ?> bucket) {
        if (bucket.get("status") != null) return status(bucket.get("status"));
        if (Boolean.TRUE.equals(bucket.get("partial"))) return "部分时段";
        return bucket.get("value") instanceof Number ? "已取得" : "无有效数据";
    }

    static List<Map<?, ?>> maps(Object raw) {
        List<Map<?, ?>> result = new ArrayList<>();
        if (raw instanceof List<?> rows) for (Object row : rows) if (row instanceof Map<?, ?> value) result.add(value);
        return result;
    }

    static Map<?, ?> map(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }

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

    static String missing(Object value) {
        return value == null || value instanceof List<?> rows && rows.isEmpty() ? "无" : String.valueOf(value);
    }
    private static String text(Object value) { return value == null ? "未取得" : String.valueOf(value); }
}
