package com.ehe.iotagent.iot.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.EnvironmentHistory.Sample;

/** Display aggregation of already-authorized local samples; never changes raw history or its metrics. */
final class EnvironmentTrendProjection {
    private static final ZoneOffset SHANGHAI = ZoneOffset.ofHours(8);
    private static final List<String> KEYS = List.of("temp", "humidity", "pm25", "pm10", "pm1");
    private static final List<Function<Sample, BigDecimal>> VALUES = List.of(
            Sample::temp, Sample::humidity, Sample::pm25, Sample::pm10, Sample::pm1);

    private EnvironmentTrendProjection() { }

    static long days(Instant from, Instant to) {
        return ChronoUnit.DAYS.between(from.atOffset(SHANGHAI).toLocalDate(), to.atOffset(SHANGHAI).toLocalDate()) + 1;
    }

    static void validate(Instant from, Instant to) {
        if (from.isAfter(to) || days(from, to) > 180)
            throw new BusinessException("INVALID_HISTORY_WINDOW", "环境趋势单次最多选择180天（含起止日期）", 400);
    }

    static Map<String, Object> payload(List<Sample> samples, Instant from, Instant to) {
        long days = days(from, to);
        String resolution = days <= 2 ? "raw" : days <= 14 ? "3h" : "day";
        Map<String, Object> result = EnvironmentSamples.payload(samples, from.toString(), to.toString(), true, null, null, days <= 2);
        result.put("resolution", resolution);
        Map<String, Object> latest = new LinkedHashMap<>();
        for (int index = samples.size() - 1; index >= 0 && latest.size() < KEYS.size(); index--) {
            Sample sample = samples.get(index);
            if (!sample.metricEligible()) continue;
            String at = null;
            for (int i = 0; i < KEYS.size(); i++) {
                if (latest.containsKey(KEYS.get(i))) continue;
                BigDecimal value = VALUES.get(i).apply(sample);
                if (value == null) continue;
                if (at == null) at = sample.recordedAt().toString();
                latest.put(KEYS.get(i), Map.of("value", value, "at", at));
            }
        }
        result.put("latest", latest);
        if (days <= 2) return result;
        long step = "3h".equals(resolution) ? 10800 : 86400;
        Instant first = from.atOffset(SHANGHAI).toLocalDate().atStartOfDay().toInstant(SHANGHAI);
        if (step < 86400) first = first.plusSeconds(((from.getEpochSecond() - first.getEpochSecond()) / step) * step);
        int size = (int) ((to.getEpochSecond() - first.getEpochSecond()) / step + 1);
        Accumulator[][] values = new Accumulator[size][KEYS.size()];
        for (Sample sample : samples) {
            if (!sample.metricEligible()) continue;
            int bucket = (int) ((sample.recordedAt().getEpochSecond() - first.getEpochSecond()) / step);
            if (bucket < 0 || bucket >= size) continue;
            for (int i = 0; i < KEYS.size(); i++) {
                BigDecimal value = VALUES.get(i).apply(sample);
                if (value == null) continue;
                if (values[bucket][i] == null) values[bucket][i] = new Accumulator();
                values[bucket][i].add(value);
            }
        }
        List<Map<String, Object>> buckets = new ArrayList<>(size);
        Instant requestedEnd = to.plusMillis(1);
        for (int index = 0; index < size; index++) {
            Instant start = first.plusSeconds(index * step);
            Instant end = start.plusSeconds(step);
            Map<String, Object> metrics = new LinkedHashMap<>();
            Map<String, Object> means = new LinkedHashMap<>();
            for (int i = 0; i < KEYS.size(); i++) {
                Accumulator accumulator = values[index][i];
                Map<String, Object> metric = accumulator == null ? empty() : accumulator.result();
                metrics.put(KEYS.get(i), metric); means.put(KEYS.get(i), metric.get("mean"));
            }
            Instant bucketFrom = start.isBefore(from) ? from : start;
            Instant bucketTo = end.isAfter(requestedEnd) ? requestedEnd : end;
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("from", bucketFrom.atOffset(SHANGHAI).toString());
            bucket.put("to", bucketTo.atOffset(SHANGHAI).toString());
            bucket.put("at", bucketFrom.plusNanos(java.time.Duration.between(bucketFrom, bucketTo).toNanos() / 2).toString());
            bucket.put("values", means); bucket.put("metrics", metrics);
            buckets.add(bucket);
        }
        result.put("buckets", buckets);
        return result;
    }

    private static Map<String, Object> empty() {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("mean", null); metric.put("min", null); metric.put("max", null); metric.put("count", 0);
        return metric;
    }

    private static final class Accumulator {
        private BigDecimal sum = BigDecimal.ZERO, min, max;
        private int count;
        void add(BigDecimal value) {
            sum = sum.add(value); count++;
            if (min == null || value.compareTo(min) < 0) min = value;
            if (max == null || value.compareTo(max) > 0) max = value;
        }
        Map<String, Object> result() {
            return Map.of("mean", sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP),
                    "min", min, "max", max, "count", count);
        }
    }
}
