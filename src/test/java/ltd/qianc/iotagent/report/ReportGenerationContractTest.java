package ltd.qianc.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportGenerationContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RequestIdentity identity = new RequestIdentity("Bearer test", "TENANT", IotSource.COMPANY, "owner-1");

    @Test
    void duplicateConversationRequestReturnsTheImmutableOriginalSnapshot() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService service = ReportGenerationServiceFactory.local(store, mapper, iot);

        ReportGenerationService.GeneratedReport first = service.generate(identity, "conversation-1", "turn-1", "request-1", content("completed", "meter-1"));
        ReportGenerationService.GeneratedReport duplicate = service.generate(identity, "conversation-1", "turn-1", "request-1", content("failed", "meter-1"));

        assertThat(duplicate.reused()).isTrue();
        assertThat(duplicate.reportId()).isEqualTo(first.reportId());
        assertThat(duplicate.report()).containsEntry("status", "completed");
        assertThat(store.values).hasSize(1);
    }

    @Test
    void everyBlockAndAuthorizationReferenceMustRemainInCurrentEnergyScope() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService service = ReportGenerationServiceFactory.local(store, mapper, iot);
        Map<String, Object> invalid = content("partial", "meter-1");
        Map<String, Object> block = (Map<String, Object>) ((List<?>) invalid.get("blocks")).get(0);
        Map<String, Object> report = (Map<String, Object>) block.get("report");
        report.put("meterId", "revoked-meter");

        assertThatThrownBy(() -> service.generate(identity, "conversation-1", "turn-2", "request-2", invalid))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("REPORT_SCOPE_CHANGED");
    }
    @Test
    void preparingAndFailedStatusesRemainFactsRatherThanBeingReportedAsSuccess() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService service = ReportGenerationServiceFactory.local(store, mapper, iot);

        assertThat(service.generate(identity, "conversation-1", "turn-preparing", "request-preparing",
                content("preparing", "meter-1")).report()).containsEntry("status", "preparing");
        assertThat(service.generate(identity, "conversation-1", "turn-failed", "request-failed",
                content("failed", "meter-1")).report()).containsEntry("status", "failed");
    }

    @Test
    void readingACompletedReportRechecksNestedBlockReferencesAfterRestart() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService generator = ReportGenerationServiceFactory.local(store, mapper, iot);
        ReportGenerationService.GeneratedReport saved = generator.generate(
                identity, "conversation-1", "turn-3", "request-3", content("completed", "meter-1"));
        ReportService reports = ReportServiceFactory.local(store, mapper, iot);

        assertThat(reports.read(saved.reportId(), identity)).containsEntry("id", saved.reportId());
        iot.devices.clear();
        assertThatThrownBy(() -> reports.read(saved.reportId(), identity))
                .isInstanceOf(ReportNotFoundException.class);
    }

    @Test
    void reportFactsCannotBeReadByAnotherTenantOrOwner() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter iot = new MutableScopeAdapter();
        ReportGenerationService.GeneratedReport saved = ReportGenerationServiceFactory.local(store, mapper, iot)
                .generate(identity, "conversation-1", "turn-4", "request-4", content("completed", "meter-1"));
        RequestIdentity other = new RequestIdentity("Bearer other", "OTHER", IotSource.COMPANY, "owner-2");
        assertThatThrownBy(() -> ReportServiceFactory.local(store, mapper, iot).read(saved.reportId(), other))
                .isInstanceOf(ReportNotFoundException.class);
    }


    private static Map<String, Object> content(String status, String meterId) {
        Map<String, Object> meter = new LinkedHashMap<>();
        meter.put("id", meterId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("authorizationMeterIds", List.of(meterId));
        report.put("meters", new ArrayList<>(List.of(meter)));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "meter_report");
        block.put("report", report);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("title", "Energy");
        content.put("status", status);
        content.put("meterIds", List.of(meterId));
        content.put("blocks", new ArrayList<>(List.of(block)));
        return content;
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
                @Override public IotSource source() { return IotSource.COMPANY; }
                @Override public List<AuthorizedDevice> devices() { return List.copyOf(devices); }
            });
        }
    }
}
