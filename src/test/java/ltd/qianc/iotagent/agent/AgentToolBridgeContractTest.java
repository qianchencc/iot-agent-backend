package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
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
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
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
    void wholeCandidateReadCannotBeMixedWithAnExplicitDeviceSelection() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        bind(conversations, "company", null);
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot,
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);
        BusinessException error = catchThrowableOfType(() -> bridge.execute(request("read_device_states",
                Map.of("candidateSets", List.of(Map.of("candidateSetId", "set-1",
                        "all", true, "deviceIds", List.of("selected-only")))))), BusinessException.class);
        assertThat(error.code()).isEqualTo("INVALID_DEVICE_REFERENCE");
    }

    @Test
    void officeRegionNameSearchReturnsOnlyMatchingAuthorizedRegions() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        bind(conversations, "office", "region-1");
        when(iot.loadOfficeRegions(any())).thenReturn(Mono.just(List.of(
                new OfficeRegion("region-1", "26楼运营办公区"),
                new OfficeRegion("region-2", "杨国英办公室"))));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot,
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        Map<String, Object> result = bridge.execute(request("list_office_regions",
                Map.of("nameContains", "杨国英")));

        assertThat(result.get("regions")).isEqualTo(List.of(new OfficeRegion("region-2", "杨国英办公室")));
    }


    @Test
    void aggregatesRequestedAlertTypesWithoutMaskingMissingTotals() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ObservationAdapter observations = mock(ObservationAdapter.class);
        bind(conversations, "company", null);
        when(observations.read(any(ObservationRequest.class))).thenAnswer(invocation -> {
            ObservationRequest query = invocation.getArgument(0);
            return switch (query.parameters().get("type")) {
                case "smoke" -> Mono.just(ObservationResponse.available(Map.of(
                        "items", List.of(Map.of("eventId", "smoke-1")),
                        "total", 2, "scanned", 5, "excludedCount", 0, "coverageComplete", true)));
                case "gas" -> {
                    Map<String, Object> gas = new LinkedHashMap<>();
                    gas.put("items", List.of());
                    gas.put("total", null);
                    gas.put("scanned", 0);
                    gas.put("excludedCount", 0);
                    gas.put("coverageComplete", true);
                    yield Mono.just(ObservationResponse.available(gas));
                }
                default -> Mono.error(new BusinessException("AGENT_TOOL_UNAVAILABLE", "水浸台账暂不可用", 503));
            };
        });
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class), observations,
                mock(TaskProjectionStore.class), mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        Map<String, Object> result = bridge.execute(request("query_alerts",
                Map.of("types", List.of("smoke", "gas", "water"))));

        assertThat(result).containsEntry("total", 2)
                .containsEntry("summaryStatus", "partial")
                .containsEntry("coverageComplete", false);
        assertThat(result.get("items")).isEqualTo(List.of(Map.of("eventId", "smoke-1")));
        assertThat(result.get("failures").toString()).contains("water").contains("水浸");
        verify(observations, org.mockito.Mockito.times(3)).read(any(ObservationRequest.class));
    }

    @Test
    void currentOfficeRegionIsBoundIntoPersistedCandidateQuery() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        bind(conversations, "office", "region-1");
        AtomicReference<DeviceCandidateQuery> query = new AtomicReference<>();
        AtomicReference<ltd.qianc.iotagent.iot.DeviceCandidateBinding> binding = new AtomicReference<>();
        when(iot.findDeviceCandidates(any(), any(), any())).thenAnswer(invocation -> {
            query.set(invocation.getArgument(1));
            binding.set(invocation.getArgument(2));
            return Mono.just(new DeviceCandidateSet("set-1", query.get(), List.of(), true, 0));
        });
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot,
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        bridge.execute(request("find_device_candidates", Map.of(
                "scopeText", "当前位置", "kind", "lighting", "deviceText", "灯")));

        assertThat(query.get().regionId()).isEqualTo("region-1");
        assertThat(query.get().scopeText()).isNull();
        assertThat(binding.get().conversationId()).isEqualTo("conversation-1");
    }

    @Test
    void companyEnergyToolsUseMeterReportWithBoundSourceAndWindow() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ObservationAdapter observations = mock(ObservationAdapter.class);
        bind(conversations, "company", null);
        AtomicReference<ObservationRequest> request = new AtomicReference<>();
        when(observations.read(any(ObservationRequest.class))).thenAnswer(invocation -> {
            request.set(invocation.getArgument(0));
            return Mono.just(ObservationResponse.available(Map.of("total", Map.of("value", 1))));
        });
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class), observations,
                mock(TaskProjectionStore.class), mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        bridge.execute(request("summarize_energy", Map.of(
                "scope", Map.of("level", "floor", "floor", "26楼"),
                "from", "2026-10-01T00:00:00+08:00", "to", "2026-10-02T00:00:00+08:00")));

        assertThat(request.get().operation()).isEqualTo(ObservationOperation.COMPANY_METER_REPORT);
        assertThat(request.get().identity().source().wireValue()).isEqualTo("company");
        assertThat(request.get().parameters()).containsEntry("scopeLevel", "floor")
                .containsEntry("floor", "26楼")
                .containsEntry("kind", "statistics")
                .containsEntry("from", "2026-10-01T00:00:00+08:00")
                .containsEntry("to", "2026-10-02T00:00:00+08:00");
    }


    @Test
    void readDeviceStatesAcceptsRuntimeCandidateSetShape() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        bind(conversations, "office", null);
        when(iot.readDeviceStates(any(), any(), org.mockito.ArgumentMatchers.eq("conversation-1")))
                .thenReturn(Mono.just(new DeviceStateReadResult(
                        Map.of("device-1", Map.of("power", "on")), "2026-10-30T00:00:00Z")));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot,
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        Map<String, Object> result = bridge.execute(request("read_device_states",
                Map.of("candidateSets", List.of(Map.of("candidateSetId", "set-1", "deviceIds", List.of("device-1"))))));

        assertThat(result).containsEntry("observedAt", "2026-10-30T00:00:00Z");
        assertThat(result.get("states")).isEqualTo(Map.of("device-1", Map.of("power", "on")));
    }


    @Test
    void prepareVideoPlaybackUsesBoundCompanySourceAndNormalizesWindow() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        bind(conversations, "company", null);
        when(iot.prepareVideoPlayback(any(), any(), any())).thenReturn(Mono.just(
                new ltd.qianc.iotagent.iot.VideoPlaybackPreparation("company", "camera-1", "大堂摄像头", "一层大堂",
                        "2026-10-16T10:57:00.000+08:00", "2026-10-16T11:27:00.000+08:00")));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot,
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        Map<String, Object> result = bridge.execute(request("prepare_video_playback", Map.of(
                "deviceId", "camera-1",
                "beginTime", "2026-10-16T02:57:00Z", "endTime", "2026-10-16T03:27:00Z")));

        assertThat(result).containsKey("videoPlayback");
        assertThat(result.get("videoPlayback").toString()).contains("2026-10-16T10:57:00.000+08:00");
    }

    @Test
    void rejectsLegacyExecutionAliasesAndMismatchedBoundContext() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        bind(conversations, "office", "region-1");
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, mock(IotAdapter.class),
                mock(ActionDraftFacade.class), mock(TaskFacade.class), mapper, null);

        BusinessException alias = catchThrowableOfType(() -> bridge.execute(request("confirm_action",
                Map.of("draftId", "draft-1"))), BusinessException.class);
        assertThat(alias.code()).isEqualTo("AGENT_TOOL_UNAVAILABLE");

        BusinessException tamperedSource = catchThrowableOfType(() -> bridge.execute(request("read_device_states",
                Map.of("source", "company", "candidateSets", List.of(Map.of("candidateSetId", "s", "deviceIds", List.of("d")))))),
                BusinessException.class);
        assertThat(tamperedSource.code()).isEqualTo("AGENT_TOOL_CONTEXT_MISMATCH");

        BusinessException tamperedFocus = catchThrowableOfType(() -> bridge.execute(request("find_device_candidates",
                Map.of("focusRegionId", "region-2", "kind", "lighting"))), BusinessException.class);
        assertThat(tamperedFocus.code()).isEqualTo("AGENT_TOOL_CONTEXT_MISMATCH");

        BusinessException unknownArgument = catchThrowableOfType(() -> bridge.execute(request("read_action_draft",
                Map.of("draftId", "draft-1", "tenantCode", "other"))), BusinessException.class);
        assertThat(unknownArgument.code()).isEqualTo("AGENT_TOOL_CONTEXT_MISMATCH");
    }

    private void bind(ConversationFacade conversations, String source, String focusRegionId) {
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(principal, "run-1", "conversation-1", "turn-1",
                        source, focusRegionId, Map.of()));
    }

    private static Map<String, Object> request(String toolName, Map<String, Object> arguments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", "run-1");
        body.put("toolContextId", "tool-1");
        body.put("conversationId", "conversation-1");
        body.put("turnId", "turn-1");
        body.put("toolCallId", "call-1");
        body.put("toolName", toolName);
        body.put("arguments", arguments);
        return body;
    }
}
