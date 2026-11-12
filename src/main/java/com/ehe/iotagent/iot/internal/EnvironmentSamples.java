package com.ehe.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.ehe.iotagent.iot.EnvironmentHistory.Sample;
import com.ehe.iotagent.iot.ObservationFailure;
import com.ehe.iotagent.iot.ObservationResponse;

/** Shared interpretation of raw environment facts; never infers a device/floor association. */
final class EnvironmentSamples {
    static final Comparator<Sample> ORDER = Comparator.comparing(Sample::recordedAt,
            Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(sample -> sample.sourceIdMissing() ? 1 : 0)
            .thenComparing(Sample::sourceId,Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Sample::deviceCode,Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Sample::originalRecordedAt,Comparator.nullsFirst(Comparator.naturalOrder()));

    private EnvironmentSamples() { }

    static Sample fromSource(JsonNode row) {
        String sourceId = text(row.get("id"));
        String rawTime = text(row.get("createdAt"));
        BigDecimal temp = number(row.get("tem")), humidity = number(row.get("hum"));
        BigDecimal pm25 = number(row.get("pm25")), pm10 = number(row.get("pm101")), pm1 = number(row.get("pm10"));
        boolean invalid = invalid(row.get("tem"),temp) || invalid(row.get("hum"),humidity)
                || invalid(row.get("pm25"),pm25) || invalid(row.get("pm101"),pm10) || invalid(row.get("pm10"),pm1);
        Object quality = row.hasNonNull("quality") ? GatewayJson.value(row.get("quality")) : null;
        return new Sample(sourceId,sourceId == null,text(row.get("deviceCode")),rawTime,time(rawTime),
                temp,humidity,pm25,pm10,pm1,invalid,quality,false);
    }

    static String identity(Sample sample) {
        String point = sample.deviceCode() == null ? "site:null" : "code:" + sample.deviceCode();
        String time = sample.recordedAt() == null ? "unknown:" + sample.originalRecordedAt() : "time:" + sample.recordedAt();
        String record = sample.sourceIdMissing() ? "fallback" : "id:" + sample.sourceId();
        return LocalDataKeys.hash(record + "\u0000" + time + "\u0000" + point);
    }

    static Sample conflicted(Sample sample) {
        return new Sample(sample.sourceId(),sample.sourceIdMissing(),sample.deviceCode(),sample.originalRecordedAt(),
                sample.recordedAt(),sample.temp(),sample.humidity(),sample.pm25(),sample.pm10(),sample.pm1(),
                sample.invalidValues(),sample.sourceQuality(),true);
    }

    static Map<String,Object> publicSample(Sample sample) {
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("temp",sample.temp()); values.put("humidity",sample.humidity()); values.put("pm25",sample.pm25());
        values.put("pm10",sample.pm10()); values.put("pm1",sample.pm1());
        Map<String,Object> quality = new LinkedHashMap<>();
        quality.put("timeUnknown",sample.recordedAt() == null); quality.put("invalidValues",sample.invalidValues());
        quality.put("missingValues",sample.missingValues() && !sample.invalidValues());
        quality.put("conflicted",sample.conflicted()); quality.put("source",sample.sourceQuality());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id",sample.sourceIdMissing() ? (sample.recordedAt() == null ? "unknown" : sample.recordedAt())
                + ":" + (sample.deviceCode() == null ? "site" : sample.deviceCode()) : sample.sourceId());
        result.put("sourceIdMissing",sample.sourceIdMissing()); result.put("recordedAt",sample.recordedAt() == null ? null : sample.recordedAt().toString());
        result.put("originalRecordedAt",sample.originalRecordedAt()); result.put("deviceCode",sample.deviceCode());
        result.put("values",values); result.put("quality",quality);
        return result;
    }

    static Map<String,Object> payload(List<Sample> samples, String from, String to, boolean scannedComplete,
                                     Integer nextPage, Map<String,Object> cache) {
        return payload(samples, from, to, scannedComplete, nextPage, cache, true);
    }

    static Map<String,Object> payload(List<Sample> samples, String from, String to, boolean scannedComplete,
                                     Integer nextPage, Map<String,Object> cache, boolean includeSamples) {
        int unknown = 0, invalid = 0, missing = 0, conflicts = 0, unverified = 0;
        Instant first = null, last = null;
        List<Map<String,Object>> rows = new ArrayList<>(includeSamples ? samples.size() : 0);
        for (Sample sample : samples) {
            if (includeSamples) rows.add(publicSample(sample));
            if (sample.recordedAt() == null) unknown++;
            else {
                if (first == null || sample.recordedAt().isBefore(first)) first = sample.recordedAt();
                if (last == null || sample.recordedAt().isAfter(last)) last = sample.recordedAt();
            }
            if (sample.invalidValues()) invalid++;
            if (sample.missingValues() && !sample.invalidValues()) missing++;
            if (sample.conflicted()) conflicts++;
            if (sample.sourceQuality() != null) unverified++;
        }
        boolean complete = scannedComplete && nextPage == null && unknown + invalid + missing + conflicts + unverified == 0;
        Map<String,Object> coverage = new LinkedHashMap<>();
        coverage.put("complete",complete); coverage.put("readSamples",samples.size());
        coverage.put("from",first == null ? null : first.toString()); coverage.put("to",last == null ? null : last.toString());
        coverage.put("truncated",nextPage != null); coverage.put("unknownTimeSamples",unknown);
        coverage.put("invalidValueSamples",invalid); coverage.put("conflictedSamples",conflicts);
        coverage.put("missingValueSamples",missing);
        coverage.put("unverifiedQualitySamples",unverified);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("source","company"); result.put("timezone","Asia/Shanghai"); result.put("attribution","site");
        result.put("observedAt",unknown == 0 && last != null ? last.toString() : null);
        result.put("window",Map.of("from",from,"to",to)); result.put("samples",rows);
        result.put("metrics",metrics(samples)); result.put("coverage",coverage); result.put("nextPage",nextPage);
        if (cache != null) result.put("cache",cache);
        return result;
    }

    static ObservationResponse response(Map<String,Object> payload) {
        Map<?,?> coverage = (Map<?,?>)payload.get("coverage");
        if (Boolean.TRUE.equals(coverage.get("complete"))) {
            return ((Number)coverage.get("readSamples")).intValue() == 0 ? ObservationResponse.empty(payload) : ObservationResponse.available(payload);
        }
        List<ObservationFailure> failures = new ArrayList<>();
        if (payload.get("nextPage") != null) failures.add(new ObservationFailure("environment","HISTORY_PARTIAL","环境历史仅读取到当前页"));
        if (((Number)coverage.get("unknownTimeSamples")).intValue() > 0) failures.add(new ObservationFailure(
                "environment","SAMPLE_TIME_UNKNOWN","存在无法归入请求窗口的未知采样时刻记录；保留原始记录，不参与区间指标"));
        if (((Number)coverage.get("invalidValueSamples")).intValue() > 0) failures.add(new ObservationFailure(
                "environment","SAMPLE_VALUE_INVALID","部分来源指标不是有效数值，缺值不作为零"));
        if (((Number)coverage.get("missingValueSamples")).intValue() > 0) failures.add(new ObservationFailure(
                "environment","SAMPLE_VALUE_MISSING","部分来源指标缺测，缺值不作为零"));
        if (((Number)coverage.get("conflictedSamples")).intValue() > 0) failures.add(new ObservationFailure(
                "environment","SAMPLE_CONFLICT","来源记录身份相同但内容冲突，不作为可计算采样"));
        if (((Number)coverage.get("unverifiedQualitySamples")).intValue() > 0) failures.add(new ObservationFailure(
                "environment","SAMPLE_QUALITY_UNVERIFIED","来源质量标记尚未核实，原样保留且不参与指标"));
        return ObservationResponse.partial(payload,failures,false);
    }

    private static List<Map<String,Object>> metrics(List<Sample> samples) {
        List<Map<String,Object>> result = new ArrayList<>(5);
        metric(result,samples,"temp","温度","°C",Sample::temp);
        metric(result,samples,"humidity","湿度","%",Sample::humidity);
        metric(result,samples,"pm25","PM2.5",null,Sample::pm25);
        metric(result,samples,"pm10","PM10",null,Sample::pm10);
        metric(result,samples,"pm1","PM1.0",null,Sample::pm1);
        return result;
    }

    private static void metric(List<Map<String,Object>> out,List<Sample> samples,String key,String label,String unit,
                               Function<Sample,BigDecimal> valueOf) {
        int count = 0;
        BigDecimal min = null, peak = null;
        Instant peakAt = null;
        for (Sample sample : samples) {
            if (!sample.metricEligible()) continue;
            BigDecimal value = valueOf.apply(sample);
            if (value == null) continue;
            count++;
            if (min == null || value.compareTo(min) < 0) min = value;
            if (peak == null || value.compareTo(peak) > 0) { peak = value; peakAt = sample.recordedAt(); }
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("key",key); result.put("label",label); result.put("unit",unit); result.put("count",count);
        result.put("min",min); result.put("peak",peak == null ? null : Map.of("value",peak,"at",peakAt.toString()));
        out.add(result);
    }

    private static boolean invalid(JsonNode raw,BigDecimal value) { return raw != null && !raw.isNull() && value == null; }
    private static String text(JsonNode value) {
        if (value == null || !value.isValueNode() || value.isNull() || value.asText().isBlank()) return null;
        return value.asText();
    }
    private static BigDecimal number(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try { return value.isNumber() ? value.decimalValue() : new BigDecimal(value.asText().trim()); }
        catch (NumberFormatException invalid) { return null; }
    }
    private static Instant time(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException invalidOffset) {
            try { return LocalDateTime.parse(value.replace(' ','T')).toInstant(ZoneOffset.ofHours(8)); }
            catch (RuntimeException invalidLocal) { return null; }
        }
    }
}
