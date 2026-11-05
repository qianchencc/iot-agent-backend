package ltd.qianc.iotagent.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Per-run facts/receipts from Runtime pi-runner.ts:1226-1333,1683-1715,1748-1750. */
final class AgentEnergyRunFacts {
    private static final Set<String> READS = Set.of("summarize_energy", "compare_energy", "create_energy_report");
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<String, Call> calls = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> meters = new LinkedHashMap<>();
    private final Map<String, String> saved = new LinkedHashMap<>();
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> timeline = new LinkedHashMap<>();
    private boolean proposal;
    private record Call(String tool, String key, String label) { }

    void started(String callId, String tool, Map<String, Object> arguments) {
        if (callId == null || !READS.contains(tool)) return;
        String label = "summarize_energy".equals(tool) ? "统计区间用电" : "compare_energy".equals(tool) ? "对比用电" : "生成用电报告附件";
        if (arguments.get("from") instanceof String from && arguments.get("to") instanceof String to) label += "（" + from + " 至 " + to + "）";
        calls.put(callId, new Call(tool, tool + ":" + canonical(arguments), label));
    }
    void completed(String callId, String tool, Map<String, Object> result) {
        if (result.isEmpty()) return;
        if (!READS.contains(tool)) {
            if (String.valueOf(result.get("status")).equalsIgnoreCase("PENDING_CONFIRMATION")) proposal = true;
            return;
        }
        Call call = calls.get(callId);
        if (call == null) call = new Call(tool, tool, tool);
        String status = text(result.get("energyRead"));
        if (status == null && Set.of("preparing", "unavailable").contains(String.valueOf(result.get("status")))) status = text(result.get("status"));
        if (status != null) {
            String message = first(text(result.get("energyMessage")), text(result.get("summary")), text(result.get("message")), "数据暂不可用");
            pending.put(call.key(), call.label() + "未完成：" + message);
            timeline.put(callId, Map.of("status", "preparing".equals(status) ? "skipped" : "failed", "summary", message,
                    "details", Map.of("energyRead", status, "energyMessage", message)));
            return;
        }
        pending.remove(call.key());
        if ("meter-report".equals(result.get("type"))) {
            String id = first(text(result.get("reportId")), call.key()); meters.put(id, result);
        }
        if ("create_energy_report".equals(tool) && result.get("report") instanceof Map<?, ?> raw) {
            Map<String, Object> report = map(raw);
            String id = first(text(result.get("reportId")), text(report.get("id")), call.key());
            saved.put(id, first(text(result.get("summary")), text(report.get("summary")), text(report.get("title")), "用电报告"));
        }
    }
    Map<String, Object> timeline(String callId) { return timeline.getOrDefault(callId, Map.of()); }
    String receipt(boolean modelFailed, boolean emptyAnswer) {
        if (proposal || meters.isEmpty() && saved.isEmpty() && pending.isEmpty()) return null;
        if (!modelFailed && !emptyAnswer && pending.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> report : meters.values()) lines.add(summary(report));
        for (String summary : saved.values()) lines.add("已生成报告附件：" + summary);
        lines.addAll(pending.values());
        if (modelFailed) lines.add("智能解读未完成，已取得的业务结果保留。");
        if (!saved.isEmpty()) lines.add("可以先打开已有报告附件；未取得的部分可让我按原范围和期间重新读取，已有报告无需重新生成。");
        else if (!pending.isEmpty()) lines.add((meters.isEmpty() ? "本轮尚未取得统计结果" : "已有统计结果会保留") + "；可以让我按原范围和期间重新读取未取得的数据。");
        else lines.add("统计结果已保留，智能解读未完成。可以先查看结果卡，再沿这份结果继续提问。");
        return String.join("\n", lines);
    }
    static boolean hasData(Map<String, Object> report) {
        if (count(map(report.get("coverage")).get("dataMeters")) > 0) return true;
        if (!"comparison".equals(report.get("kind"))) return false;
        Map<String, Object> sides = map(report.get("sides"));
        for (String key : List.of("a", "b")) {
            Map<String, Object> side = map(sides.get(key));
            if (count(side.get("dataMeters")) > 0 || count(map(side.get("coverage")).get("dataMeters")) > 0) return true;
        }
        return false;
    }
    static Map<String, Object> modelFacts(Map<String, Object> report) {
        Map<String, Object> facts = new LinkedHashMap<>();
        boolean comparison = "comparison".equals(report.get("kind"));
        List<String> fields = comparison ? List.of("reportId", "kind", "params", "diff", "sides", "metrics", "overlapMeterIds", "contribution", "context", "coverage", "authorizationMeterIds", "cache")
                : List.of("reportId", "kind", "params", "total", "buckets", "shape24", "metrics", "context", "peer", "composition", "coverage", "windowSummary", "authorizationMeterIds", "cache");
        for (String field : fields) if (report.containsKey(field)) facts.put(field, report.get(field));
        compact(facts);
        Map<String, Object> params = map(report.get("params"));
        Map<String, Object> requested = map(map(params.get("span")).get("requested"));
        boolean simulated = false;
        try { simulated = OffsetDateTime.parse(String.valueOf(requested.get("to"))).isAfter(OffsetDateTime.parse("2025-01-01T00:00:00+08:00")); }
        catch (java.time.format.DateTimeParseException ignored) { }
        facts.put("interpretationNote", "数值和图表已由卡片承载，正文只简要概括各项结果与覆盖限制；用户未问建议时，不追加建议、排查或跟踪安排。"
                + (simulated ? "所选期间包含2024年之后的上游模拟补数，仅可展示已计算数值与覆盖，不能据恒定增量推断真实用电模式、基础负荷、节能空间或设备异常。" : ""));
        facts.put("metricDefinitions", Map.of("nightBase", Map.of("label", "夜间负荷", "meaning", "20:00至次日08:00完整窗口用电量除以12小时", "unit", "度/时", "window", "20:00-08:00"),
                "nightShare", Map.of("label", "夜间用电占比", "meaning", "完整计量日夜间用电量占全天用电量的比例", "unit", "%")));
        if (!comparison && params.containsKey("dailyWindow")) {
            facts.put("rankingBasis", "每日20:00至次日08:00已取得的完整窗口用电量；逐表有效天数见night，覆盖不等不能直接归因或据排名认定浪费");
            facts.put("dataProvenance", simulated ? "upstream-simulated-backfill" : "upstream-history");
        }
        if (!hasData(report)) {
            facts.put("cardEmitted", false);
            facts.put("note", comparison ? "两侧均无可计入的有效电表采样，未生成结果卡；请在正文中直接说明覆盖情况与最新采样时刻。"
                    : "所选区间内没有可计入的有效电表采样，未生成结果卡；请在正文中直接说明覆盖情况与最新采样时刻。");
        }
        return facts;
    }
    private static void compact(Map<String, Object> facts) {
        if (facts.get("coverage") instanceof Map<?, ?> coverage) facts.put("coverage", coverageSummary(coverage));
        if (facts.get("sides") instanceof Map<?, ?> sides) {
            Map<String, Object> compactSides = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : sides.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> side) compactSides.put(String.valueOf(entry.getKey()), sideSummary(side));
            }
            facts.put("sides", compactSides);
        }
        if (facts.get("composition") instanceof Map<?, ?> composition) {
            Map<String, Object> value = new LinkedHashMap<>();
            copy(composition, value, "kind", "unit", "total");
            value.put("rowCount", size(composition.get("rows")));
            facts.put("composition", value);
        }
        if (facts.get("contribution") instanceof Map<?, ?> contribution) {
            Map<String, Object> value = new LinkedHashMap<>();
            copy(contribution, value, "unit", "rest");
            value.put("rowCount", size(contribution.get("rows")));
            facts.put("contribution", value);
        }
        compactCount(facts, "metrics", "metricCount");
        compactCount(facts, "buckets", "bucketCount");
        compactCount(facts, "shape24", "shapeCount");
    }

    private static Map<String, Object> sideSummary(Map<?, ?> side) {
        Map<String, Object> value = new LinkedHashMap<>();
        copy(side, value, "total", "value", "unit", "dataMeters", "basis", "pct", "pctNote");
        if (side.get("coverage") instanceof Map<?, ?> coverage) value.put("coverage", coverageSummary(coverage));
        return value;
    }

    private static Map<String, Object> coverageSummary(Map<?, ?> coverage) {
        Map<String, Object> value = new LinkedHashMap<>();
        copy(coverage, value, "meters", "dataMeters", "truncated", "latestSampleAt");
        value.put("noDataCount", size(coverage.get("noData")));
        value.put("partialCount", size(coverage.get("partial")));
        return value;
    }

    private static void compactCount(Map<String, Object> facts, String key, String countKey) {
        if (facts.containsKey(key)) facts.put(key, Map.of("count", size(facts.get(key))));
    }

    private static void copy(Map<?, ?> source, Map<String, Object> target, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static int size(Object value) {
        return value instanceof Map<?, ?> map ? map.size() : value instanceof List<?> list ? list.size() : 0;
    }

    private static String summary(Map<String, Object> report) {
        Map<String, Object> params = map(report.get("params")), scope = map(params.get("scope")), span = map(map(params.get("span")).get("requested"));
        String label = "comparison".equals(report.get("kind")) ? "用电对比" : "用电统计";
        return label + "已取得：" + first(text(scope.get("label")), "所选电表") + "，" + span.get("from") + " 至 " + span.get("to")
                + "；有效电表 " + count(map(report.get("coverage")).get("dataMeters")) + "/" + count(map(report.get("coverage")).get("meters")) + " 台。";
    }
    private static String canonical(Object value) {
        try { return JSON.writeValueAsString(canonicalValue(value)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("能耗请求不可序列化", error); }
    }
    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> fields) {
            Map<String, Object> sorted = new TreeMap<>();
            fields.forEach((key, item) -> { if (!"idempotencyKey".equals(key)) sorted.put(String.valueOf(key), canonicalValue(item)); }); return sorted;
        }
        if (value instanceof List<?> items) return items.stream().map(AgentEnergyRunFacts::canonicalValue).toList();
        if (value instanceof String text) {
            try { return OffsetDateTime.parse(text).toInstant().toString(); }
            catch (java.time.format.DateTimeParseException ignored) { return text; }
        }
        return value;
    }
    private static int count(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value; return ""; }
    private static String text(Object value) { return value instanceof String text && !text.isBlank() ? text : null; }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }
}
