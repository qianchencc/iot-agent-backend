package ltd.qianc.iotagent.iot.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import ltd.qianc.iotagent.iot.internal.CompanyEnergyObservationComposerImpl.Sample;

/** Arithmetic from Runtime energy/meter-report.ts; no gateway or business-state writes. */
final class MeterReportWindows {
    static final long HOUR = 3_600_000L;
    static final long DAY = 24 * HOUR;
    private static final ZoneOffset SHANGHAI = ZoneOffset.ofHours(8);
    record Window(long from, long to, boolean full) { }
    record Difference(Sample from, Sample to, Double value) { }
    private MeterReportWindows() { }

    static String format(long at) { return Instant.ofEpochMilli(at).atOffset(SHANGHAI).toString(); }
    static double round(double value) { return Math.round((value + Math.ulp(1d)) * 100d) / 100d; }

    static Difference difference(List<Sample> samples, long from, long to) {
        Sample first = null, last = null;
        for (Sample sample : samples) {
            long at = sample.readTime().toEpochMilli();
            if (at <= from) first = sample;
            else if (first == null && at <= from + 12 * HOUR) first = sample;
            if (at <= to) last = sample;
        }
        if (first == null || last == null || !first.readTime().isBefore(last.readTime())) {
            return new Difference(first, last, null);
        }
        Sample previous = null;
        for (Sample sample : samples) {
            if (sample.readTime().isBefore(first.readTime()) || sample.readTime().isAfter(last.readTime())) continue;
            if (previous != null && (sample.value() < previous.value()
                    || sample.readTime().equals(previous.readTime()) && sample.value() != previous.value())) {
                return new Difference(first, last, null);
            }
            previous = sample;
        }
        double value = last.value() - first.value();
        return new Difference(first, last, value >= 0 ? round(value) : null);
    }

    /** Index conflicting timestamps once, not once per requested night or metering day. */
    static final class Series {
        private final long[] times;
        private final Double[] values;
        private final int size;
        private final List<Sample> samples;
        Series(List<Sample> samples) {
            this.samples = samples;
            times = new long[samples.size()];
            values = new Double[samples.size()];
            int length = 0;
            for (Sample sample : samples) {
                long at = sample.readTime().toEpochMilli();
                if (length > 0 && at < times[length - 1]) throw new IllegalArgumentException("Meter samples must be ordered");
                if (length > 0 && at == times[length - 1]) {
                    if (values[length - 1] != null && Double.compare(values[length - 1], sample.value()) != 0) values[length - 1] = null;
                } else {
                    times[length] = at;
                    values[length++] = sample.value();
                }
            }
            size = length;
        }
        Double complete(long from, long to) {
            Difference exact = difference(samples, from, to);
            if (exact.value() != null && exact.from().readTime().toEpochMilli() == from
                    && exact.to().readTime().toEpochMilli() == to) return exact.value();
            return interpolate(from, to, false);
        }
        Double night(long from, long to) { return interpolate(from, to, true); }
        private Double interpolate(long from, long to, boolean allowWholeNight) {
            double energy = 0;
            long covered = 0;
            for (int index = 1; index < size; index++) {
                long start = times[index - 1], end = times[index];
                if (end <= from || start >= to) continue;
                Double a = values[index - 1], b = values[index];
                if (a == null || b == null || b < a
                        || end - start > 6 * HOUR && !(allowWholeNight && start == from && end == to)) return null;
                long duration = Math.min(end, to) - Math.max(start, from);
                energy += (b - a) * duration / (end - start);
                covered += duration;
            }
            return covered == to - from ? energy : null;
        }
    }

    static List<Window> buckets(long from, long to, String grain) {
        List<Window> windows = new ArrayList<>();
        for (long cursor = from; cursor < to && windows.size() < 400;) {
            var local = Instant.ofEpochMilli(cursor).atOffset(SHANGHAI);
            long next = switch (grain) {
                case "month" -> local.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay().toInstant(SHANGHAI).toEpochMilli();
                case "week" -> cursor + 7 * DAY;
                case "hour" -> (Math.floorDiv(cursor, HOUR) + 1) * HOUR;
                case "day" -> local.toLocalDate().plusDays(1).atStartOfDay().toInstant(SHANGHAI).toEpochMilli();
                default -> throw new IllegalArgumentException("Unknown meter report grain");
            };
            long end = Math.min(next, to);
            boolean full = "month".equals(grain)
                    ? local.getDayOfMonth() == 1 && local.getHour() == 0 && local.getMinute() == 0 && end == next
                    : end - cursor == ("week".equals(grain) ? 7 * DAY : "hour".equals(grain) ? HOUR : DAY);
            windows.add(new Window(cursor, end, full));
            cursor = next;
        }
        if ("hour".equals(grain) && windows.size() == 400 && windows.get(windows.size() - 1).to() < to) return List.of();
        return windows;
    }
}
