package ltd.qianc.iotagent.task.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime report-summary.ts summarizeTaskReport: the checkpoint/device/energy
 * aggregation over one already visibility-trimmed report. Null-valued fields
 * are legal facts — a zero-alert window carries latest/windowLatest null, an
 * open statistics span carries a null end — and are copied through unchanged;
 * nothing here drops fields, invents values or swallows errors for them.
 */
public final class TaskReportSummaries {
    private TaskReportSummaries() { }

    public static Map<String, Object> summarize(Map<String, Object> report) {
        List<Map<String, Object>> phases = phaseList(report);
        List<Map<String, Object>> inspections = new ArrayList<>();
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Map<String, Object> phase : phases) {
            if (!(phase.get("observations") instanceof List<?> observations)) continue;
            for (Object block : observations) {
                if (!(block instanceof Map<?, ?> observation)) continue;
                blocks.add(stringKeyedCopy(observation));
                if ("ac_inspection".equals(String.valueOf(observation.get("type")))) {
                    inspections.add((Map<String, Object>) observation);
                }
            }
        }
        if (inspections.isEmpty()) return null;
        Map<String, Object[]> perDevice = new LinkedHashMap<>();
        for (Map<String, Object> block : inspections) {
            if (!(block.get("devices") instanceof List<?> devices)) continue;
            for (Object item : devices) {
                if (!(item instanceof Map<?, ?> device)) continue;
                String deviceId = String.valueOf(device.get("deviceId"));
                Object[] entry = perDevice.computeIfAbsent(deviceId, key -> new Object[]{null, new ArrayList<String>()});
                ((List<String>) entry[1]).add(device.get("outcome") == null ? "" : String.valueOf(device.get("outcome")));
                String disposition = String.valueOf(device.get("disposition"));
                String current = entry[0] == null ? "" : (String) entry[0];
                if ("adjusted".equals(disposition)) entry[0] = "adjusted";
                else if ("not_dispatched".equals(disposition) && !"adjusted".equals(current)) entry[0] = "not_dispatched";
                else if ("unknown".equals(disposition) && "adjustable".equals(current)) entry[0] = "unknown";
                else if (entry[0] == null) entry[0] = disposition;
            }
        }
        Map<String, Long> devices = new LinkedHashMap<>();
        devices.put("total", (long) perDevice.size());
        devices.put("adjusted", 0L);
        devices.put("satisfied", 0L);
        devices.put("skipped", 0L);
        devices.put("failed", 0L);
        devices.put("unknown", 0L);
        devices.put("unverified", 0L);
        Set<String> failureOutcomes = Set.of("failed", "rejected", "missing", "unreadable", "read_failed");
        for (Object[] entry : perDevice.values()) {
            String disposition = entry[0] == null ? "" : (String) entry[0];
            List<String> outcomes = (List<String>) entry[1];
            switch (disposition) {
                case "adjusted" -> devices.merge("adjusted", 1L, Long::sum);
                case "not_needed" -> devices.merge("satisfied", 1L, Long::sum);
                case "not_dispatched" -> devices.merge("skipped", 1L, Long::sum);
                case "unknown" -> devices.merge("unknown", 1L, Long::sum);
                default -> devices.merge("unverified", 1L, Long::sum);
            }
            if (outcomes.stream().anyMatch(failureOutcomes::contains) && !"adjusted".equals(disposition)) {
                devices.merge("failed", 1L, Long::sum);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checkpoints", (long) inspections.size());
        summary.put("devices", devices);
        summary.put("energyImpact", devices.get("adjusted") instanceof Long adjusted && adjusted > 0
                ? energyImpact(blocks, (Long) devices.get("adjusted"), windowEnd(report))
                : null);
        return summary;
    }

    private static String windowEnd(Map<String, Object> report) {
        Object interval = report.get("interval");
        String intervalEnd = interval instanceof Map<?, ?> span
                ? String.valueOf(((Map<?, ?>) span).get("endsAt")) : null;
        String generatedAt = String.valueOf(report.get("generatedAt"));
        if (intervalEnd != null && generatedAt != null) {
            try {
                Instant end = Instant.parse(intervalEnd);
                Instant generated = Instant.parse(generatedAt);
                return end.isBefore(generated) ? end.toString() : generated.toString();
            } catch (RuntimeException ignored) {
                return intervalEnd;
            }
        }
        return intervalEnd != null ? intervalEnd : generatedAt;
    }

    /** Runtime projectEnergyImpact: measured comparison, AC assumption estimate, statistics, honest unavailable. */
    private static Map<String, Object> energyImpact(List<Map<String, Object>> blocks, long adjustedDevices, String windowEnd) {
        List<Map<String, Object>> meterReports = new ArrayList<>();
        for (Map<String, Object> block : blocks) {
            if ("meter_report".equals(String.valueOf(block.get("type")))
                    && block.get("report") instanceof Map<?, ?> report) {
                meterReports.add(stringKeyedCopy(report));
            }
        }
        for (Map<String, Object> report : meterReports) {
            if (truncated(report) || dataMeters(report) == 0) continue;
            if ("comparison".equals(String.valueOf(report.get("kind")))) {
                Map<String, Object> impact = new LinkedHashMap<>();
                impact.put("status", "measured");
                impact.put("basis", "comparison");
                impact.put("from", observedSpan(report, "from"));
                impact.put("to", observedSpan(report, "to"));
                impact.put("meterCount", dataMeters(report));
                Object diffNode = report.get("diff");
                Map<?, ?> diff = diffNode instanceof Map<?, ?> value ? value : Map.of();
                impact.put("value", diff.get("value"));
                impact.put("unit", String.valueOf(diff.get("unit") == null ? "度" : diff.get("unit")));
                impact.put("label", "关联能耗变化");
                return impact;
            }
        }
        Map<String, Object> assumed = windowEnd == null ? null : estimateAcSavings(blocks, adjustedDevices, windowEnd);
        if (assumed != null) return assumed;
        for (Map<String, Object> report : meterReports) {
            if (truncated(report) || dataMeters(report) == 0) continue;
            if ("statistics".equals(String.valueOf(report.get("kind")))) {
                Map<String, Object> impact = new LinkedHashMap<>();
                impact.put("status", "estimated");
                impact.put("basis", "statistics");
                impact.put("from", observedSpan(report, "from"));
                impact.put("to", observedSpan(report, "to"));
                impact.put("meterCount", dataMeters(report));
                impact.put("value", ((Map<?, ?>) report.getOrDefault("total", Map.of())).get("value"));
                impact.put("unit", String.valueOf(((Map<?, ?>) report.getOrDefault("total", Map.of())).get("unit")));
                impact.put("estimated", true);
                impact.put("reason", "仅反映关联电表区间用电，无法拆分空调设定温度调整的因果贡献");
                return impact;
            }
        }
        Map<String, Object> unavailable = new LinkedHashMap<>();
        unavailable.put("status", "unavailable");
        unavailable.put("reason", adjustedDevices > 0
                ? "已调温，但缺少可用的调前设定温度或调温时长不足，暂不展示估算数字"
                : "本次没有实际调温，且没有可比较的电表事实");
        return unavailable;
    }

    /** Estimate each accepted adjustment only until its next checkpoint, never the whole task repeatedly. */
    private static Map<String, Object> estimateAcSavings(List<Map<String, Object>> blocks, long adjustedDevices, String windowEnd) {
        Instant end;
        try { end = Instant.parse(windowEnd); }
        catch (RuntimeException invalid) { return null; }
        Map<String, List<EstimateSegment>> perDevice = new LinkedHashMap<>();
        for (Map<String, Object> block : blocks) {
            if (!"ac_inspection".equals(block.get("type")) || !"adjust_to_26".equals(block.get("mode"))) continue;
            Object criteria = block.get("criteria");
            Object setpoint = criteria instanceof Map<?, ?> map ? map.get("setpoint") : null;
            double target = setpoint instanceof Map<?, ?> map && map.get("value") instanceof Number number
                    ? number.doubleValue() : 26;
            if (!(block.get("devices") instanceof List<?> devices)) continue;
            for (Object item : devices) {
                if (!(item instanceof Map<?, ?> device)) continue;
                Instant at, until = end;
                try {
                    at = Instant.parse(String.valueOf(device.get("observedAt") == null
                            ? block.get("observedAt") : device.get("observedAt")));
                    if (block.get("estimateUntil") != null) {
                        Instant bound = Instant.parse(String.valueOf(block.get("estimateUntil")));
                        if (bound.isBefore(until)) until = bound;
                    }
                } catch (RuntimeException invalid) { continue; }
                double before = device.get("setpoint") instanceof Number number ? number.doubleValue() : Double.NaN;
                boolean adjusted = "adjusted".equals(device.get("disposition"))
                        && Double.isFinite(before) && before >= 16 && before < target;
                perDevice.computeIfAbsent(String.valueOf(device.get("deviceId")), ignored -> new ArrayList<>())
                        .add(new EstimateSegment(at, until, before, target, adjusted));
            }
        }
        double degreeHours = 0, deviceHours = 0, beforeHours = 0, targetHours = 0;
        Instant first = end, last = Instant.MIN;
        long count = 0, segments = 0;
        for (List<EstimateSegment> values : perDevice.values()) {
            values.sort(java.util.Comparator.comparing(EstimateSegment::at));
            boolean included = false;
            for (int i = 0; i < values.size(); i++) {
                EstimateSegment segment = values.get(i);
                Instant until = segment.until();
                if (i + 1 < values.size() && values.get(i + 1).at().isBefore(until)) until = values.get(i + 1).at();
                if (!segment.adjusted() || !until.isAfter(segment.at())) continue;
                double hours = java.time.Duration.between(segment.at(), until).toMillis() / 3_600_000.0;
                degreeHours += (segment.target() - segment.before()) * hours;
                deviceHours += hours;
                beforeHours += segment.before() * hours;
                targetHours += segment.target() * hours;
                if (segment.at().isBefore(first)) first = segment.at();
                if (until.isAfter(last)) last = until;
                included = true;
                segments++;
            }
            if (included) count++;
        }
        double kwh = degreeHours * 0.07 * 2.5;
        if (kwh < 0.005) return null;
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("status", "estimated");
        impact.put("basis", "assumption");
        impact.put("estimated", true);
        impact.put("from", first.toString());
        impact.put("to", last.toString());
        impact.put("deviceCount", count);
        impact.put("checkpointCount", segments);
        impact.put("degreeHours", Math.round(degreeHours * 10000) / 10000.0);
        impact.put("value", Math.round(kwh * 100) / 100.0);
        impact.put("unit", "度");
        impact.put("cost", Map.of("value", Math.round(kwh * 0.8 * 100) / 100.0, "unit", "元"));
        impact.put("baseline", Map.of("averageSetpoint", round1(beforeHours / deviceHours),
                "target", round1(targetHours / deviceHours), "averageRise", round1(degreeHours / deviceHours)));
        impact.put("assumptions", Map.of("ratePerDegree", 0.07, "powerKw", 2.5, "pricePerKwh", 0.8));
        impact.put("coverage", Map.of("adjusted", adjustedDevices, "estimated", count));
        impact.put("reason", "按各检查点调温指令、调前温度与不重叠时段估算，非电表实测");
        return impact;
    }

    private record EstimateSegment(Instant at, Instant until, double before, double target, boolean adjusted) { }

    /**
     * Defensive String-keyed copy that keeps null facts. Collectors.toMap
     * rejects null values, yet report facts legally carry them.
     */
    private static Map<String, Object> stringKeyedCopy(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, fact) -> copy.put(String.valueOf(key), fact));
        return copy;
    }

    private static boolean truncated(Map<String, Object> report) {
        return report.get("coverage") instanceof Map<?, ?> coverage
                && Boolean.TRUE.equals(coverage.get("truncated"));
    }

    private static long dataMeters(Map<String, Object> report) {
        return report.get("coverage") instanceof Map<?, ?> coverage
                && coverage.get("dataMeters") instanceof Number number ? number.longValue() : 0;
    }

    private static String observedSpan(Map<String, Object> report, String field) {
        Object params = report.get("params");
        if (params instanceof Map<?, ?> span
                && span.get("span") instanceof Map<?, ?> observed
                && observed.get(field) != null) {
            return String.valueOf(observed.get(field));
        }
        return null;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> phaseList(Map<String, Object> report) {
        return report.get("phases") instanceof List<?> phases
                ? (List<Map<String, Object>>) phases : List.of();
    }
}
