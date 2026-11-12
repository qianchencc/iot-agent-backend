package com.ehe.iotagent.report;

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
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ReportFactProducerContractTest {
    private static final Instant CHECKPOINT = Instant.parse("2026-10-12T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T01:00:01Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestIdentity identity = new RequestIdentity("Bearer test", "XSJ", IotSourceEnum.COMPANY, "owner-1");

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
    void acInspectionUsesPersistedServerEvidenceAndLabelsEstimateAsAssumption() throws Exception {
        MemoryStore store = new MemoryStore();
        ReportFactProducer producer = producer(store);
        String payload = """
                {"reportRequest":{"kind":"ac_inspection","title":"空调巡检报告",
                  "period":{"kind":"absolute","from":"2026-10-01T08:00:00+08:00","to":"2026-10-02T08:00:00+08:00"}},
                 "inspectionEvidence":{"taskId":"task-1","generatedAt":"2026-10-12T01:00:00Z","taskOutcome":"COMPLETED",
                  "checkpoints":[{"phaseId":"phase-1","at":"2026-10-12T01:00:00Z","status":"COMPLETED","outcome":"COMPLETED",
                    "mode":"adjust_to_26","target":26,"criteria":{"power":"on","mode":"cool"},
                    "devices":[{"deviceId":"meter-1","name":"空调","location":"26楼","power":"on","mode":"cool",
                      "setpoint":25.0,"disposition":"adjusted","outcome":"verified","afterSetpoint":26.0}],
                    "energyEstimate":{"status":"estimated","basis":"assumption","value":3.2,"unit":"度",
                      "costValue":2.1,"costUnit":"元","reason":"按调前设定温度估算，非电表实测"}}],
                  "coverage":{"checkpoints":1,"devices":["meter-1"],"adjusted":1,"unconfirmed":0}}}
                """;

        Optional<ReportFact> result = producer.produce(checkpoint(payload, "COMPLETED"), identity);

        assertThat(result).isPresent();
        JsonNode report = mapper.readTree(result.orElseThrow().payloadJson());
        assertThat(report.path("status").asText()).isEqualTo("completed");
        assertThat(report.path("taskId").asText()).isEqualTo("task-1");
        assertThat(report.path("blocks").get(0).path("type").asText()).isEqualTo("ac_inspection");
        JsonNode estimate = report.path("blocks").get(0).path("energyEstimate");
        assertThat(estimate.path("status").asText()).isEqualTo("estimated");
        assertThat(estimate.path("measured").asBoolean()).isFalse();
        assertThat(estimate.path("label").asText()).contains("非电表实测");
        assertThat(store.find(result.orElseThrow().reportId())).isPresent();
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
                new RequestIdentity("Bearer other", "OTHER", IotSourceEnum.COMPANY, "owner-2")))
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

    @Test
    void scheduledRelativePeriodsAnchorToTheirOwnCheckpointAcrossDayAndMonthRetries() throws Exception {
        MemoryStore store = new MemoryStore();
        // The retry clock has crossed both a day and a month boundary since the checkpoints.
        Clock lateClock = Clock.fixed(Instant.parse("2026-12-15T09:30:00Z"), ZoneOffset.UTC);
        ReportFactProducer producer = ReportFactProducerFactory.local(store, mapper,
                identity -> Mono.just(scope(identity)), ReportEvidenceFixture.evidence(), lateClock);
        String request = "{\"reportRequest\":{\"kind\":\"energy\",\"period\":{\"kind\":\"previous\",\"unit\":\"day\"}}}";

        ReportFact firstOccurrence = producer.produce(
                checkpoint("task-day-1", request, Instant.parse("2026-10-01T01:00:00Z")), identity).orElseThrow();
        ReportFact secondOccurrence = producer.produce(
                checkpoint("task-day-2", request, Instant.parse("2026-10-02T01:00:00Z")), identity).orElseThrow();
        // Delayed retry of the first occurrence: the scanner re-runs with a late clock,
        // but the artifact must keep the window of its own scheduled day.
        ReportFact retried = producer.produce(
                checkpoint("task-day-1", request, Instant.parse("2026-10-01T01:00:00Z")), identity).orElseThrow();

        JsonNode first = mapper.readTree(firstOccurrence.payloadJson());
        JsonNode second = mapper.readTree(secondOccurrence.payloadJson());
        assertThat(first.path("requirements").path("period").path("from").asText())
                .isEqualTo("2026-09-30T08:00:00+08:00");
        assertThat(first.path("requirements").path("period").path("to").asText())
                .isEqualTo("2026-10-01T08:00:00+08:00");
        assertThat(second.path("requirements").path("period").path("from").asText())
                .isEqualTo("2026-10-01T08:00:00+08:00");
        assertThat(retried.payloadJson()).isEqualTo(firstOccurrence.payloadJson());
        // The evidence actually captured for the first occurrence is its anchored window,
        // not the day before the late retry clock.
        assertThat(first.path("blocks").get(0).path("report").path("params").path("span")
                .path("requested").path("from").asText()).isEqualTo("2026-09-30T08:00:00+08:00");
        assertThat(first.path("blocks").get(0).path("report").path("params").path("span")
                .path("requested").path("to").asText()).isEqualTo("2026-10-01T08:00:00+08:00");
    }

    @Test
    void scheduledMonthlyPeriodStaysOnTheCheckpointMonthOnLateRetry() throws Exception {
        MemoryStore store = new MemoryStore();
        Clock lateClock = Clock.fixed(Instant.parse("2026-12-20T05:00:00Z"), ZoneOffset.UTC);
        ReportFactProducer producer = ReportFactProducerFactory.local(store, mapper,
                identity -> Mono.just(scope(identity)), ReportEvidenceFixture.evidence(), lateClock);
        String request = "{\"reportRequest\":{\"kind\":\"energy\",\"period\":{\"kind\":\"previous\",\"unit\":\"month\"}}}";

        ReportFact fact = producer.produce(checkpoint("task-month-1", request,
                Instant.parse("2026-11-01T02:00:00Z")), identity).orElseThrow();
        ReportFact retry = producer.produce(checkpoint("task-month-1", request,
                Instant.parse("2026-11-01T02:00:00Z")), identity).orElseThrow();

        JsonNode report = mapper.readTree(fact.payloadJson());
        assertThat(report.path("requirements").path("period").path("from").asText())
                .isEqualTo("2026-10-01T08:00:00+08:00");
        assertThat(report.path("requirements").path("period").path("to").asText())
                .isEqualTo("2026-11-01T08:00:00+08:00");
        assertThat(retry.payloadJson()).isEqualTo(fact.payloadJson());
    }

    private ReportFactProducer producer(MemoryStore store) {
        return ReportFactProducerFactory.local(store, mapper, identity -> Mono.just(scope(identity)),
                ReportEvidenceFixture.evidence(), CLOCK);
    }

    private static AuthorizedDeviceScope scope(RequestIdentity request) {
        return new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return request; }
            @Override public IotSourceEnum source() { return IotSourceEnum.COMPANY; }
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

    private ReportFactProducer.ReportCheckpoint checkpoint(String taskId, String payload, Instant checkpointAt) {
        return new ReportFactProducer.ReportCheckpoint(
                taskId, "phase-1", "company", "XSJ", "owner-1",
                "昨日用电统计并保存报告", "COMPLETED", checkpointAt, CLOCK.instant(), payload);
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
