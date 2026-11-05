package ltd.qianc.iotagent.iot.internal;

import static ltd.qianc.iotagent.iot.internal.MeterReportLayout.*;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.DAY;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.HOUR;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.format;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.round;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.internal.CompanyEnergyObservationComposerImpl.MeterRead;

/** Pure Java port of Runtime energy/meter-report.ts statistics/comparison/regionReport. */
final class MeterReports {
    private MeterReports() { }
    static Map<String, Object> statistics(Map<String, Object> report, Map<String, String> parameters,
            Map<String, Object> scope, List<AuthorizedDevice> selected, List<MeterRead> reads, long from, long to) {
        long effectiveTo = MeterReportProfile.effectiveEnd(reads, from, to);
        List<Map<String, Object>> catalog = catalog(reads, from, to, effectiveTo), rows = select(catalog, selected);
        MeterReportProfile profile = new MeterReportProfile(rows, reads, from, to, effectiveTo, tariff(selected));
        Map<String, Object> totalAmount = profile.total(), derived = profile.derived();
        String grain = parameters.getOrDefault("grain", to - from <= DAY ? "hour" : to - from <= 14 * DAY ? "day" : to - from <= 91 * DAY ? "week" : "month");
        Map<String, Object> allBuckets = new LinkedHashMap<>();
        for (String key : List.of("hour", "day", "week", "month")) allBuckets.put(key, profile.buckets(key));
        Map<String, Object> params = new LinkedHashMap<>(); params.put("span", span(from, to, rows)); params.put("scope", scope); params.put("grain", grain);
        report.put("params", params); report.put("total", totalAmount); totalAmount.put("tariff", tariff(selected));
        report.put("bucketsAll", allBuckets); report.put("buckets", Map.of("grain", grain, "items", allBuckets.get(grain)));
        report.put("shape24", profile.shape()); report.put("meters", rows);
        report.put("coverage", coverage(rows, truncated(reads))); report.put("authorizationMeterIds", ids(reads));
        Map<String, Object> summary = new LinkedHashMap<>();
        long okCount = rows.stream().filter(MeterReportLayout::ok).count();
        summary.put("value", totalAmount.get("value")); summary.put("unit", "度");
        summary.put("dataMeters", (int) okCount); summary.put("meters", rows.size());
        summary.put("partial", okCount != rows.size());
        report.put("summary", summary);
        double days = Math.max(HOUR, effectiveTo - from) / (double) DAY;
        String level = String.valueOf(scope.get("level"));
        report.put("context", context(level, rows, catalog, days));
        List<Map<String, Object>> composed = rows; String highlighted = null;
        if ("location".equals(level)) {
            Map<String, Object> self = rows.get(0);
            composed = catalog.stream().filter(row -> Objects.equals(row.get("floor"), self.get("floor"))).toList(); highlighted = label(self);
            MeterReportProfile peers = new MeterReportProfile(composed, reads, from, to, effectiveTo, null);
            Map<String, Object> peerShape = peers.shape();
            Object rates = peerShape.get("rates");
            long n = composed.stream().filter(MeterReportLayout::ok).count();
            Map<String, Object> peer = new LinkedHashMap<>(); peer.put("floorPerMeterRates", rates instanceof List<?> list && n > 0 ? perMeter(list, n) : null);
            report.put("peer", peer);
        }
        report.put("composition", composition(composed, 8, highlighted));
        boolean singleDay = to - from <= DAY;
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric(singleDay ? "dayTotal" : "dailyAvg", derived.get(singleDay ? "dayTotal" : "dailyAvg"), singleDay ? "度" : "度/日"));
        metrics.add(metric("nightBase", derived.get("nightBase"), "度/时"));
        metrics.add(metric(singleDay ? "peakHour" : "peakDay", derived.get(singleDay ? "peakHour" : "peakDay"), "度"));
        if ("location".equals(level)) {
            double night = 0, day = 0;
            for (long start = (long) Math.ceil(from / (double) DAY) * DAY; start + DAY <= effectiveTo; start += DAY) {
                Double dayValue = profile.complete(start, start + DAY, false), nightValue = profile.complete(start + 12 * HOUR, start + DAY, false);
                if (dayValue != null && nightValue != null) { day += dayValue; night += nightValue; }
            }
            metrics.add(metric("nightShare", day > 0 ? round(night / day * 100) : null, "%"));
        } else {
            double total = sum(rows), top = locations(rows).stream().limit(3).mapToDouble(MeterReportLayout::value).sum();
            metrics.add(metric("top3Share", total > 0 ? round(top / total * 100) : null, "%"));
        }
        report.put("metrics", metrics); return enrich(report, reads, from, to);
    }
    static Map<String, Object> region(Map<String, Object> report, Map<String, Object> scopeA, Map<String, Object> scopeB,
            List<AuthorizedDevice> a, List<AuthorizedDevice> b, List<MeterRead> reads, long from, long to) {
        long effectiveTo = MeterReportProfile.effectiveEnd(reads, from, to);
        List<Map<String, Object>> catalog = catalog(reads, from, to, effectiveTo), aRows = select(catalog, a), bRows = select(catalog, b);
        MeterReportProfile pa = new MeterReportProfile(aRows, reads, from, to, effectiveTo, tariff(a));
        MeterReportProfile pb = new MeterReportProfile(bRows, reads, from, to, effectiveTo, tariff(b));
        Map<String, Object> sa = pa.side(), sb = pb.side();
        long na = aRows.stream().filter(MeterReportLayout::ok).count(), nb = bRows.stream().filter(MeterReportLayout::ok).count();
        double days = Math.max(HOUR, effectiveTo - from) / (double) DAY;
        for (Map<String, Object> side : List.of(sa, sb)) {
            boolean isA = side == sa; List<Map<String, Object>> rows = isA ? aRows : bRows;
            Map<String, Object> scope = isA ? scopeA : scopeB;
            side.remove("dataMeters"); side.remove("effectiveDays"); side.put("scope", scope); side.put("meters", rows);
            Map<String, Object> coverage = new LinkedHashMap<>(coverage(rows, false)); coverage.remove("truncated"); side.put("coverage", coverage);
            side.put("context", context(String.valueOf(scope.get("level")), rows, catalog, days)); side.put("composition", composition(rows, 5, null));
        }
        if (na != nb || !scopeA.get("level").equals(scopeB.get("level")) || a.size() != b.size()) {
            normalize(sa, na); normalize(sb, nb);
        }
        Set<String> union = new HashSet<>(); a.forEach(meter -> union.add(meter.id())); b.forEach(meter -> union.add(meter.id()));
        Set<String> bIds = new HashSet<>(); b.forEach(meter -> bIds.add(meter.id()));
        List<Map<String, Object>> rows = catalog.stream().filter(row -> union.contains(row.get("id"))).toList();
        Map<String, Object> params = new LinkedHashMap<>(); params.put("axis", "region"); params.put("span", span(from, to, rows));
        params.put("scope", scopeA); params.put("other", scopeB); report.put("params", params);
        report.put("sides", Map.of("a", sa, "b", sb)); report.put("diff", diff(sum(aRows), sum(bRows), na > 0 && nb > 0, "对照范围为零", "数据不足", false));
        Map<String, Object> da = pa.derived(), db = pb.derived();
        boolean day = to - from <= DAY;
        report.put("metrics", List.of(metricPair("perMeterDaily", "度/台·日", na > 0 ? round(sum(aRows) / days / na) : null, nb > 0 ? round(sum(bRows) / days / nb) : null),
                metricPair(day ? "dayTotal" : "dailyAvg", day ? "度" : "度/日", da.get(day ? "dayTotal" : "dailyAvg"), db.get(day ? "dayTotal" : "dailyAvg")),
                metricPair("nightBase", "度/时", da.get("nightBase"), db.get("nightBase")),
                metricPair(day ? "peakHour" : "peakDay", "度", da.get(day ? "peakHour" : "peakDay"), db.get(day ? "peakHour" : "peakDay"))));
        report.put("overlapMeterIds", a.stream().map(AuthorizedDevice::id).filter(bIds::contains).toList());
        report.put("meters", rows); report.put("coverage", coverage(rows, truncated(reads))); report.put("authorizationMeterIds", ids(reads)); return enrich(report, reads, from, to);
    }
    static Map<String, Object> time(Map<String, Object> report, Map<String, Object> scope, List<AuthorizedDevice> selected,
            List<MeterRead> reads, long from, long to, long baseFrom, long baseTo) {
        long effA = MeterReportProfile.effectiveEnd(reads, from, to), effB = MeterReportProfile.effectiveEnd(reads, baseFrom, baseTo);
        List<Map<String, Object>> allA = catalog(reads, from, to, effA), allB = catalog(reads, baseFrom, baseTo, effB);
        Map<String, Map<String, Object>> bById = new LinkedHashMap<>(); allB.forEach(row -> bById.put(String.valueOf(row.get("id")), row));
        Set<String> selectedIds = new HashSet<>(); selected.forEach(meter -> selectedIds.add(meter.id()));
        List<Map<String, Object>> combined = new ArrayList<>(), contextRows = new ArrayList<>(), aRows = new ArrayList<>(), bRows = new ArrayList<>();
        List<Map<String, Object>> cohortA = new ArrayList<>(), cohortB = new ArrayList<>();
        for (Map<String, Object> a : allA) {
            Map<String, Object> b = bById.get(String.valueOf(a.get("id")));
            Map<String, Object> row = new LinkedHashMap<>();
            for (String key : List.of("id", "name", "areaName", "floor")) row.put(key, a.get(key));
            row.put("a", sideRow(a)); row.put("b", sideRow(b)); combined.add(row);
            Map<String, Object> contextRow = new LinkedHashMap<>(a); contextRow.put("status", ok(a) && ok(b) ? "ok" : "partial"); contextRows.add(contextRow);
            if (!selectedIds.contains(a.get("id"))) continue;
            aRows.add(a); bRows.add(b);
            if (ok(a) && ok(b)) { cohortA.add(a); cohortB.add(b); }
        }
        List<Map<String, Object>> countedA = cohortA.isEmpty() ? aRows.stream().filter(MeterReportLayout::ok).toList() : cohortA;
        List<Map<String, Object>> countedB = cohortB.isEmpty() ? bRows.stream().filter(MeterReportLayout::ok).toList() : cohortB;
        MeterReportProfile pa = new MeterReportProfile(countedA, reads, from, to, effA, tariff(selected));
        MeterReportProfile pb = new MeterReportProfile(countedB, reads, baseFrom, baseTo, effB, tariff(selected));
        boolean day = to - from <= DAY && baseTo - baseFrom <= DAY;
        long durationA = Math.max(HOUR, effA - from), durationB = Math.max(HOUR, effB - baseFrom);
        double daysA = durationA / (double) DAY, daysB = durationB / (double) DAY;
        boolean unequal = !day && durationA != durationB;
        double totalA = sum(countedA), totalB = sum(countedB);
        String missing = countedA.isEmpty() && !countedB.isEmpty() ? "本期无有效采样" : !countedA.isEmpty() && countedB.isEmpty() ? "基期无有效采样" : "数据不足";
        report.put("diff", diff(unequal ? totalA / daysA : totalA, unequal ? totalB / daysB : totalB, !cohortA.isEmpty(), "基期为零", missing, unequal));
        report.put("sides", Map.of("a", pa.side(), "b", pb.side()));
        Map<String, Object> da = pa.derived(), db = pb.derived(); List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metricPair(day ? "dayTotal" : "dailyAvg", day ? "度" : "度/日", da.get(day ? "dayTotal" : "dailyAvg"), db.get(day ? "dayTotal" : "dailyAvg")));
        if (!day) metrics.add(metricPair("weekdayDailyAvg", "度/日", da.get("weekdayDailyAvg"), db.get("weekdayDailyAvg")));
        metrics.add(metricPair("nightBase", "度/时", da.get("nightBase"), db.get("nightBase")));
        metrics.add(metricPair(day ? "peakHour" : "peakDay", "度", da.get(day ? "peakHour" : "peakDay"), db.get(day ? "peakHour" : "peakDay"))); report.put("metrics", metrics);
        List<Map<String, Object>> scopeContext = contextRows.stream().filter(row -> selectedIds.contains(row.get("id"))).toList();
        String level = String.valueOf(scope.get("level")); report.put("context", context(level, scopeContext, contextRows, daysA));
        List<Map<String, Object>> contributionSource = combined.stream().filter(row -> ok(map(row.get("a"))) && ok(map(row.get("b"))))
                .filter(row -> "location".equals(level) ? Objects.equals(row.get("floor"), aRows.get(0).get("floor")) : selectedIds.contains(row.get("id"))).toList();
        report.put("contribution", contributions(contributionSource, unequal, daysA, daysB, "location".equals(level) ? label(aRows.get(0)) : null));
        List<Map<String, Object>> rows = combined.stream().filter(row -> selectedIds.contains(row.get("id"))).toList();
        List<Map<String, Object>> statuses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> a = map(row.get("a")), b = map(row.get("b")); Map<String, Object> status = new LinkedHashMap<>(row);
            status.put("status", ok(a) && ok(b) ? "ok" : "noData".equals(a.get("status")) || "noData".equals(b.get("status")) ? "noData" : "partial");
            String throughA = sampleTime(a, "toSample"), throughB = sampleTime(b, "toSample");
            String through = throughA == null ? throughB : throughB == null || throughA.compareTo(throughB) > 0 ? throughA : throughB;
            status.put("observed", through == null ? Map.of() : Map.of("toSample", Map.of("readTime", through))); statuses.add(status);
        }
        Map<String, Object> params = new LinkedHashMap<>(); params.put("axis", "time"); params.put("scope", scope);
        params.put("relation", timeRelation(from, to, baseFrom, baseTo));
        params.put("span", span(from, to, countedA)); params.put("baseline", span(baseFrom, baseTo, countedB));
        report.put("params", params); report.put("meters", rows); report.put("coverage", coverage(statuses, truncated(reads))); report.put("authorizationMeterIds", ids(reads)); return enrich(report, reads, from, to);
    }
    private static Map<String, Object> contributions(List<Map<String, Object>> rows, boolean unequal, double daysA, double daysB, String highlight) {
        List<Map<String, Object>> deltas = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> delta = new LinkedHashMap<>(row); delta.put("status", "ok");
            delta.put("value", value(map(row.get("a"))) / (unequal ? daysA : 1) - value(map(row.get("b"))) / (unequal ? daysB : 1)); deltas.add(delta);
        }
        List<Map<String, Object>> all = locations(deltas);
        all.sort((a, b) -> Double.compare(Math.abs(value(b)), Math.abs(value(a))));
        List<Map<String, Object>> shown = new ArrayList<>(all.subList(0, Math.min(8, all.size())));
        for (Map<String, Object> row : all) if (Objects.equals(highlight, row.get("label"))) { row.put("highlight", true); if (!shown.contains(row)) shown.add(row); }
        double sumAbs = all.stream().mapToDouble(row -> Math.abs(value(row))).sum();
        Map<String, Object> rest = all.size() > shown.size() ? Map.of("count", all.size() - shown.size(), "delta", round(all.stream().filter(row -> !shown.contains(row)).mapToDouble(MeterReportLayout::value).sum())) : null;
        Double topShare = sumAbs > 0 ? round(all.stream().limit(3).mapToDouble(row -> Math.abs(value(row))).sum() / sumAbs * 100) : null;
        for (Map<String, Object> row : shown) row.put("delta", row.remove("value"));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("rows", shown); result.put("rest", rest); result.put("topShare", topShare); result.put("unit", unequal ? "度/日" : "度"); return result;
    }
    private static Map<String, Object> diff(double a, double b, boolean valid, String zero, String missing, boolean daily) {
        Map<String, Object> result = new LinkedHashMap<>(); double difference = round(a - b);
        result.put("value", valid ? difference : null); result.put("pct", valid && b > 0 ? Math.round(difference / b * 1000d) / 10d : null);
        if (!valid || b <= 0) result.put("pctNote", valid ? zero : missing);
        result.put("basis", daily ? "dailyAvg" : "total"); result.put("unit", daily ? "度/日" : "度"); return result;
    }
    private static Map<String, Object> sideRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(); for (String key : List.of("value", "status", "observed")) result.put(key, row.get(key)); return result;
    }
    private static List<Map<String, Object>> catalog(List<MeterRead> reads, long from, long to, long effectiveTo) {
        return MeterReportProfile.rows(reads.stream().map(MeterRead::device).toList(), reads, from, to, effectiveTo);
    }
    private static List<Map<String, Object>> select(List<Map<String, Object>> catalog, List<AuthorizedDevice> selected) {
        Set<String> ids = new HashSet<>(); selected.forEach(meter -> ids.add(meter.id())); return catalog.stream().filter(row -> ids.contains(row.get("id"))).toList();
    }
    private static List<String> ids(List<MeterRead> reads) { return reads.stream().map(read -> read.device().id()).toList(); }
    private static boolean truncated(List<MeterRead> reads) { return reads.stream().anyMatch(MeterRead::truncated); }
    private static Map<String, Object> span(long from, long to, List<Map<String, Object>> rows) { return Map.of("requested", Map.of("from", format(from), "to", format(to)), "observed", observed(rows)); }
    static Double tariff(List<AuthorizedDevice> meters) {
        Double common = null; boolean first = true;
        for (AuthorizedDevice meter : meters) {
            Double price = meter.metadata().get("tariff") instanceof Number value ? value.doubleValue() : null;
            if (!first && !Objects.equals(price, common)) return null; common = price; first = false;
        }
        return common;
    }
    static Map<String, Object> enrich(Map<String, Object> report, List<MeterRead> reads, long from, long to) {
        Map<String, MeterRead> byId = new LinkedHashMap<>();
        for (MeterRead read : reads) byId.put(read.device().id(), read);
        boolean complete = !truncated(reads);
        Map<String, Object> params = map(report.get("params"));
        boolean time = "time".equals(params.get("axis"));
        long baseFrom = 0, baseTo = 0;
        if (time) {
            Map<String, Object> baseline = map(map(params.get("baseline")).get("requested"));
            baseFrom = java.time.OffsetDateTime.parse(String.valueOf(baseline.get("from"))).toInstant().toEpochMilli();
            baseTo = java.time.OffsetDateTime.parse(String.valueOf(baseline.get("to"))).toInstant().toEpochMilli();
        }
        if (report.get("meters") instanceof List<?> rows) for (Object item : rows) {
            Map<String, Object> row = map(item);
            MeterRead read = byId.get(String.valueOf(row.get("id")));
            if (time) {
                map(row.get("a")).put("powerFactor", powerFactor(read, from, to, complete));
                map(row.get("b")).put("powerFactor", powerFactor(read, baseFrom, baseTo, complete));
            } else row.put("powerFactor", powerFactor(read, from, to, complete));
        }
        Map<String, Object> coverage = new LinkedHashMap<>(map(report.get("coverage")));
        Long latest = null;
        for (MeterRead read : reads) for (var sample : read.samples()) {
            long at = sample.readTime().toEpochMilli();
            if (latest == null || at > latest) latest = at;
        }
        coverage.put("latestSampleAt", latest == null ? null : format(latest));
        report.put("coverage", coverage);
        return report;
    }
    private static Map<String, Object> powerFactor(MeterRead read, long from, long to, boolean complete) {
        Map<Long, Double> slots = new LinkedHashMap<>();
        Set<Long> conflicts = new HashSet<>();
        if (read != null) for (var sample : read.samples()) {
            long at = sample.readTime().toEpochMilli();
            Double value = sample.powerFactor();
            if (at < from || at >= to || value == null || !Double.isFinite(value) || value < 0 || value > 1 || conflicts.contains(at)) continue;
            Double previous = slots.putIfAbsent(at, value);
            // Repeated samples must carry identical source values, not merely values within a tolerance.
            if (previous != null && Double.doubleToLongBits(previous) != Double.doubleToLongBits(value)) {
                slots.remove(at); conflicts.add(at);
            }
        }
        double total = 0, minimum = Double.POSITIVE_INFINITY;
        for (double value : slots.values()) { total += value; minimum = Math.min(minimum, value); }
        Map<String, Object> factor = new LinkedHashMap<>();
        factor.put("mean", slots.isEmpty() ? null : Math.round(total / slots.size() * 1000) / 1000d);
        factor.put("min", slots.isEmpty() ? null : minimum); factor.put("samples", slots.size()); factor.put("complete", complete);
        return factor;
    }
    private static String timeRelation(long from, long to, long baseFrom, long baseTo) {
        var zone = java.time.ZoneOffset.ofHours(8);
        var a = java.time.Instant.ofEpochMilli(from).atOffset(zone);
        var endA = java.time.Instant.ofEpochMilli(to).atOffset(zone);
        var b = java.time.Instant.ofEpochMilli(baseFrom).atOffset(zone);
        var endB = java.time.Instant.ofEpochMilli(baseTo).atOffset(zone);
        if (a.getYear() == b.getYear() + 1 && endA.getYear() == endB.getYear() + 1
                && a.getMonthValue() == b.getMonthValue() && a.getDayOfMonth() == b.getDayOfMonth()
                && endA.getMonthValue() == endB.getMonthValue() && endA.getDayOfMonth() == endB.getDayOfMonth()
                && a.toLocalTime().equals(b.toLocalTime()) && endA.toLocalTime().equals(endB.toLocalTime())) return "yearOverYear";
        Integer months = calendarMonths(a, endA);
        return baseTo == from && (months == null ? to - from == baseTo - baseFrom : months.equals(calendarMonths(b, endB)))
                ? "previousPeriod" : "custom";
    }
    private static Integer calendarMonths(java.time.OffsetDateTime from, java.time.OffsetDateTime to) {
        if (from.getDayOfMonth() != 1 || to.getDayOfMonth() != 1
                || !from.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) || !to.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) return null;
        return (to.getYear() - from.getYear()) * 12 + to.getMonthValue() - from.getMonthValue();
    }

    private static List<Double> perMeter(List<?> rates, long n) { List<Double> normalized = new ArrayList<>(rates.size()); for (Object rate : rates) normalized.add(rate instanceof Number value ? round(value.doubleValue() / n) : null); return normalized; }
    private static void normalize(Map<String, Object> side, long n) { Map<String, Object> shape = map(side.get("shape24")); if (n > 0 && shape.get("rates") instanceof List<?> rates) shape.put("rates", perMeter(rates, n)); shape.put("perMeter", true); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
