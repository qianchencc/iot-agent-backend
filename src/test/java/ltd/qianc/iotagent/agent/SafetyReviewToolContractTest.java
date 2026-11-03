package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentEngineEvent;
import ltd.qianc.iotagent.conversation.AgentRunPolicy;
import ltd.qianc.iotagent.conversation.AgentRunRequest;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.VideoPlaybackPreparation;
import ltd.qianc.iotagent.iot.VideoPlaybackWindow;
import ltd.qianc.iotagent.task.TaskFacade;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SafetyReviewToolContractTest {
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final String BEGIN = "2026-11-01T09:45:00.000+08:00";
    private static final String END = "2026-11-01T10:15:00.000+08:00";

    @Test
    void singleCandidatePreparesRealPlaybackCardAndProjectsVideoPlaybackBlock() {
        ConversationFacade conversations = companyConversations();
        ObservationAdapter observations = mock(ObservationAdapter.class);
        IotAdapter iot = mock(IotAdapter.class);
        AtomicReference<ObservationRequest> observed = new AtomicReference<>();
        when(observations.read(any(ObservationRequest.class))).thenAnswer(invocation -> {
            observed.set(invocation.getArgument(0));
            return Mono.just(ObservationResponse.available(cameraPayload(List.of(candidate("cam-1", "大堂监控")))));
        });
        AtomicReference<VideoPlaybackWindow> preparedWindow = new AtomicReference<>();
        when(iot.prepareVideoPlayback(any(), any(), any())).thenAnswer(invocation -> {
            VideoPlaybackWindow window = invocation.getArgument(2);
            preparedWindow.set(window);
            return Mono.just(new VideoPlaybackPreparation("company", "cam-1", "大堂监控", "一层大堂",
                    window.beginTime(), window.endTime()));
        });
        AgentToolBridge bridge = bridge(conversations, iot, observations);

        Map<String, Object> result = bridge.execute(request("review_safety_event",
                Map.of("eventId", "alarm-1", "deviceId", "gas-1", "ledger", "gas")));

        assertThat(observed.get().operation()).isEqualTo(ObservationOperation.SAFETY_EVENT_CAMERAS);
        assertThat(observed.get().parameters()).containsEntry("id", "alarm-1")
                .containsEntry("deviceId", "gas-1").containsEntry("ledger", "gas");
        assertThat(preparedWindow.get().beginTime()).isEqualTo(BEGIN);
        assertThat(preparedWindow.get().endTime()).isEqualTo(END);
        Map<String, Object> playback = map(result.get("videoPlayback"));
        assertThat(playback).containsEntry("deviceId", "cam-1")
                .containsEntry("beginTime", BEGIN).containsEntry("endTime", END);
        assertThat(map(playback.get("safetyContext"))).containsEntry("eventId", "alarm-1");

        AgentRunPolicy policy = policy(conversations, iot, observations);
        List<AgentEngineEvent> projected = policy.projection().apply(new AgentEngineEvent(
                "run-1", "conversation-1", "turn-1", "tool.result",
                Map.of("tool", "review_safety_event", "toolCallId", "call-1", "result", result)));
        assertThat(projected).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("observation.reported");
            List<Map<String, Object>> blocks = listOfMaps(event.fields().get("blocks"));
            assertThat(blocks).singleElement().satisfies(block ->
                    assertThat(block).containsEntry("type", "video_playback"));
        });
    }

    @Test
    void multipleCandidatesRequireExplicitSelectionBeforePreparingPlayback() {
        ConversationFacade conversations = companyConversations();
        ObservationAdapter observations = mock(ObservationAdapter.class);
        IotAdapter iot = mock(IotAdapter.class);
        when(observations.read(any(ObservationRequest.class))).thenReturn(Mono.just(ObservationResponse.available(
                cameraPayload(List.of(candidate("cam-1", "大堂监控"), candidate("cam-2", "电梯监控"))))));
        when(iot.prepareVideoPlayback(any(), any(), any())).thenAnswer(invocation -> {
            VideoPlaybackWindow window = invocation.getArgument(2);
            return Mono.just(new VideoPlaybackPreparation("company", invocation.getArgument(1), "电梯监控", "26楼",
                    window.beginTime(), window.endTime()));
        });
        AgentToolBridge bridge = bridge(conversations, iot, observations);

        Map<String, Object> unresolved = bridge.execute(request("review_safety_event",
                Map.of("eventId", "alarm-1", "deviceId", "gas-1", "ledger", "gas")));
        assertThat(unresolved).doesNotContainKey("videoPlayback");
        assertThat(listOfMaps(unresolved.get("candidates"))).hasSize(2);
        verify(iot, never()).prepareVideoPlayback(any(), any(), any());

        Map<String, Object> selectionRequest = request("review_safety_event",
                Map.of("eventId", "alarm-1", "deviceId", "gas-1", "ledger", "gas", "cameraDeviceId", "cam-2"));
        selectionRequest.put("toolCallId", "call-2");
        BusinessException notSelectedByUser = catchThrowableOfType(
                () -> bridge.execute(selectionRequest), BusinessException.class);
        assertThat(notSelectedByUser.code()).isEqualTo("CAMERA_SELECTION_REQUIRED");
        verify(iot, never()).prepareVideoPlayback(any(), any(), any());
        when(conversations.toolContext("tool-selected", "conversation-1", "turn-selected", "run-selected"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-selected", "conversation-1", "turn-selected",
                        "company", null, Map.of()));
        selectionRequest.put("toolContextId", "tool-selected");
        selectionRequest.put("runId", "run-selected");
        selectionRequest.put("turnId", "turn-selected");
        Map<String, Object> selected = bridge.execute(selectionRequest);
        assertThat(map(selected.get("videoPlayback"))).containsEntry("deviceId", "cam-2");
        verify(iot).prepareVideoPlayback(any(), any(), any());
    }

    @Test
    void selectedCameraMustBeAnAuthorizedCandidate() {
        ConversationFacade conversations = companyConversations();
        ObservationAdapter observations = mock(ObservationAdapter.class);
        when(observations.read(any(ObservationRequest.class))).thenReturn(Mono.just(ObservationResponse.available(
                cameraPayload(List.of(candidate("cam-1", "大堂监控"))))));
        AgentToolBridge bridge = bridge(conversations, mock(IotAdapter.class), observations);

        BusinessException error = catchThrowableOfType(() -> bridge.execute(request("review_safety_event",
                        Map.of("eventId", "alarm-1", "deviceId", "gas-1", "ledger", "gas",
                                "cameraDeviceId", "cam-other"))), BusinessException.class);
        assertThat(error.code()).isEqualTo("SAFETY_EVENT_CAMERA_UNAVAILABLE");
    }

    @Test
    void noCandidateProducesTruthfulEmptyResultWithoutPlayback() {
        ConversationFacade conversations = companyConversations();
        ObservationAdapter observations = mock(ObservationAdapter.class);
        Map<String, Object> payload = cameraPayload(List.of());
        payload.put("note", "当前Company目录中未找到同地点或同楼层候选监控");
        when(observations.read(any(ObservationRequest.class))).thenReturn(Mono.just(ObservationResponse.available(payload)));
        AgentToolBridge bridge = bridge(conversations, mock(IotAdapter.class), observations);

        Map<String, Object> result = bridge.execute(request("review_safety_event",
                Map.of("eventId", "alarm-1", "deviceId", "gas-1", "ledger", "gas")));

        assertThat(result).doesNotContainKey("videoPlayback");
        assertThat(listOfMaps(result.get("candidates"))).isEmpty();
    }

    private AgentToolBridge bridge(ConversationFacade conversations, IotAdapter iot, ObservationAdapter observations) {
        return AgentToolBridgeFactory.local(conversations, iot, observations, null,
                mock(ActionDraftFacade.class), mock(TaskFacade.class), new ObjectMapper(), null);
    }

    private AgentRunPolicy policy(ConversationFacade conversations, IotAdapter iot, ObservationAdapter observations) {
        return AgentBusinessEngineFactory.policyProvider(() -> conversations, iot, observations, null, null, CLOCK, 8)
                .prepare(new AgentRunRequest("run-1", "conversation-1", "turn-1", "company",
                        "复盘这条告警", "TENANT", null, Map.of(), "tool-1"));
    }

    private static ConversationFacade companyConversations() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, Map.of()));
        return conversations;
    }

    private static Map<String, Object> request(String tool, Map<String, Object> arguments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", "run-1");
        body.put("toolContextId", "tool-1");
        body.put("conversationId", "conversation-1");
        body.put("turnId", "turn-1");
        body.put("toolCallId", "call-1");
        body.put("toolName", tool);
        body.put("arguments", arguments);
        return body;
    }

    private static Map<String, Object> cameraPayload(List<Map<String, Object>> candidates) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("eventId", "alarm-1");
        record.put("deviceId", "gas-1");
        record.put("ledger", "gas");
        record.put("deviceName", "燃气一号");
        record.put("occurredAt", "2026-11-01T10:00:00Z");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "company");
        payload.put("record", record);
        payload.put("currentSafetyDevice", Map.of("name", "燃气一号", "location", "26楼茶水间"));
        payload.put("candidates", candidates);
        payload.put("note", "按当前位置匹配，记录时位置未核验；可选直播或回放");
        return payload;
    }

    private static Map<String, Object> candidate(String deviceId, String name) {
        Map<String, Object> safetyContext = new LinkedHashMap<>();
        safetyContext.put("type", "safety_event");
        safetyContext.put("ledger", "gas");
        safetyContext.put("eventId", "alarm-1");
        safetyContext.put("deviceId", "gas-1");
        safetyContext.put("label", "燃气一号");
        safetyContext.put("occurredAt", "2026-11-01T10:00:00Z");
        safetyContext.put("basis", "按当前安装位置匹配");
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("source", "company");
        candidate.put("deviceId", deviceId);
        candidate.put("name", name);
        candidate.put("location", "26楼");
        candidate.put("beginTime", BEGIN);
        candidate.put("endTime", END);
        candidate.put("safetyContext", safetyContext);
        return candidate;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
