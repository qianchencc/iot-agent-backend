package ltd.qianc.iotagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Mono;

/** Public application-entry contract fixture for conversation-generated reports. */
class ReportConversationHttpFixtureTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrincipalInfo principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");
    private final RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(),
            IotSource.COMPANY, principal.ownerKey());


    @org.junit.jupiter.api.Test
    void statusesDuplicateAndReferenceFailuresAreMappedWithoutSuccessFallback() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter scope = new MutableScopeAdapter();
        ReportGenerationService generation = ReportGenerationServiceFactory.local(store, mapper, scope);
        for (String status : List.of("preparing", "partial", "failed", "completed")) {
            ReportGenerationPort.GeneratedReport result = generation.generate(identity, "conversation-1", "turn-" + status,
                    "request-" + status, content(status, "meter-1"));
            assertThat(result.report()).containsEntry("status", status);
        }
        ReportGenerationPort.GeneratedReport original = generation.generate(identity, "conversation-1", "turn-duplicate",
                "request-duplicate", content("completed", "meter-1"));
        ReportGenerationPort.GeneratedReport duplicate = generation.generate(identity, "conversation-1", "turn-duplicate",
                "request-duplicate", content("failed", "meter-1"));
        assertThat(duplicate.reused()).isTrue();
        assertThat(duplicate.report()).containsEntry("status", "completed");

        assertThatThrownBy(() -> generation.generate(identity, "conversation-1", "turn-revoked", "req-revoked",
                content("partial", "revoked-meter")))
                .isInstanceOf(ltd.qianc.iotagent.error.BusinessException.class)
                .extracting(error -> ((ltd.qianc.iotagent.error.BusinessException) error).code())
                .isEqualTo("REPORT_SCOPE_CHANGED");
        RequestIdentity otherTenant = new RequestIdentity("Bearer other", "OTHER", IotSource.COMPANY, "owner-2");
        ReportService reports = ReportServiceFactory.local(store, mapper, scope);
        assertThatThrownBy(() -> reports.read(original.reportId(), otherTenant))
                .isInstanceOf(ReportNotFoundException.class);
    }

    @org.junit.jupiter.api.Test
    void missingReportAndFormatErrorsAreSafePublicErrors() {
        ReportService reports = ReportServiceFactory.local(new MemoryStore(), mapper, new MutableScopeAdapter());
        assertThatThrownBy(() -> reports.read("missing", identity)).isInstanceOf(ReportNotFoundException.class);
        assertThatThrownBy(() -> reports.download("missing", "html", identity))
                .isInstanceOf(ReportFormatException.class);
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", "Energy");
        result.put("status", status);
        result.put("meterIds", List.of(meterId));
        result.put("blocks", new ArrayList<>(List.of(block)));
        return result;
    }

    private static final class MemoryStore implements ReportFactStore {
        private final Map<String, ReportFact> values = new LinkedHashMap<>();
        @Override public ReportFact save(ReportFact report) { values.putIfAbsent(report.reportId(), report); return values.get(report.reportId()); }
        @Override public Optional<ReportFact> find(String reportId) { return Optional.ofNullable(values.get(reportId)); }
    }

    private static final class MutableScopeAdapter implements IotAdapter {
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
