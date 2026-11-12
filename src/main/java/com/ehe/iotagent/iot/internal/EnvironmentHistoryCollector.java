package com.ehe.iotagent.iot.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.ehe.iotagent.iot.EnvironmentHistory.Sample;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.iot.ObservationOperationEnum;
import com.ehe.iotagent.iot.ObservationRequest;
import com.ehe.iotagent.iot.ObservationResponse;
import com.ehe.iotagent.iot.ObservationStatusEnum;
import com.ehe.iotagent.iot.RequestIdentity;

/** Unfiltered 100-row source stream; a page is never an ordinary readable publication. */
final class EnvironmentHistoryCollector {
    private static final int PAGE_SIZE=100;
    private static final Set<String> ALLOWED_FAILURES=Set.of("HISTORY_PARTIAL","SAMPLE_TIME_UNKNOWN","SAMPLE_VALUE_INVALID",
            "SAMPLE_VALUE_MISSING","SAMPLE_CONFLICT","SAMPLE_QUALITY_UNVERIFIED");
    private final ObservationAdapter source;
    private final JdbcEnvironmentHistory store;

    EnvironmentHistoryCollector(ObservationAdapter source,JdbcEnvironmentHistory store) { this.source=source; this.store=store; }

    void collect(RequestIdentity identity,String run,String key,Instant currentCutoff,boolean full) {
        Set<String> priorPages=new HashSet<>();
        Long expectedTotal=null;
        long seen=0;
        Instant previousOldest=null;
        for(int page=0; ;page=Math.incrementExact(page)) {
            if(Thread.currentThread().isInterrupted()) throw invalid("Environment collection interrupted");
            ObservationResponse response=source.read(new ObservationRequest(identity,ObservationOperationEnum.ENVIRONMENT_HISTORY,
                    Map.of("page",Integer.toString(page)))).block();
            if(response==null || response.status()==ObservationStatusEnum.UNAVAILABLE
                    || response.failures().stream().anyMatch(failure -> !ALLOWED_FAILURES.contains(failure.code()))) throw invalid("Environment source incomplete");
            Map<String,Object> payload=response.payload();
            if(!(payload.get("samples") instanceof java.util.List<?> rows) || !(payload.get("sourcePage") instanceof Map<?,?> metadata))
                throw invalid("Environment source page metadata missing");
            if(!(metadata.get("number") instanceof Number number) || number.intValue()!=page) throw invalid("Environment page changed");
            if(Boolean.FALSE.equals(metadata.get("ordered"))) throw invalid("Environment source order invalid");
            if(!(metadata.get("returned") instanceof Number returned) || returned.intValue()!=rows.size() || returned.intValue()>PAGE_SIZE)
                throw invalid("Environment page count invalid");
            Long total=payload.get("totalElements") instanceof Number value?value.longValue():null;
            if(total!=null) {
                if(total<0 || expectedTotal!=null && !expectedTotal.equals(total)) throw invalid("Environment total changed");
                expectedTotal=total;
            }
            boolean last=payload.get("nextPage")==null;
            if(!(metadata.get("last") instanceof Boolean declared) || declared!=last) throw invalid("Environment last flag changed");
            if(total!=null && last!=((page+1L)*PAGE_SIZE>=total)) throw invalid("Environment last flag conflicts with total");
            if(metadata.get("totalPages") instanceof Number pages && total!=null && pages.longValue()!=(total+PAGE_SIZE-1)/PAGE_SIZE)
                throw invalid("Environment total pages conflicts with total");
            Map<String,Sample> selected=new LinkedHashMap<>();
            Instant oldest=null,newest=null;
            for(Object item:rows) {
                if(!(item instanceof Map<?,?> row)) throw invalid("Environment sample invalid");
                Sample sample=sample(row);
                String sampleKey=EnvironmentSamples.identity(sample);
                if(priorPages.contains(sampleKey)) throw invalid("Environment record moved between pages");
                Sample previous=selected.putIfAbsent(sampleKey,sample);
                if(previous!=null && !sameValues(previous,sample)) selected.put(sampleKey,EnvironmentSamples.conflicted(previous));
                if(sample.recordedAt()!=null) {
                    if(oldest==null || sample.recordedAt().isBefore(oldest)) oldest=sample.recordedAt();
                    if(newest==null || sample.recordedAt().isAfter(newest)) newest=sample.recordedAt();
                }
            }
            if(previousOldest!=null && newest!=null && previousOldest.isBefore(newest)) throw invalid("Environment pages overlap or changed");
            priorPages.addAll(selected.keySet());
            store.stage(run,key,selected);
            seen=Math.addExact(seen,returned.longValue());
            if(expectedTotal!=null && seen>expectedTotal) throw invalid("Environment exceeded advertised total");
            if(last) {
                if(expectedTotal!=null && seen!=expectedTotal) throw invalid("Environment ended before advertised total");
                return;
            }
            if(returned.intValue()==0) throw invalid("Environment non-final empty page");
            if(!full && (currentCutoff==null || oldest!=null && oldest.isBefore(currentCutoff))) return;
            if(oldest!=null) previousOldest=oldest;
        }
    }

    private static Sample sample(Map<?,?> row) {
        String at=text(row.get("recordedAt"));
        boolean missing=Boolean.TRUE.equals(row.get("sourceIdMissing"));
        Map<?,?> values=row.get("values") instanceof Map<?,?> map?map:Map.of();
        Map<?,?> quality=row.get("quality") instanceof Map<?,?> map?map:Map.of();
        String sourceId=missing?null:text(row.get("id"));
        return new Sample(sourceId,missing || sourceId==null,text(row.get("deviceCode")),text(row.get("originalRecordedAt")),
                at==null?null:Instant.parse(at),decimal(values.get("temp")),decimal(values.get("humidity")),decimal(values.get("pm25")),
                decimal(values.get("pm10")),decimal(values.get("pm1")),Boolean.TRUE.equals(quality.get("invalidValues")),quality.get("source"),
                Boolean.TRUE.equals(quality.get("conflicted")));
    }

    private static boolean sameValues(Sample left,Sample right) {
        return equal(left.temp(),right.temp()) && equal(left.humidity(),right.humidity()) && equal(left.pm25(),right.pm25())
                && equal(left.pm10(),right.pm10()) && equal(left.pm1(),right.pm1()) && left.invalidValues()==right.invalidValues()
                && java.util.Objects.equals(left.sourceQuality(),right.sourceQuality());
    }
    private static boolean equal(BigDecimal left,BigDecimal right) { return left==null?right==null:right!=null && left.compareTo(right)==0; }
    private static BigDecimal decimal(Object value) { return value==null?null:value instanceof BigDecimal number?number:new BigDecimal(value.toString()); }
    private static String text(Object value) { return value instanceof String text && !text.isBlank()?text:null; }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
