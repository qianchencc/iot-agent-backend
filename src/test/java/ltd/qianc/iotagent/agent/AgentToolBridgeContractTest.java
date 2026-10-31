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
    void readDeviceStatesAcceptsRuntimeCandidateSetShape() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        when(conversations.toolPrincipal("tool-1", "conversation-1", "turn-1")).thenReturn(principal);
        when(iot.readDeviceStates(any(), org.mockito.ArgumentMatchers.eq(List.of("device-1"))))
                .thenReturn(Mono.just(new ltd.qianc.iotagent.iot.DeviceStateReadResult(
                        Map.of("device-1", Map.of("power", "on")), "2026-10-30T00:00:00Z")));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot,
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        Map<String, Object> result = bridge.execute(Map.of("toolContextId", "tool-1", "conversationId", "conversation-1",
                "turnId", "turn-1", "toolName", "read_device_states",
                "arguments", Map.of("source", "office", "candidateSets",
                        List.of(Map.of("candidateSetId", "set-1", "deviceIds", List.of("device-1"))))));

        assertThat(result).containsEntry("observedAt", "2026-10-30T00:00:00Z");
        assertThat(result.get("states")).isEqualTo(Map.of("device-1", Map.of("power", "on")));
    }

    @Test
    void proposeActionPreservesExplicitKindWhenNormalizingFlatFields() throws Exception {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ActionDraftFacade actions = mock(ActionDraftFacade.class);
        when(conversations.toolPrincipal("tool-1", "conversation-1", "turn-1")).thenReturn(principal);
        java.util.concurrent.atomic.AtomicReference<String> payload = new java.util.concurrent.atomic.AtomicReference<>();
        when(actions.propose(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            payload.set(invocation.getArgument(4));
            return new ltd.qianc.iotagent.action.ActionDraftFact("draft-1", 1, "PENDING_CONFIRMATION", payload.get(), "turn-1");
        });
        when(actions.view(any())).thenReturn(Map.of("draftId", "draft-1", "status", "PENDING_CONFIRMATION"));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class),
                actions, mock(TaskFacade.class), mapper, null);

        bridge.execute(Map.of("toolContextId", "tool-1", "conversationId", "conversation-1",
                "turnId", "turn-1", "toolName", "propose_action_draft",
                "arguments", Map.of("source", "office", "candidateSetIds", List.of("set-1"),
                        "actions", List.of(Map.of("deviceId", "device-1", "kind", "lighting",
                                "closeOrOpen", 1, "selected", true)))));

        assertThat(mapper.readTree(payload.get()).at("/actions/0/kind").asText()).isEqualTo("lighting");
        assertThat(mapper.readTree(payload.get()).at("/actions/0/command/power").asText()).isEqualTo("on");
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
