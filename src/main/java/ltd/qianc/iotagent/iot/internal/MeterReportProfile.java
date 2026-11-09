package ltd.qianc.iotagent.iot.internal;

import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.DAY;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.HOUR;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.format;
import static ltd.qianc.iotagent.iot.internal.MeterReportWindows.round;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.internal.CompanyEnergyObservationComposerImpl.MeterRead;
import ltd.qianc.iotagent.iot.internal.CompanyEnergyObservationComposerImpl.Sample;

/** Runtime sideAggregate and hourlyAllocation, shared by statistics and both comparison axes. */
final class MeterReportProfile {
    private final List<MeterRead> counted = new ArrayList<>();
    private final Map<String, MeterReportWindows.Series> series = new HashMap<>();
    private final double[] hourEnergy = new double[24], hourCovered = new double[24];
    private final Map<Long, Double> slotEnergy = new TreeMap<>(), slotCovered = new HashMap<>();
    private final long from, to, effectiveTo;
    private final double total, coveredFraction, effectiveDays;
    private final Double tariff;
    private final Map<String, Object> derived = new LinkedHashMap<>();
    private final List<Double> rates;

    MeterReportProfile(List<Map<String, Object>> rows, List<MeterRead> reads, long from, long to,
            long effectiveTo, Double tariff) {
        this.from = from; this.to = to; this.effectiveTo = effectiveTo; this.tariff = tariff;
        Map<String, MeterRead> byId = new HashMap<>();
        for (MeterRead read : reads) byId.put(read.device().id(), read);
        double sum = 0, covered = 0;
        for (Map<String, Object> row : rows) {
            if (!(row.get("value") instanceof Number value)) continue;
            sum += value.doubleValue();
            MeterRead read = byId.get(String.valueOf(row.get("id")));
            if (read == null) continue;
            counted.add(read); series.put(read.device().id(), new MeterReportWindows.Series(read.samples()));
            List<Sample> samples = read.samples();
            for (int index = 1; index < samples.size(); index++) {
                Sample a = samples.get(index - 1), b = samples.get(index);
                long t0 = a.readTime().toEpochMilli(), t1 = b.readTime().toEpochMilli(), duration = t1 - t0;
                double delta = b.value() - a.value();
                if (duration <= 0 || duration > 6 * HOUR || delta < 0) continue;
                long hi = Math.min(t1, to);
                for (long cursor = Math.max(t0, from); cursor < hi;) {
                    long hourStart = Math.floorDiv(cursor, HOUR) * HOUR;
                    long end = Math.min(hourStart + HOUR, hi);
                    double energy = delta * (end - cursor) / duration;
                    int hour = Instant.ofEpochMilli(cursor).atOffset(ZoneOffset.ofHours(8)).getHour();
                    hourEnergy[hour] += energy; hourCovered[hour] += end - cursor;
                    slotEnergy.merge(hourStart, energy, Double::sum);
                    slotCovered.merge(hourStart, (double) (end - cursor), Double::sum);
                    covered += end - cursor; cursor = end;
                }
            }
        }
        total = round(sum);
        long effectiveSpan = Math.max(HOUR, effectiveTo - from);
        effectiveDays = effectiveSpan / (double) DAY;
        coveredFraction = counted.isEmpty() ? 0 : covered / (counted.size() * (double) effectiveSpan);
        if (coveredFraction >= 0.5) {
            rates = new ArrayList<>(24);
            for (int hour = 0; hour < 24; hour++) rates.add(hourCovered[hour] > 0
                    ? round(hourEnergy[hour] / (hourCovered[hour] / HOUR) * counted.size()) : null);
        } else rates = null;
        derived.put("dayTotal", counted.isEmpty() ? null : total);
        derived.put("dailyAvg", counted.isEmpty() ? null : round(total / effectiveDays));
        double weekdayUsage = 0, nightUsage = 0; int weekdays = 0, nights = 0;
        Map<String, Object> peakDay = null, peakHour = null;
        for (long start = (long) Math.ceil(from / (double) DAY) * DAY;
                start + DAY <= effectiveTo && !counted.isEmpty(); start += DAY) {
            Double usage = complete(start, start + DAY, false);
            if (usage == null) continue;
            String day = format(start + DAY).substring(0, 10);
            if (peakDay == null || usage > ((Number) peakDay.get("value")).doubleValue()) {
                peakDay = new LinkedHashMap<>(); peakDay.put("value", round(usage)); peakDay.put("sub", day);
            }
            int weekday = Instant.ofEpochMilli(start + DAY).atOffset(ZoneOffset.ofHours(8)).getDayOfWeek().getValue();
            if (weekday <= 5) { weekdays++; weekdayUsage += usage; }
        }
        for (long start = (long) Math.ceil((from - 12 * HOUR) / (double) DAY) * DAY + 12 * HOUR;
                start + 12 * HOUR <= to && !counted.isEmpty(); start += DAY) {
            Double usage = complete(start, start + 12 * HOUR, true);
            if (usage != null) { nightUsage += usage; nights++; }
        }
        if (coveredFraction >= 0.5) for (Map.Entry<Long, Double> slot : slotEnergy.entrySet()) {
            if (peakHour == null || slot.getValue() > ((Number) peakHour.get("value")).doubleValue()) {
                peakHour = new LinkedHashMap<>(); peakHour.put("value", round(slot.getValue()));
                peakHour.put("sub", String.format("%02d:00", Instant.ofEpochMilli(slot.getKey()).atOffset(ZoneOffset.ofHours(8)).getHour()));
            }
        }
        derived.put("weekdayDailyAvg", weekdays > 0 ? round(weekdayUsage / weekdays) : null);
        derived.put("nightBase", nights > 0 ? round(nightUsage / nights / 12) : null);
        derived.put("peakDay", peakDay); derived.put("peakHour", peakHour);
    }

    Map<String, Object> total() {
        Map<String, Object> money = new LinkedHashMap<>();
        money.put("value", total); money.put("unit", "度");
        money.put("yuan", tariff == null ? null : round(total * tariff));
        return money;
    }
    Map<String, Object> shape() {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("rates", rates); shape.put("coveredFraction", round(coveredFraction));
        return shape;
    }
    Map<String, Object> side() {
        return new LinkedHashMap<>(Map.of("total", total(), "dataMeters", counted.size(), "effectiveDays", round(effectiveDays),
                "shape24", shape(), "buckets", Map.of("grain", to - from <= DAY ? "hour" : "day", "items", buckets(to - from <= DAY ? "hour" : "day"))));
    }
    Map<String, Object> derived() { return derived; }
    List<Map<String, Object>> buckets(String grain) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MeterReportWindows.Window window : MeterReportWindows.buckets(from, to, grain)) {
            Double value = null; boolean partial = !window.full();
            if ("hour".equals(grain)) {
                long start = Math.floorDiv(window.from(), HOUR) * HOUR;
                double covered = slotCovered.getOrDefault(start, 0d);
                if (covered > 0) value = round(slotEnergy.getOrDefault(start, 0d));
                partial |= covered < counted.size() * (double) (window.to() - window.from());
            } else {
                double sum = 0; int valid = 0;
                for (MeterRead read : counted) {
                    boolean advanced = read.samples().stream().anyMatch(sample -> sample.readTime().toEpochMilli() > window.from()
                            && sample.readTime().toEpochMilli() <= window.to());
                    Double cell = advanced ? MeterReportWindows.difference(read.samples(), window.from(), window.to()).value() : null;
                    if (cell != null) { sum += cell; valid++; }
                }
                if (valid > 0) value = round(sum);
                partial |= valid < counted.size() || window.to() > effectiveTo;
            }
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", format(window.from())); bucket.put("to", format(window.to())); bucket.put("value", value);
            bucket.put("partial", partial); bucket.put("days", Math.max(1, Math.round((Math.min(window.to(), effectiveTo) - window.from()) / (double) DAY)));
            result.add(bucket);
        }
        return result;
    }
    Double complete(long start, long end, boolean night) {
        if (counted.isEmpty()) return null;
        double total = 0;
        for (MeterRead read : counted) {
            var indexed = series.get(read.device().id());
            Double value = night ? indexed.night(start, end) : indexed.complete(start, end);
            if (value == null) return null;
            total += value;
        }
        return total;
    }
    static long effectiveEnd(List<MeterRead> reads, long from, long to) {
        long latest = Long.MIN_VALUE;
        for (MeterRead read : reads) for (Sample sample : read.samples()) {
            long at = sample.readTime().toEpochMilli();
            if (at > from && at <= to && at > latest) latest = at;
        }
        return latest > from && to - latest > DAY ? latest : to;
    }
    static List<Map<String, Object>> rows(List<AuthorizedDevice> meters, List<MeterRead> reads,
            long from, long to, long effectiveTo) {
        Map<String, MeterRead> byId = new HashMap<>();
        for (MeterRead read : reads) byId.put(read.device().id(), read);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AuthorizedDevice meter : meters) {
            MeterRead read = byId.get(meter.id());
            List<Sample> samples = read == null ? List.of() : read.samples();
            var window = MeterReportWindows.difference(samples, from, to);
            boolean inSpan = samples.stream().anyMatch(sample -> sample.readTime().toEpochMilli() >= from && sample.readTime().toEpochMilli() <= to);
            boolean stale = window.to() == null || effectiveTo - window.to().readTime().toEpochMilli() > Math.max(HOUR, effectiveTo - from) / 2d;
            String status = read == null || read.truncated() ? "partial"
                    : !inSpan ? "noData" : window.value() == null || stale ? "partial" : "ok";
            Map<String, Object> row = new LinkedHashMap<>(); row.put("id", meter.id()); row.put("name", meter.name());
            row.put("areaName", meter.installAddress()); row.put("floor", floor(meter.installAddress()));
            row.put("status", status); row.put("value", "ok".equals(status) ? window.value() : null);
            Map<String, Object> observed = new LinkedHashMap<>();
            observed.put("fromSample", sample(window.from())); observed.put("toSample", sample(window.to())); row.put("observed", observed); rows.add(row);
        }
        return rows;
    }
    private static Object sample(Sample sample) { return sample == null ? null : Map.of("readTime", format(sample.readTime().toEpochMilli()), "value", sample.value()); }
    private static final java.util.regex.Pattern FLOOR = java.util.regex.Pattern.compile("^(\\d+楼)");
    private static String floor(String address) { var matcher = FLOOR.matcher(address); return matcher.find() ? matcher.group(1) : null; }
}
