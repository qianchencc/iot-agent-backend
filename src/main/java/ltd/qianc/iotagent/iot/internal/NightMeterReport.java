package ltd.qianc.iotagent.iot.internal;

import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.DAY;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.HOUR;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.format;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.round;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.internal.CompanyEnergyObservationComposerImpl.MeterRead;
import ltd.qianc.iotagent.iot.internal.CompanyEnergyObservationComposerImpl.Sample;
import ltd.qianc.iotagent.iot.internal.MeterReportWindows.Window;

/** Runtime nightStatisticsReport: complete Shanghai 20:00–08:00 windows and paired metering days. */
final class NightMeterReport {
    private static final java.util.regex.Pattern FLOOR = java.util.regex.Pattern.compile("^(\\d+楼)");
    private NightMeterReport() { }

    static Map<String, Object> build(Map<String, Object> report, ObservationRequest request,
            List<AuthorizedDevice> meters, List<MeterRead> reads, long from, long to) {
        List<Window> windows = new ArrayList<>();
        for (long start = (long) Math.ceil((from - 12 * HOUR) / (double) DAY) * DAY + 12 * HOUR;
                start + 12 * HOUR <= to; start += DAY) windows.add(new Window(start, start + 12 * HOUR, true));
        Map<String, MeterRead> byId = new HashMap<>();
        for (MeterRead read : reads) byId.put(read.device().id(), read);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> noData = new ArrayList<>();
        List<Map<String, Object>> partial = new ArrayList<>();
        double[] bucketUsage = new double[windows.size()];
        int[] bucketValid = new int[windows.size()];
        int counted = 0, validMeterNights = 0, pairedMeterDays = 0;
        double total = 0, pairedNightUsage = 0, pairedDayUsage = 0;
        boolean truncated = false;
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (AuthorizedDevice meter : meters) {
            MeterRead read = byId.get(meter.id());
            List<Sample> samples = read == null ? List.of() : read.samples();
            truncated |= read != null && read.truncated();
            var series = new MeterReportWindows.Series(samples);
            List<Map<String, Object>> cells = new ArrayList<>();
            int valid = 0, shared = 0;
            double usage = 0, shareUsage = 0, dayUsage = 0;
            Long first = null, last = null;
            for (int index = 0; index < windows.size(); index++) {
                Window window = windows.get(index);
                Double value = series.complete(window.from(), window.to());
                Double dayValue = window.to() - DAY >= from ? series.complete(window.to() - DAY, window.to()) : null;
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("from", format(window.from())); cell.put("to", format(window.to()));
                cell.put("value", value); cell.put("dayValue", dayValue); cells.add(cell);
                if (value == null) continue;
                valid++; usage += value;
                bucketUsage[index] += value; bucketValid[index]++;
                if (first == null) first = window.from();
                last = window.to();
                if (dayValue != null) { shared++; shareUsage += value; dayUsage += dayValue; }
            }
            boolean inSpan = samples.stream().anyMatch(sample -> sample.readTime().toEpochMilli() >= from
                    && sample.readTime().toEpochMilli() <= to);
            String status = valid > 0 && valid == windows.size() ? "ok" : valid > 0 || inSpan ? "partial" : "noData";
            Map<String, Object> night = new LinkedHashMap<>();
            night.put("usage", valid > 0 ? round(usage) : null);
            night.put("validDays", valid); night.put("expectedDays", windows.size());
            night.put("shareUsage", shareUsage); night.put("dayUsage", dayUsage); night.put("shareDays", shared);
            night.put("share", dayUsage > 0 ? round(shareUsage / dayUsage * 100) : null); night.put("windows", cells);
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("fromSample", exactSample(samples, first)); observed.put("toSample", exactSample(samples, last));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", meter.id()); row.put("name", meter.name()); row.put("areaName", meter.installAddress());
            row.put("floor", floor(meter.installAddress())); row.put("value", valid > 0 ? round(usage) : null);
            row.put("status", status); row.put("observed", observed); row.put("night", night); rows.add(row);
            if ("noData".equals(status)) noData.add(meter.id());
            else if (valid < windows.size() || windows.isEmpty()) {
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("id", meter.id()); missing.put("through", last == null ? null : format(last)); partial.add(missing);
            }
            if (valid > 0) {
                counted++; total += round(usage);
                String label = meter.installAddress().isBlank() ? meter.name() : meter.installAddress();
                Map<String, Object> group = groups.computeIfAbsent(label, key -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("label", key); value.put("value", 0d); value.put("meterIds", new ArrayList<String>()); return value;
                });
                group.put("value", ((Number) group.get("value")).doubleValue() + round(usage));
                @SuppressWarnings("unchecked") List<String> ids = (List<String>) group.get("meterIds");
                ids.add(meter.id());
            }
            validMeterNights += valid; pairedMeterDays += shared;
            pairedNightUsage += shareUsage; pairedDayUsage += dayUsage;
        }
        List<Map<String, Object>> buckets = new ArrayList<>();
        Long observedFrom = null, observedTo = null;
        double fullUsage = 0;
        int fullNights = 0;
        Map<String, Object> peak = null;
        for (int index = 0; index < windows.size(); index++) {
            Window window = windows.get(index);
            Double value = bucketValid[index] > 0 ? round(bucketUsage[index]) : null;
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(window.from())); bucket.put("to", format(window.to())); bucket.put("value", value);
            bucket.put("partial", bucketValid[index] < meters.size()); bucket.put("days", 1); buckets.add(bucket);
            if (value != null) { if (observedFrom == null) observedFrom = window.from(); observedTo = window.to(); }
            if (value != null && bucketValid[index] == meters.size()) {
                fullNights++; fullUsage += value;
                if (peak == null || value > ((Number) peak.get("value")).doubleValue()) peak = bucket;
            }
        }
        String grain = request.parameters().get("grain");
        if (grain == null || "hour".equals(grain)) grain = to - from <= 14 * DAY ? "day" : to - from <= 91 * DAY ? "week" : "month";
        Map<String, List<Map<String, Object>>> bucketsAll = Map.of("hour", List.of(), "day", buckets,
                "week", aggregate(windows, buckets, from, to, "week"), "month", aggregate(windows, buckets, from, to, "month"));
        @SuppressWarnings("unchecked") Map<String, Object> params = (Map<String, Object>) report.get("params");
        Map<String, Object> observedSpan = new LinkedHashMap<>();
        observedSpan.put("from", observedFrom == null ? null : format(observedFrom));
        observedSpan.put("to", observedTo == null ? null : format(observedTo));
        params.put("span", Map.of("requested", Map.of("from", format(from), "to", format(to)), "observed", observedSpan));
        params.put("grain", grain); params.put("dailyWindow", Map.of("from", "20:00", "to", "08:00", "timezone", "Asia/Shanghai"));
        Map<String, Object> totalView = new LinkedHashMap<>();
        Double tariff = MeterReports.tariff(meters);
        totalView.put("value", round(total)); totalView.put("unit", "度");
        totalView.put("yuan", tariff == null ? null : round(total * tariff)); totalView.put("tariff", tariff);
        report.put("total", totalView); report.put("meters", rows); report.put("bucketsAll", bucketsAll);
        report.put("buckets", Map.of("grain", grain, "items", bucketsAll.get(grain)));
        Map<String, Object> shape = new LinkedHashMap<>(); shape.put("rates", null); shape.put("coveredFraction", 0);
        report.put("shape24", shape); report.put("context", Map.of("kind", "floorSplit", "floors", List.of()));
        report.put("windowSummary", Map.of("expectedNights", windows.size(), "validMeterNights", validMeterNights,
                "expectedMeterNights", meters.size() * windows.size(), "pairedMeterDays", pairedMeterDays,
                "pairedNightUsage", round(pairedNightUsage), "pairedDayUsage", round(pairedDayUsage)));
        Map<String, Object> daily = metric("dailyAvg", fullNights == windows.size() && fullNights > 0 ? round(total / fullNights) : null, "度/夜");
        Map<String, Object> base = metric("nightBase", fullNights > 0 ? round(fullUsage / fullNights / 12) : null, "度/时");
        base.put("nightWindow", "20:00-08:00");
        Map<String, Object> peakMetric = metric("peakDay", peak == null ? null : (Double) peak.get("value"), "度");
        if (peak != null) peakMetric.put("sub", String.valueOf(peak.get("to")).substring(0, 10));
        Map<String, Object> share = metric("nightShare", pairedDayUsage > 0 ? round(pairedNightUsage / pairedDayUsage * 100) : null, "%");
        share.put("nightWindow", "20:00-08:00"); share.put("dayWindow", "previous08:00-08:00");
        report.put("metrics", List.of(daily, base, peakMetric, share));
        List<Map<String, Object>> ranked = new ArrayList<>(groups.values());
        for (Map<String, Object> group : ranked) group.put("value", round(((Number) group.get("value")).doubleValue()));
        ranked.sort(Comparator.comparingDouble((Map<String, Object> row) -> ((Number) row.get("value")).doubleValue()).reversed());
        Map<String, Object> composition = new LinkedHashMap<>();
        composition.put("kind", "ranking"); composition.put("rows", ranked.subList(0, Math.min(8, ranked.size())));
        composition.put("rest", ranked.size() > 8 ? Map.of("count", ranked.size() - 8, "value", round(ranked.subList(8, ranked.size())
                .stream().mapToDouble(row -> ((Number) row.get("value")).doubleValue()).sum())) : null);
        Map<String, Object> excluded = new LinkedHashMap<>(); excluded.put("noData", noData.size()); excluded.put("partial", partial.size()); excluded.put("through", null);
        composition.put("excluded", excluded); report.put("composition", composition);
        report.put("coverage", Map.of("meters", meters.size(), "dataMeters", counted, "noData", noData, "partial", partial, "truncated", truncated));
        return MeterReports.enrich(report, reads, from, to);
    }

    private static List<Map<String, Object>> aggregate(List<Window> nights, List<Map<String, Object>> values,
            long from, long to, String grain) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Window window : MeterReportWindows.buckets(from, to, grain)) {
            double sum = 0; int valid = 0, parts = 0; boolean partial = !window.full();
            for (int index = 0; index < nights.size(); index++) {
                long at = nights.get(index).from();
                if (at < window.from() || at >= window.to()) continue;
                parts++;
                Map<String, Object> night = values.get(index);
                if (night.get("value") instanceof Number value) { sum += value.doubleValue(); valid++; }
                else partial = true;
                partial |= Boolean.TRUE.equals(night.get("partial"));
            }
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(window.from())); bucket.put("to", format(window.to()));
            bucket.put("value", valid > 0 ? round(sum) : null); bucket.put("partial", partial); bucket.put("days", parts); result.add(bucket);
        }
        return result;
    }

    private static Object exactSample(List<Sample> samples, Long at) {
        if (at == null) return null;
        for (Sample sample : samples) if (sample.readTime().toEpochMilli() == at) return Map.of("readTime", format(at), "value", sample.value());
        return null;
    }
    private static String floor(String address) {
        var matcher = FLOOR.matcher(address);
        return matcher.find() ? matcher.group(1) : null;
    }
    private static Map<String, Object> metric(String key, Double value, String unit) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("key", key); result.put("value", value); result.put("unit", unit); return result;
    }
}
