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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> estimateAcSavings(List<Map<String, Object>> blocks, long adjustedDevices, String windowEnd) {
        Instant end;
        try { end = Instant.parse(windowEnd); }
        catch (RuntimeException invalid) { return null; }
        Map<String, double[]> firstAdjusted = new LinkedHashMap<>();
        for (Map<String, Object> block : blocks) {
            if (!"ac_inspection".equals(String.valueOf(block.get("type")))
                    || !"adjust_to_26".equals(String.valueOf(block.get("mode")))) continue;
            Object criteria = ((Map<String, Object>) block.getOrDefault("criteria", Map.of())).get("setpoint");
            double target = criteria instanceof Map<?, ?> setpoint && setpoint.get("value") instanceof Number number
                    ? number.doubleValue() : 26;
            if (!(block.get("devices") instanceof List<?> devices)) continue;
            for (Object item : devices) {
                if (!(item instanceof Map<?, ?> device)) continue;
                if (!"adjusted".equals(String.valueOf(device.get("disposition")))
                        || firstAdjusted.containsKey(String.valueOf(device.get("deviceId")))) continue;
                Object setpointRaw = device.get("setpoint");
                Object observedAt = device.get("observedAt");
                if (observedAt == null) observedAt = block.get("observedAt");
                if (!(setpointRaw instanceof Number setpoint) || setpoint.doubleValue() < 16
                        || setpoint.doubleValue() >= target) continue;
                Instant at;
                try { at = Instant.parse(String.valueOf(observedAt)); }
                catch (RuntimeException missing) { continue; }
                if (!at.isBefore(end)) continue;
                firstAdjusted.put(String.valueOf(device.get("deviceId")),
                        new double[]{setpoint.doubleValue(), at.toEpochMilli(), target});
            }
        }
        if (firstAdjusted.isEmpty()) return null;
        double kwh = 0, deltaSum = 0, beforeSum = 0;
        double firstAt = Double.POSITIVE_INFINITY, targetValue = Double.NaN;
        for (double[] device : firstAdjusted.values()) {
            double delta = device[2] - device[0];
            kwh += delta * 0.07 * 2.5 * (end.toEpochMilli() - device[1]) / 3_600_000;
            deltaSum += delta;
            beforeSum += device[0];
            firstAt = Math.min(firstAt, device[1]);
            if (Double.isNaN(targetValue)) targetValue = device[2];
        }
        if (kwh < 0.1) return null;
        Map<String, Object> impact = new LinkedHashMap<>();
        int count = firstAdjusted.size();
        impact.put("status", "estimated");
        impact.put("basis", "assumption");
        impact.put("from", Instant.ofEpochMilli((long) firstAt).toString());
        impact.put("to", windowEnd);
        impact.put("deviceCount", (long) count);
        impact.put("value", round1(kwh));
        impact.put("unit", "度");
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("value", round1(kwh * 0.8));
        cost.put("unit", "元");
        impact.put("cost", cost);
        impact.put("estimated", true);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("averageSetpoint", round1(beforeSum / count));
        baseline.put("target", round1(targetValue));
        baseline.put("averageRise", round1(deltaSum / count));
        impact.put("baseline", baseline);
        Map<String, Object> assumptions = new LinkedHashMap<>();
        assumptions.put("ratePerDegree", 0.07);
        assumptions.put("powerKw", 2.5);
        assumptions.put("pricePerKwh", 0.8);
        impact.put("assumptions", assumptions);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("adjusted", adjustedDevices);
        coverage.put("estimated", (long) count);
        impact.put("coverage", coverage);
        impact.put("reason", "按调前设定温度与经验参数估算，非电表实测");
        return impact;
    }

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
