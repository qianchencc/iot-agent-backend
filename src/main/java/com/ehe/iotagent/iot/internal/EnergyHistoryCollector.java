package com.ehe.iotagent.iot.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ehe.iotagent.iot.EnergyHistory.Sample;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.iot.ObservationOperationEnum;
import com.ehe.iotagent.iot.ObservationRequest;
import com.ehe.iotagent.iot.ObservationResponse;
import com.ehe.iotagent.iot.ObservationStatusEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Flux;

/** Reads from page zero to the genuine stream end. Current passes stop only after a prior complete horizon. */
final class EnergyHistoryCollector {
    private static final int PAGE_SIZE = 500;
    private static final int CONCURRENCY = 6;
    private final ObservationAdapter source;
    private final JdbcEnergyHistory store;

    EnergyHistoryCollector(ObservationAdapter source, JdbcEnergyHistory store) {
        this.source = source;
        this.store = store;
    }

    void collect(RequestIdentity identity, String run, String key, Set<String> allowed, Instant currentCutoff, boolean full) {
        Set<String> signatures = new HashSet<>();
        Set<String> priorPageRecords = new HashSet<>();
        long seen = 0;
        Long expectedTotal = null;
        Instant previousOldest = null;
        int next = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw invalid("Collection interrupted");
            int first = next;
            int count = expectedTotal == null ? 1 : (int) Math.min(CONCURRENCY, Math.max(1, (expectedTotal + PAGE_SIZE - 1) / PAGE_SIZE - first));
            List<Page> pages = Flux.range(first,count).flatMapSequential(page -> source.read(new ObservationRequest(identity,
                            ObservationOperationEnum.ENERGY_HISTORY,Map.of("page",Integer.toString(page),"size",Integer.toString(PAGE_SIZE))))
                    .map(response -> page(response,page)),CONCURRENCY).collectList().block();
            if (pages == null || pages.isEmpty()) throw invalid("No history page");
            for (Page page : pages) {
                if (page.number() != next++) throw invalid("History page sequence changed");
                if (page.total() != null) {
                    if (expectedTotal != null && !expectedTotal.equals(page.total())) throw invalid("History total changed during collection");
                    expectedTotal = page.total();
                }
                if (previousOldest != null && page.newest() != null && previousOldest.isBefore(page.newest()))
                    throw invalid("History order overlaps or changed");
                Set<String> thisPage = new HashSet<>();
                List<Sample> selected = new ArrayList<>();
                StringBuilder pageIdentity = new StringBuilder(page.samples().size() * 64);
                for (Sample sample : page.samples()) {
                    String sampleKey = JdbcEnergyHistory.sampleKey(sample);
                    pageIdentity.append(sampleKey);
                    if (priorPageRecords.contains(sampleKey)) throw invalid("History record moved between pages");
                    thisPage.add(sampleKey);
                    if (allowed.contains(sample.meterId())) selected.add(sample);
                }
                priorPageRecords.addAll(thisPage);
                store.stage(run,key,selected);
                seen = Math.addExact(seen,page.returned());
                if (expectedTotal != null && seen > expectedTotal) throw invalid("History returned more records than its total");
                if (!page.samples().isEmpty() && !signatures.add(LocalDataKeys.hash(pageIdentity.toString())))
                    throw invalid("History page repeated");
                if (page.oldest() != null) previousOldest = page.oldest();
                if (page.last()) {
                    if (expectedTotal != null && seen != expectedTotal) throw invalid("History ended before its advertised total");
                    return;
                }
                if (page.returned() == 0) throw invalid("Non-final empty history page");
                if (!full && currentCutoff == null) return;
                if (currentCutoff != null && page.oldest() != null && page.oldest().isBefore(currentCutoff)) return;
            }
        }
    }

    private static Page page(ObservationResponse response,int requestedPage) {
        if (response == null || response.status() == ObservationStatusEnum.UNAVAILABLE
                || response.status() == ObservationStatusEnum.PARTIAL && response.failures().stream().anyMatch(failure -> !"HISTORY_PARTIAL".equals(failure.code())))
            throw invalid("History source returned incomplete data");
        Map<String,Object> payload = response.payload();
        if (!(payload.get("samples") instanceof List<?> rows)) throw invalid("History samples missing");
        Map<?,?> metadata = payload.get("sourcePage") instanceof Map<?,?> values ? values : Map.of();
        int number = metadata.get("number") instanceof Number value ? value.intValue() : requestedPage;
        if (number != requestedPage) throw invalid("History page number changed");
        if (Boolean.FALSE.equals(metadata.get("ordered"))) throw invalid("History page is not source-time ordered");
        int returned = metadata.get("returned") instanceof Number value ? value.intValue() : rows.size();
        if (returned != rows.size() || returned > PAGE_SIZE) throw invalid("History page count changed");
        Long total = payload.get("totalElements") instanceof Number value ? value.longValue() : null;
        if (total != null && total < 0) throw invalid("History total invalid");
        boolean last = payload.get("nextPage") == null;
        if (metadata.get("last") instanceof Boolean declared && declared != last) throw invalid("Conflicting history last flags");
        if (total != null && last != ((long) (requestedPage+1)*PAGE_SIZE>=total)) throw invalid("History last flag conflicts with total");
        if (metadata.get("totalPages") instanceof Number pages && total != null
                && pages.longValue() != (total+PAGE_SIZE-1)/PAGE_SIZE) throw invalid("History total pages conflicts with total");
        List<Sample> samples = new ArrayList<>(rows.size());
        Instant oldest = null,newest = null;
        for (Object item : rows) {
            if (!(item instanceof Map<?,?> row)) throw invalid("Invalid history row");
            String meter = text(row.get("equipmentId"));
            String at = text(row.get("readTime"));
            if (meter == null || at == null) throw invalid("History source identity missing");
            Instant instant = Instant.parse(at);
            if (!instant.equals(instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
                throw invalid("History sample time exceeds database precision");
            String sourceId = text(row.get("id"));
            boolean missingId = Boolean.TRUE.equals(row.get("sourceIdMissing")) || sourceId == null;
            Map<?,?> values = row.get("values") instanceof Map<?,?> map ? map : Map.of();
            samples.add(new Sample(meter,missingId?null:sourceId,missingId,instant,decimal(values.get("epf")),decimal(values.get("epz")),
                    decimal(values.get("totalElectric")),decimal(row.get("totalPowerFactor")),Boolean.TRUE.equals(row.get("conflicted"))));
            if (oldest == null || instant.isBefore(oldest)) oldest=instant;
            if (newest == null || instant.isAfter(newest)) newest=instant;
        }
        return new Page(number,returned,total,last,samples,oldest,newest);
    }
    private static BigDecimal decimal(Object value) { return JdbcEnergyHistory.decimal(value); }
    private static String text(Object value) { return value instanceof String text && !text.isBlank()?text:null; }
    private static IllegalStateException invalid(String reason) { return new IllegalStateException(reason); }
    private record Page(int number,int returned,Long total,boolean last,List<Sample> samples,Instant oldest,Instant newest) { }
}
