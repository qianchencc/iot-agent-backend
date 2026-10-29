package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentToolBridgeContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrincipalInfo principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");

    @Test
    void publicFactoryRoutesObservationThroughTheJavaSeam() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ObservationAdapter observations = mock(ObservationAdapter.class);
        when(conversations.toolPrincipal("tool-1", "conversation-1", "turn-1")).thenReturn(principal);
        when(observations.read(any(ObservationRequest.class)))
                .thenReturn(Mono.just(ObservationResponse.available(Map.of("records", List.of(Map.of("id", "alert-1"))))));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class), observations,
                mock(TaskProjectionStore.class), mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        Map<String, Object> result = bridge.execute(Map.of("toolContextId", "tool-1", "conversationId", "conversation-1",
                "turnId", "turn-1", "toolName", "query_alerts", "arguments", Map.of("source", "company", "days", 3)));

        assertThat(result).containsKey("records");
        verify(observations).read(any(ObservationRequest.class));
    }

    @Test
    void safetyToolArgumentsPreserveRuntimeEventIdentityAndTypeFilters() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ObservationAdapter observations = mock(ObservationAdapter.class);
        when(conversations.toolPrincipal("tool-1", "conversation-1", "turn-1")).thenReturn(principal);
        java.util.concurrent.atomic.AtomicReference<ObservationRequest> request = new java.util.concurrent.atomic.AtomicReference<>();
        when(observations.read(any(ObservationRequest.class))).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return Mono.just(ObservationResponse.available(Map.of("record", Map.of("eventId", "event-1"))));
        });
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class), observations,
                mock(TaskProjectionStore.class), mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        bridge.execute(Map.of("toolContextId", "tool-1", "conversationId", "conversation-1",
                "turnId", "turn-1", "toolName", "review_safety_event",
                "arguments", Map.of("source", "company", "eventId", "event-1", "deviceId", "gas-1", "ledger", "gas")));

        assertThat(request.get().operation()).isEqualTo(ltd.qianc.iotagent.iot.ObservationOperation.SAFETY_EVENT);
        assertThat(request.get().parameters()).containsEntry("id", "event-1")
                .containsEntry("deviceId", "gas-1")
                .containsEntry("ledger", "gas");
    }

    @Test
    void publicFactoryFailsClosedForProductionVideoPayloads() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolPrincipal("tool-1", "conversation-1", "turn-1")).thenReturn(principal);
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class),
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        assertThatThrownBy(() -> bridge.execute(Map.of("toolContextId", "tool-1", "conversationId", "conversation-1",
                "turnId", "turn-1", "toolName", "prepare_video_playback", "arguments", Map.of("source", "company"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo("AGENT_TOOL_UNAVAILABLE"));
    }
}
