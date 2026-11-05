package ltd.qianc.iotagent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import reactor.core.publisher.Mono;

/** Public package-root/application-entry fixture for report conversation and HTTP contracts. */
class ReportConversationHttpFixtureTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrincipalInfo principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");
    private final RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(),
            IotSourceEnum.COMPANY, principal.ownerKey());

    @AfterEach
    void clearPrincipal() {
        PrincipalContext.clear();
    }

    @Test
    void duplicateAndReferenceFailuresAreMappedWithoutSuccessFallback() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter scope = new MutableScopeAdapter();
        ReportGenerationService generation = ReportGenerationServiceFactory.local(
                store, mapper, scope, ReportEvidenceFixture.evidence());

        ReportGenerationPort.GeneratedReport original = generation.generate(identity, "conversation-1", "turn-duplicate",
                "request-duplicate", ReportEvidenceFixture.energy("meter-1", true));
        ReportGenerationPort.GeneratedReport duplicate = generation.generate(identity, "conversation-1", "turn-duplicate",
                "request-duplicate", ReportEvidenceFixture.energy("meter-1", false));
        assertThat(duplicate.reused()).isTrue();
        assertThat(duplicate.report()).containsEntry("status", "completed");

        assertThatThrownBy(() -> generation.generate(identity, "conversation-1", "turn-revoked", "req-revoked",
                ReportEvidenceFixture.energy("revoked-meter", false)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("REPORT_SCOPE_CHANGED");
        RequestIdentity otherTenant = new RequestIdentity("Bearer other", "OTHER", IotSourceEnum.COMPANY, "owner-2");
        assertThatThrownBy(() -> generation.generate(
                new RequestIdentity("Bearer office", "TENANT", IotSourceEnum.OFFICE, "owner-1"),
                "conversation-1", "turn-office", "request-office", ReportEvidenceFixture.energy("meter-1", false)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("REPORT_SOURCE_UNSUPPORTED");
        ReportService reports = ReportServiceFactory.local(store, mapper, scope);
        assertThatThrownBy(() -> reports.read(original.reportId(), otherTenant))
                .isInstanceOf(ReportNotFoundException.class);
    }

    @Test
    void publicReadAndDownloadPreserveStatusAndResponseHeaders() throws Exception {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter scope = new MutableScopeAdapter();
        ReportGenerationService generation = ReportGenerationServiceFactory.local(
                store, mapper, scope, ReportEvidenceFixture.evidence());
        ReportGenerationPort.GeneratedReport generated = generation.generate(identity, "conversation-1", "turn-http",
                "request-http", ReportEvidenceFixture.energy("meter-1", false));
        MockMvc reports = reportHttp(ReportServiceFactory.local(store, mapper, scope));

        reports.perform(get("/api/agent/reports/" + generated.reportId()).queryParam("source", "company"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.status").value("completed"))
                .andExpect(jsonPath("$.report.contentHash").isNotEmpty())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Report-Content-Hash")).isNotEmpty());
        reports.perform(get("/api/agent/reports/" + generated.reportId() + "/download")
                        .queryParam("source", "company").queryParam("format", "pdf"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Report-Content-Hash")).isNotEmpty())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition"))
                        .contains("attachment;").contains("filename*=UTF-8''"));
        reports.perform(get("/api/agent/reports/" + generated.reportId() + "/download")
                        .queryParam("source", "company").queryParam("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store"));
    }

    @Test
    void publicSourceScopeFormatAndMissingErrorsAreSafe() throws Exception {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter scope = new MutableScopeAdapter();
        ReportGenerationService generation = ReportGenerationServiceFactory.local(
                store, mapper, scope, ReportEvidenceFixture.evidence());
        ReportGenerationPort.GeneratedReport generated = generation.generate(identity, "conversation-1", "turn-errors",
                "request-errors", ReportEvidenceFixture.energy("meter-1", false));
        MockMvc reports = reportHttp(ReportServiceFactory.local(store, mapper, scope));

        reports.perform(get("/api/agent/reports/" + generated.reportId()).queryParam("source", "office"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));
        reports.perform(get("/api/agent/reports/" + generated.reportId() + "/download")
                        .queryParam("source", "company").queryParam("format", "html"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_FORMAT_UNSUPPORTED"));
        reports.perform(get("/api/agent/reports/missing").queryParam("source", "company"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REPORT_NOT_FOUND"));

        MutableScopeAdapter unavailable = new MutableScopeAdapter();
        unavailable.failWith(new IllegalStateException("scope transport details must not escape"));
        MockMvc unavailableReports = reportHttp(ReportServiceFactory.local(store, mapper, unavailable));
        unavailableReports.perform(get("/api/agent/reports/" + generated.reportId())
                        .queryParam("source", "company"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_SCOPE_UNAVAILABLE"));
    }

    @Test
    void rendererFailureIsMappedThroughPublicFactoryWithoutLeakingDetails() throws Exception {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter scope = new MutableScopeAdapter();
        ReportGenerationPort.GeneratedReport generated = ReportGenerationServiceFactory.local(
                store, mapper, scope, ReportEvidenceFixture.evidence())
                .generate(identity, "conversation-1", "turn-render", "request-render",
                        ReportEvidenceFixture.energy("meter-1", false));
        ReportRenderer failingPdf = report -> {
            throw new IllegalStateException("renderer internals must not escape");
        };
        ReportService reports = ReportServiceFactory.local(store, mapper, scope, failingPdf, ReportRendererFactory.xlsx());

        reportHttp(reports).perform(get("/api/agent/reports/" + generated.reportId() + "/download")
                        .queryParam("source", "company").queryParam("format", "pdf"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_PDF_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("报告导出暂不可用"));
    }

    private MockMvc reportHttp(ReportService service) {
        HandlerInterceptor auth = new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                PrincipalContext.set(principal);
                return true;
            }

            @Override
            public void afterCompletion(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler, Exception ex) {
                PrincipalContext.clear();
            }
        };
        ReportHttpApplication application = ReportHttpApplicationFactory.local(service);
        return MockMvcBuilders.standaloneSetup(application.endpoint()).addInterceptors(auth).build();
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

    private static final class MutableScopeAdapter implements IotAdapter {
        private final List<AuthorizedDevice> devices = new java.util.ArrayList<>(List.of(
                new AuthorizedDevice("meter-1", "energy", "Meter 1", "Building", false)));
        private RuntimeException failure;

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity request) {
            if (failure != null) return Mono.error(failure);
            return Mono.just(new AuthorizedDeviceScope() {
                @Override
                public RequestIdentity identity() { return request; }

                @Override
                public IotSourceEnum source() { return IotSourceEnum.COMPANY; }

                @Override
                public List<AuthorizedDevice> devices() { return List.copyOf(devices); }
            });
        }
    }
}
