package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.task.TaskFacade;
import org.junit.jupiter.api.Test;

class AgentReportToolContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrincipalInfo principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");

    @Test
    void dispatchesDeclarativeReportIntentWithBoundIdentityAndServerIdempotency() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ReportGenerationPort reports = mock(ReportGenerationPort.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(principal, "run-1", "conversation-1", "turn-1",
                        "company", null, Map.of()));
        AtomicReference<ltd.qianc.iotagent.iot.RequestIdentity> identity = new AtomicReference<>();
        AtomicReference<Map<String, Object>> content = new AtomicReference<>();
        when(reports.generate(any(), eq("conversation-1"), eq("turn-1"), eq("turn-1:call-1"), any()))
                .thenAnswer(invocation -> {
                    identity.set(invocation.getArgument(0));
                    content.set(invocation.getArgument(4));
                    return new ReportGenerationPort.GeneratedReport("report-1", false,
                            Map.of("id", "report-1", "title", "用电日报",
                                    "generatedAt", "2026-11-02T02:00:00Z"));
                });
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class),
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, reports);

        Map<String, Object> result = bridge.execute(Map.of("runId", "run-1", "toolCallId", "call-1",
                "toolContextId", "tool-1", "conversationId", "conversation-1", "turnId", "turn-1",
                "toolName", "create_energy_report", "arguments", reportIntent()));

        assertThat(result).containsEntry("reportId", "report-1").containsEntry("reused", false);
        assertThat(identity.get().source().wireValue()).isEqualTo("company");
        assertThat(identity.get().tenantCode()).isEqualTo("TENANT");
        assertThat(content.get()).containsEntry("kind", "energy")
                .containsEntry("title", "用电日报")
                .containsKeys("period", "comparisons", "scope")
                .doesNotContainKeys("blocks", "totals", "status", "source", "idempotencyKey");
        verify(reports).generate(any(), eq("conversation-1"), eq("turn-1"), eq("turn-1:call-1"), any());
    }

    private static Map<String, Object> reportIntent() {
        Map<String, Object> period = Map.of("kind", "absolute",
                "from", "2026-11-01T00:00:00+08:00", "to", "2026-11-02T00:00:00+08:00");
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("level", "floor");
        scope.put("floor", "26楼");
        return Map.of("kind", "energy", "title", "用电日报", "period", period,
                "comparisons", List.of("previousPeriod"), "scope", scope);
    }
}
