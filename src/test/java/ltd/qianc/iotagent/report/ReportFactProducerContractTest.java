package ltd.qianc.iotagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ReportFactProducerContractTest {
    private static final Instant CHECKPOINT = Instant.parse("2026-10-12T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T01:00:01Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestIdentity identity = new RequestIdentity("Bearer test", "XSJ", IotSource.COMPANY, "owner-1");

    @Test
    void reportRequestIsRecomputedFromServerEvidenceAndCallerBlocksAreIgnored() throws Exception {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = producer(store);
        String payload = """
                {"reportRequest":{"kind":"energy","title":"昨日用电简报",
                  "period":{"kind":"absolute","from":"2026-09-01T08:00:00+08:00","to":"2026-10-01T08:00:00+08:00"},
                  "scope":{"level":"selection","meterIds":["meter-1"]},"comparisons":["previousPeriod"]},
                 "blocks":[{"type":"text","text":"model-authored 999"}]}
                """;

        Optional<ReportFact> result = producer.produce(checkpoint(payload, "COMPLETED"), identity);

        assertThat(result).isPresent();
        JsonNode report = mapper.readTree(result.orElseThrow().payloadJson());
        assertThat(report.path("status").asText()).isEqualTo("completed");
        assertThat(report.path("tenantCode").asText()).isEqualTo("XSJ");
        assertThat(report.path("ownerKey").asText()).isEqualTo("owner-1");
        assertThat(report.path("origin").path("kind").asText()).isEqualTo("checkpoint");
        assertThat(report.path("blocks").size()).isEqualTo(2);
        assertThat(report.path("blocks").get(0).path("report").path("total").path("value").asDouble()).isEqualTo(10.0d);
        assertThat(report.toString()).doesNotContain("model-authored 999");
    }

    @Test
    void checkpointWithoutReportRequestIsNotAnArtifact() {
        MemoryStore store = new MemoryStore();
        assertThat(producer(store).produce(checkpoint("{\"outcome\":\"COMPLETED\"}", "COMPLETED"), identity)).isEmpty();
        assertThat(store.values).isEmpty();
    }

    @Test
    void nonCompanyTerminalFactsDoNotCreateArtifacts() {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = producer(store);
        assertThat(producer.produce(new ReportFactProducer.ReportCheckpoint(
                "task-1", "phase-1", "office", "XSJ", "owner-1", "objective", "COMPLETED",
                CHECKPOINT, CLOCK.instant(), "{\"reportRequest\":{\"kind\":\"energy\"}}"), identity)).isEmpty();
        assertThat(store.values).isEmpty();
    }

    @Test
    void presentReportRequestWithoutVerifiedIdentityFailsLoudly() {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = producer(store);
        String payload = "{\"reportRequest\":{\"kind\":\"energy\",\"period\":{\"kind\":\"previous\",\"unit\":\"day\"}}}";

        assertThatThrownBy(() -> producer.produce(checkpoint(payload, "COMPLETED")))
                .isInstanceOf(ReportUnavailableException.class)
                .extracting(error -> ((ReportUnavailableException) error).code())
                .isEqualTo("REPORT_EVIDENCE_IDENTITY_MISSING");
    }

    @Test
    void mismatchedTaskIdentityIsRejected() {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = producer(store);
        String payload = "{\"reportRequest\":{\"kind\":\"energy\",\"period\":{\"kind\":\"previous\",\"unit\":\"day\"}}}";

        assertThatThrownBy(() -> producer.produce(checkpoint(payload, "COMPLETED"),
                new RequestIdentity("Bearer other", "OTHER", IotSource.COMPANY, "owner-2")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("REPORT_SCOPE_CHANGED");
    }

    @Test
    void sameCheckpointIdNeverReplacesAnEarlierArtifact() throws Exception {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = producer(store);
        String first = "{\"reportRequest\":{\"kind\":\"energy\",\"period\":{\"kind\":\"absolute\","
                + "\"from\":\"2026-09-01T08:00:00+08:00\",\"to\":\"2026-10-01T08:00:00+08:00\"},"
                + "\"scope\":{\"level\":\"selection\",\"meterIds\":[\"meter-1\"]}}}";
        String changed = "{\"reportRequest\":{\"kind\":\"safety\",\"period\":{\"kind\":\"absolute\","
                + "\"from\":\"2026-10-01T08:00:00+08:00\",\"to\":\"2026-10-02T08:00:00+08:00\"}}}";

        ReportFact original = producer.produce(checkpoint(first, "COMPLETED"), identity).orElseThrow();
        ReportFact repeated = producer.produce(checkpoint(changed, "COMPLETED"), identity).orElseThrow();

        assertThat(repeated.reportId()).isEqualTo(original.reportId());
        assertThat(repeated.payloadJson()).isEqualTo(original.payloadJson());
        assertThat(store.values).hasSize(1);
    }

    private ReportFactProducer producer(MemoryStore store) {
        return ReportFactProducerFactory.local(store, mapper, identity -> Mono.just(scope(identity)),
                ReportEvidenceFixture.evidence(), CLOCK);
    }

    private static AuthorizedDeviceScope scope(RequestIdentity request) {
        return new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return request; }
            @Override public IotSource source() { return IotSource.COMPANY; }
            @Override public java.util.List<AuthorizedDevice> devices() {
                return java.util.List.of(new AuthorizedDevice("meter-1", "energy", "Meter 1", "Building", false));
            }
        };
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
