package ltd.qianc.iotagent.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportFactProducerContractTest {
    private static final Instant CHECKPOINT = Instant.parse("2026-10-12T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T01:00:01Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completedCheckpointPersistsSummaryAndEnergyAttachmentWithAuthorizationFacts() throws Exception {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = ReportFactProducerFactory.local(store, mapper);
        String payload = """
                {"report":{"title":"昨日用电简报","summary":"统计事实已取得，同比基期缺数","status":"partial",
                "meterIds":["meter-1"],"blocks":[
                  {"type":"meter_report","report":{"params":{"relation":"statistics"},"total":{"value":0,"unit":"度"}}},
                  {"type":"meter_report","report":{"params":{"relation":"previousPeriod"},"diff":{"value":-3.5,"unit":"度"}}}]}}
                """;

        Optional<ReportFact> result = producer.produce(checkpoint(payload, "COMPLETED"));

        assertThat(result).isPresent();
        JsonNode report = mapper.readTree(result.orElseThrow().payloadJson());
        assertThat(report.path("status").asText()).isEqualTo("partial");
        assertThat(report.path("tenantCode").asText()).isEqualTo("XSJ");
        assertThat(report.path("ownerKey").asText()).isEqualTo("owner-1");
        assertThat(report.path("source").asText()).isEqualTo("company");
        assertThat(report.path("origin").path("kind").asText()).isEqualTo("checkpoint");
        assertThat(report.path("blocks").size()).isEqualTo(2);
        assertThat(report.path("blocks").get(0).path("report").path("total").path("value").asDouble()).isZero();
    }

    @Test
    void nestedEnergyBlocksPersistTheirFixedMeterMembership() throws Exception {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = ReportFactProducerFactory.local(store, mapper);
        Optional<ReportFact> result = producer.produce(checkpoint(
                "{\"report\":{\"title\":\"嵌套电表报告\",\"blocks\":[{\"type\":\"meter_report\",\"report\":{\"authorizationMeterIds\":[\"meter-1\"],\"meters\":[{\"id\":\"meter-1\"}]}}]}}",
                "COMPLETED"));

        assertThat(result).isPresent();
        JsonNode meterIds = mapper.readTree(result.orElseThrow().payloadJson()).path("meterIds");
        assertThat(meterIds.size()).isEqualTo(1);
        assertThat(meterIds.get(0).asText()).isEqualTo("meter-1");

    }
    @Test
    void sameCheckpointIdNeverReplacesAnEarlierArtifact() throws Exception {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = ReportFactProducerFactory.local(store, mapper);
        ReportFactProducer.ReportCheckpoint first = checkpoint(
                "{\"report\":{\"id\":\"saved-1\",\"title\":\"原始报告\",\"blocks\":[]}}", "COMPLETED");
        ReportFactProducer.ReportCheckpoint changed = checkpoint(
                "{\"report\":{\"id\":\"saved-1\",\"title\":\"后续覆盖\",\"blocks\":[]}}", "COMPLETED");

        producer.produce(first);
        Optional<ReportFact> repeated = producer.produce(changed);

        assertThat(repeated).isPresent();
        assertThat(mapper.readTree(repeated.orElseThrow().payloadJson()).path("title").asText()).isEqualTo("原始报告");
        assertThat(store.values).hasSize(1);
    }

    @Test
    void nonReportAndNonCompanyTerminalFactsDoNotCreateArtifacts() {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = ReportFactProducerFactory.local(store, mapper);

        assertThat(producer.produce(checkpoint("{\"outcome\":\"COMPLETED\"}", "COMPLETED"))).isEmpty();
        assertThat(producer.produce(new ReportFactProducer.ReportCheckpoint(
                "task-1", "phase-1", "office", "XSJ", "owner-1", "objective", "COMPLETED",
                CHECKPOINT, CLOCK.instant(), "{\"report\":{\"title\":\"x\",\"blocks\":[]}}"))).isEmpty();
        assertThat(store.values).isEmpty();
    }

    private ReportFactProducer.ReportCheckpoint checkpoint(String payload, String outcome) {
        return new ReportFactProducer.ReportCheckpoint(
                "task-1", "phase-1", "company", "XSJ", "owner-1",
                "昨日用电统计并保存报告", outcome, CHECKPOINT, CLOCK.instant(), payload);
    }

    private static final class MemoryStore implements ReportFactStore {
        private final Map<String, ReportFact> values = new LinkedHashMap<>();

        @Override
        public ReportFact save(ReportFact report) {
            values.putIfAbsent(report.reportId(), report);
            return values.get(report.reportId());
        }

        @Override
        public Optional<ReportFact> find(String reportId) {
            return Optional.ofNullable(values.get(reportId));
        }
    }
}
