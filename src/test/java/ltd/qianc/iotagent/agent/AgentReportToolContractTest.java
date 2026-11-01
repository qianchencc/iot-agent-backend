package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ReportDownload;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.report.ReportGenerationService;
import ltd.qianc.iotagent.report.ReportEvidenceFixture;
import ltd.qianc.iotagent.report.ReportGenerationServiceFactory;
import ltd.qianc.iotagent.report.ReportService;
import ltd.qianc.iotagent.report.ReportServiceFactory;
import ltd.qianc.iotagent.task.TaskFacade;
import reactor.core.publisher.Mono;

class AgentReportToolContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrincipalInfo principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");
    private final RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(),
            IotSource.COMPANY, principal.ownerKey());

    @org.junit.jupiter.api.Test
    void authenticatedAgentBridgeCreatesIdempotentReportAndExposesDownloads() {
        MemoryStore store = new MemoryStore();
        MutableScopeAdapter scope = new MutableScopeAdapter();
        ReportGenerationService generation = ReportGenerationServiceFactory.local(store, mapper, scope, ReportEvidenceFixture.evidence());
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolPrincipal("tool-1", "conversation-1", "turn-1")).thenReturn(principal);
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class),
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, generation);

        Map<String, Object> result = bridge.execute(Map.of("toolContextId", "tool-1", "conversationId", "conversation-1",
                "turnId", "turn-1", "toolName", "create_report", "arguments", Map.of("source", "company",
                        "idempotencyKey", "req-1", "report", ReportEvidenceFixture.energy("meter-1", false))));
        String reportId = String.valueOf(result.get("reportId"));
        assertThat(result).containsEntry("reused", false);

        ReportService reports = ReportServiceFactory.local(store, mapper, scope);
        assertThat(reports.read(reportId, identity)).containsEntry("status", "completed");
        ReportDownload pdf = reports.download(reportId, "pdf", identity);
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(pdf.filename()).endsWith(".pdf");
        ReportGenerationService.GeneratedReport replay = generation.generate(identity, "conversation-1", "turn-1",
                "req-1", ReportEvidenceFixture.energy("meter-1", false));
        assertThat(replay.reused()).isTrue();
    }

    private static final class MemoryStore implements ReportFactStore {
        private final Map<String, ReportFact> values = new LinkedHashMap<>();
        @Override public ReportFact save(ReportFact report) { values.putIfAbsent(report.reportId(), report); return values.get(report.reportId()); }
        @Override public Optional<ReportFact> find(String reportId) { return Optional.ofNullable(values.get(reportId)); }
    }

    private static final class MutableScopeAdapter implements IotAdapter {
        private final List<AuthorizedDevice> devices = List.of(new AuthorizedDevice("meter-1", "energy", "Meter 1", "Building", false));
        @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity request) {
            return Mono.just(new AuthorizedDeviceScope() {
                @Override public RequestIdentity identity() { return request; }
                @Override public IotSource source() { return IotSource.COMPANY; }
                @Override public List<AuthorizedDevice> devices() { return devices; }
            });
        }
    }
}
