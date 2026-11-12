package com.ehe.iotagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.ehe.iotagent.conversation.ReportGenerationPort;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.iot.ObservationOperationEnum;
import com.ehe.iotagent.iot.ObservationRequest;
import com.ehe.iotagent.iot.ObservationResponse;
import com.ehe.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ReportGenerationContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestIdentity identity = new RequestIdentity("Bearer test", "TENANT", IotSourceEnum.COMPANY, "owner-1");

    @Test
    void noEffectiveCurrentDataDoesNotCreateAnEmptyReportArtifact() {
        MemoryStore store = new MemoryStore();
        ObservationAdapter empty = request -> new Evidence().read(request).map(response -> {
            Map<String, Object> payload = new LinkedHashMap<>(response.payload());
            payload.put("coverage", Map.of("meters", 1, "dataMeters", 0,
                    "noData", List.of("meter-1"), "partial", List.of(), "truncated", false));
            return ObservationResponse.available(payload);
        });
        ReportGenerationService service = ReportGenerationServiceFactory.local(store, mapper,
                new MutableScopeAdapter(), empty, CLOCK);
        assertThatThrownBy(() -> service.generate(identity, "conversation-1", "turn-empty", "empty",
                energy("meter-1", false))).isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORT_NO_DATA"));
        assertThat(store.values).isEmpty();
    }

    @Test
    void duplicateConversationRequestReturnsTheImmutableOriginalSnapshot() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService service = service(store, iot);

        ReportGenerationPort.GeneratedReport first = service.generate(identity, "conversation-1", "turn-1", "request-1",
                energy("meter-1", true));
        ReportGenerationPort.GeneratedReport duplicate = service.generate(identity, "conversation-1", "turn-1", "request-1",
                energy("meter-1", false));

        assertThat(duplicate.reused()).isTrue();
        assertThat(duplicate.reportId()).isEqualTo(first.reportId());
        assertThat(duplicate.report()).containsEntry("status", "completed");
        assertThat(store.values).hasSize(1);
    }

    @Test
    void callerAuthoredBlocksAndTotalsAreRejected() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService service = service(store, iot);
        Map<String, Object> content = new LinkedHashMap<>(energy("meter-1", false));
        content.put("blocks", List.of(Map.of("type", "text", "text", "model total 999")));

        assertThatThrownBy(() -> service.generate(identity, "conversation-1", "turn-block", "request-block", content))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("INVALID_REPORT");
    }

    @Test
    void revokedMeterIsRejectedBeforeReportGeneration() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService service = service(store, iot);

        assertThatThrownBy(() -> service.generate(identity, "conversation-1", "turn-2", "request-2",
                energy("revoked-meter", false)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("REPORT_SCOPE_CHANGED");
    }

    @Test
    void readingACompletedReportRechecksFixedMeterReferencesAfterRestart() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationPort.GeneratedReport saved = service(store, iot).generate(identity, "conversation-1", "turn-3", "request-3",
                energy("meter-1", true));
        ReportService reports = ReportServiceFactory.local(store, mapper, iot);

        Map<String, Object> report = reports.read(saved.reportId(), identity);
        assertThat(report).containsEntry("id", saved.reportId());
        assertThat(report.get("contentHash")).isNotNull();
        iot.devices.clear();
        assertThatThrownBy(() -> reports.read(saved.reportId(), identity))
                .isInstanceOf(ReportNotFoundException.class);
    }

    @Test
    void reportFactsCannotBeReadByAnotherTenantOrOwner() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationPort.GeneratedReport saved = service(store, iot).generate(identity, "conversation-1", "turn-4", "request-4",
                energy("meter-1", false));
        RequestIdentity other = new RequestIdentity("Bearer other", "OTHER", IotSourceEnum.COMPANY, "owner-2");
        assertThatThrownBy(() -> ReportServiceFactory.local(store, mapper, iot).read(saved.reportId(), other))
                .isInstanceOf(ReportNotFoundException.class);
    }

    @Test
    void serverEvidenceBuildsComparisonBlockAndCoverage() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationPort.GeneratedReport saved = service(store, iot).generate(identity, "conversation-1", "turn-comparison",
                "request-comparison", energy("meter-1", true));

        assertThat(saved.report()).containsEntry("status", "completed");
        List<?> blocks = (List<?>) saved.report().get("blocks");
        assertThat(blocks).hasSize(2);
        Map<?, ?> comparison = (Map<?, ?>) ((Map<?, ?>) blocks.get(1)).get("report");
        assertThat(comparison.get("kind")).isEqualTo("comparison");
        assertThat(((Map<?, ?>) comparison.get("diff")).get("value")).isEqualTo(5.0d);
        assertThat(saved.report().get("meterIds")).isEqualTo(List.of("meter-1"));
        assertThat(saved.report().get("authorizationMeterIds")).isEqualTo(List.of("meter-1"));
        assertThat(saved.report().get("contentHash")).isNull();
    }

    @Test
    void safetyReportUsesServerSafetyLedgerAndKeepsMissingLedgersExplicit() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService service = service(store, iot);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "safety");
        content.put("period", Map.of("kind", "absolute", "from", "2026-10-01T08:00:00+08:00",
                "to", "2026-10-02T08:00:00+08:00"));
        content.put("types", List.of("smoke", "gas"));

        ReportGenerationPort.GeneratedReport saved = service.generate(identity, "conversation-1", "turn-safety", "request-safety", content);
        List<?> blocks = (List<?>) saved.report().get("blocks");
        assertThat(blocks).hasSize(3);
        assertThat(((Map<?, ?>) blocks.get(0)).get("type")).isEqualTo("safety_ledger");
        assertThat(((Map<?, ?>) blocks.get(1)).get("type")).isEqualTo("safety_ledger");
        assertThat(((Map<?, ?>) blocks.get(2)).get("type")).isEqualTo("text");
        assertThat(saved.report()).containsEntry("status", "partial");
    }

    @Test
    void safetyReportCountsOnlyServerPinnedFixedMembers() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        iot.devices.add(new AuthorizedDevice("sensor-1", "safety", "传感器", "26楼", true));
        iot.devices.add(new AuthorizedDevice("sensor-2", "safety", "传感器2", "27楼", true));
        ReportGenerationService service = service(store, iot);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "safety");
        content.put("period", Map.of("kind", "absolute", "from", "2026-10-01T08:00:00+08:00",
                "to", "2026-10-02T08:00:00+08:00"));
        content.put("types", List.of("smoke"));
        content.put("fixedDeviceIds", List.of("sensor-1"));

        ReportGenerationPort.GeneratedReport saved = service.generate(identity,
                "conversation-fixed", "turn-fixed", "request-fixed", content);
        Map<?, ?> coverage = (Map<?, ?>) saved.report().get("coverage");
        assertThat(coverage.get("events")).isEqualTo(1);
        assertThat(coverage.get("fixedDeviceCount")).isEqualTo(1);
        assertThat((List<?>) ((Map<?, ?>) ((List<?>) saved.report().get("blocks")).get(0)).get("events")).hasSize(1);

        Map<String, Object> elsewhere = new LinkedHashMap<>(content);
        elsewhere.put("fixedDeviceIds", List.of("sensor-2"));
        ReportGenerationPort.GeneratedReport filtered = service.generate(identity,
                "conversation-filtered", "turn-filtered", "request-filtered", elsewhere);
        Map<?, ?> filteredCoverage = (Map<?, ?>) filtered.report().get("coverage");
        assertThat(filteredCoverage.get("events")).isEqualTo(0);
        assertThat(filteredCoverage.get("fixedDeviceCount")).isEqualTo(1);
        List<?> blocks = (List<?>) filtered.report().get("blocks");
        assertThat((List<?>) ((Map<?, ?>) blocks.get(0)).get("events")).isEmpty();
    }

    @Test
    void sourceAndUnknownReportKindsAreRejected() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService service = service(store, iot);
        assertThatThrownBy(() -> service.generate(new RequestIdentity("Bearer office", "TENANT", IotSourceEnum.OFFICE, "owner-1"),
                "conversation-1", "turn-office", "request-office", energy("meter-1", false)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("REPORT_SOURCE_UNSUPPORTED");
        assertThatThrownBy(() -> service.generate(identity, "conversation-1", "turn-kind", "request-kind",
                Map.of("kind", "freeform")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("REPORT_KIND_UNSUPPORTED");
    }

    private ReportGenerationService service(ReportFactStore store, IotAdapter iot) {
        return ReportGenerationServiceFactory.local(store, mapper, iot, new Evidence(), CLOCK);
    }

    private static Map<String, Object> energy(String meterId, boolean compare) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "energy");
        content.put("title", "公司用电报告");
        content.put("period", Map.of("kind", "absolute", "from", "2026-09-01T08:00:00+08:00",
                "to", "2026-10-01T08:00:00+08:00"));
        content.put("scope", Map.of("level", "selection", "meterIds", List.of(meterId)));
        if (compare) content.put("comparisons", List.of("previousPeriod"));
        return content;
    }

    /** Public-seam observation double; it emits the shape a real composer must return. */
    private static final class Evidence implements ObservationAdapter {
        @Override
        public Mono<ObservationResponse> read(ObservationRequest request) {
            if (request.operation() == ObservationOperationEnum.SAFETY_EVENTS) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("items", List.of(Map.of("eventId", "event-" + request.parameters().get("type"),
                        "ledger", request.parameters().get("type"), "deviceId", "sensor-1",
                        "deviceName", "传感器", "occurredAt", "2026-10-01T09:00:00+08:00", "alarm", true)));
                payload.put("total", 1);
                payload.put("scanned", 1);
                if ("gas".equals(request.parameters().get("type"))) payload.put("coverageComplete", false);
                else payload.put("coverageComplete", true);
                return Mono.just(ObservationResponse.available(payload));
            }
            Map<String, Object> params = new LinkedHashMap<>();
            Map<String, Object> requested = Map.of("from", request.parameters().get("from"),
                    "to", request.parameters().get("to"));
            Map<String, Object> span = new LinkedHashMap<>();
            span.put("requested", requested);
            params.put("span", span);
            params.put("scope", Map.of("level", "selection", "label", "test", "basis", "deviceIds", "meterCount", 1));
            params.put("axis", request.parameters().get("axis"));
            if (request.parameters().containsKey("baseFrom")) {
                Map<String, Object> baseline = new LinkedHashMap<>();
                baseline.put("requested", Map.of("from", request.parameters().get("baseFrom"),
                        "to", request.parameters().get("baseTo")));
                params.put("baseline", baseline);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "meter-report");
            payload.put("kind", request.parameters().get("kind"));
            payload.put("params", params);
            payload.put("total", Map.of("value", 10.0d, "unit", "度"));
            if (request.parameters().containsKey("baseFrom")) {
                payload.put("diff", Map.of("value", 5.0d, "pct", 100.0d, "basis", "total", "unit", "度"));
            }
            payload.put("coverage", Map.of("meters", 1, "dataMeters", 1, "noData", List.of(),
                    "partial", List.of(), "truncated", false));
            payload.put("meters", List.of(Map.of("id", "meter-1", "name", "Meter 1", "areaName", "26楼",
                    "value", 10.0d, "status", "ok", "a", Map.of("value", 10.0d, "status", "ok"),
                    "b", Map.of("value", 5.0d, "status", "ok"))));
            payload.put("authorizationMeterIds", List.of("meter-1"));
            return Mono.just(ObservationResponse.available(payload));
        }
    }

    private static final class MemoryStore implements ReportFactStore {
        private final Map<String, ReportFact> values = new LinkedHashMap<>();
        @Override public ReportFact save(ReportFact report) { values.putIfAbsent(report.reportId(), report); return values.get(report.reportId()); }
        @Override public Optional<ReportFact> find(String reportId) { return Optional.ofNullable(values.get(reportId)); }
    }

    private final class MutableScopeAdapter implements IotAdapter {
        private final List<AuthorizedDevice> devices = new ArrayList<>(List.of(
                new AuthorizedDevice("meter-1", "energy", "Meter 1", "Building", false)));
        @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity request) {
            return Mono.just(new AuthorizedDeviceScope() {
                @Override public RequestIdentity identity() { return request; }
                @Override public IotSourceEnum source() { return IotSourceEnum.COMPANY; }
                @Override public List<AuthorizedDevice> devices() { return List.copyOf(devices); }
            });
        }
    }

}
