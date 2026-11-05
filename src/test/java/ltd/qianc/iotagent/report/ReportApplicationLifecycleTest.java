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
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import reactor.core.publisher.Mono;

/** Runs the scanned application module rather than constructing report services by hand. */
class ReportApplicationLifecycleTest {
    @Test
    void scannedReportCanBeGeneratedReopenedAndDownloaded() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "agent.storage.mysql.enabled=true");
            // Production scans report components before StorageConfiguration declares its store bean.
            context.register(Reports.class, Dependencies.class);
            context.refresh();
            var principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");
            var identity = new RequestIdentity(principal.authorization(), principal.tenantCode(),
                    IotSourceEnum.COMPANY, principal.ownerKey());
            var generated = context.getBean(ReportGenerationPort.class).generate(identity,
                    "conversation-1", "turn-1", "request-1", ReportEvidenceFixture.energy("meter-1", false));
            ReportService reports = context.getBean(ReportService.class);
            assertThatThrownBy(() -> reports.conversationFacts("another-conversation",
                    List.of(generated.reportId()), identity)).isInstanceOf(ReportNotFoundException.class);
            for (RequestIdentity foreign : List.of(
                    new RequestIdentity(identity.authorization(), "OTHER", IotSourceEnum.COMPANY, identity.ownerKey()),
                    new RequestIdentity(identity.authorization(), identity.tenantCode(), IotSourceEnum.COMPANY, "other-owner"),
                    new RequestIdentity(identity.authorization(), identity.tenantCode(), IotSourceEnum.OFFICE, identity.ownerKey()))) {
                assertThatThrownBy(() -> reports.conversationFacts("conversation-1",
                        List.of(generated.reportId()), foreign)).isInstanceOf(ReportNotFoundException.class);
            }
            var http = MockMvcBuilders.webAppContextSetup(context).build();
            PrincipalContext.set(principal);
            try {
                http.perform(get("/api/agent/reports/" + generated.reportId()).param("source", "company"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.report.status").value("completed"))
                        .andExpect(jsonPath("$.report.id").value(generated.reportId()));
                http.perform(get("/api/agent/reports/" + generated.reportId() + "/download")
                                .param("source", "company").param("format", "xlsx"))
                        .andExpect(status().isOk())
                        .andExpect(result -> assertThat(result.getResponse().getContentType())
                                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            } finally {
                PrincipalContext.clear();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("ltd.qianc.iotagent.report.internal")
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class Reports { }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean ObservationAdapter observations() { return ReportEvidenceFixture.evidence(); }
        @Bean ReportFactStore facts() {
            return new ReportFactStore() {
                private final Map<String, ReportFact> rows = new LinkedHashMap<>();
                @Override public ReportFact save(ReportFact fact) {
                    rows.putIfAbsent(fact.reportId(), fact);
                    return rows.get(fact.reportId());
                }
                @Override public Optional<ReportFact> find(String id) { return Optional.ofNullable(rows.get(id)); }
            };
        }
        @Bean IotAdapter iot() {
            return new IotAdapter() {
                @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                    return Mono.just(new AuthorizedDeviceScope() {
                        @Override public RequestIdentity identity() { return identity; }
                        @Override public IotSourceEnum source() { return IotSourceEnum.COMPANY; }
                        @Override public List<AuthorizedDevice> devices() {
                            return List.of(new AuthorizedDevice("meter-1", "energy", "Meter", "Building", false));
                        }
                    });
                }
            };
        }
    }
}
