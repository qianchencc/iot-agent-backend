package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.DeviceCandidateBinding;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskFacade;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentRunRecoveryContractTest {
    @Test
    void repeatedIdenticalFailureStopsButNewEvidenceReopensLegalRecovery() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "company", null, Map.of()));
        var reads = new AtomicInteger();
        IotAdapter iot = new IotAdapter() {
            @Override public Mono<ltd.qianc.iotagent.iot.AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                throw new UnsupportedOperationException();
            }
            @Override public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity,
                    DeviceCandidateQuery query, DeviceCandidateBinding binding) {
                reads.incrementAndGet();
                if ("26楼".equals(query.scopeText())) return Mono.error(new BusinessException("UPSTREAM_UNAVAILABLE", "暂不可读", 503));
                return Mono.just(new DeviceCandidateSet("set-27", query, List.of(), true, 0));
            }
        };
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot, mock(ActionDraftFacade.class),
                mock(TaskFacade.class), new ObjectMapper(), null);
        for (int n = 1; n <= 2; n++) {
            assertThat(catchThrowableOfType(BusinessException.class,
                    () -> bridge.execute(request("26楼", "failed-" + reads.get()))).code()).isEqualTo("UPSTREAM_UNAVAILABLE");
        }
        BusinessException blocked = catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(request("26楼", "blocked")));
        assertThat(blocked.code()).isEqualTo("AGENT_NO_PROGRESS");
        assertThat(blocked).isNotInstanceOf(AgentToolHaltException.class);
        assertThat(reads.get()).isEqualTo(2);
        bridge.execute(request("27楼", "new-evidence"));
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(request("26楼", "recover"))).code()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(reads.get()).isEqualTo(4);
    }

    @Test
    void changedEvidenceReopensRecoveryEvenAfterTheThirdReadButNewCandidateIdsDoNot() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "company", null, Map.of()));
        var version = new AtomicInteger(1);
        var reads = new AtomicInteger();
        IotAdapter iot = new IotAdapter() {
            @Override public Mono<ltd.qianc.iotagent.iot.AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                throw new UnsupportedOperationException();
            }
            @Override public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity,
                    DeviceCandidateQuery query, DeviceCandidateBinding binding) {
                if ("26楼".equals(query.scopeText())) return Mono.error(new BusinessException("UPSTREAM_UNAVAILABLE", "暂不可读", 503));
                return Mono.just(new DeviceCandidateSet("set-" + reads.incrementAndGet(), query,
                        List.of(new ltd.qianc.iotagent.iot.AuthorizedDevice("light-1", "lighting",
                                "灯版本" + version.get(), "27楼", false)), true, 1));
            }
        };
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot, mock(ActionDraftFacade.class),
                mock(TaskFacade.class), new ObjectMapper(), null);
        bridge.execute(request("27楼", "read-1"));
        bridge.execute(request("27楼", "read-2"));
        for (int n = 0; n < 2; n++) {
            String callId = "failed-" + n;
            assertThat(catchThrowableOfType(BusinessException.class,
                    () -> bridge.execute(request("26楼", callId))).code())
                    .isEqualTo("UPSTREAM_UNAVAILABLE");
        }
        bridge.execute(request("27楼", "read-3"));
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(request("26楼", "still-blocked"))).code()).isEqualTo("AGENT_NO_PROGRESS");
        version.incrementAndGet();
        bridge.execute(request("27楼", "read-4"));
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(request("26楼", "retry-with-new-evidence"))).code()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    private static Map<String, Object> request(String scope, String call) {
        return Map.of("runId", "run", "toolContextId", "context", "conversationId", "conversation",
                "turnId", "turn", "toolName", "find_device_candidates", "toolCallId", call,
                "arguments", Map.of("kind", "lighting", "scopeText", scope));
    }
}
