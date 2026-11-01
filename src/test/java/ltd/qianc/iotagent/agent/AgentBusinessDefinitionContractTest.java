package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentEngineEvent;
import ltd.qianc.iotagent.conversation.AgentRunPolicy;
import ltd.qianc.iotagent.conversation.AgentRunPolicyProvider;
import ltd.qianc.iotagent.conversation.AgentRunRequest;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportService;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskFacade;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentBusinessDefinitionContractTest {
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final Set<String> FORBIDDEN_TOOLS = Set.of(
            "confirm_action", "resolve_action", "confirm_task", "confirm_task_change", "confirm_task_cancellation",
            "resolve_task", "resolve_task_change", "resolve_task_cancellation", "propose_lighting_draft",
            "read_device_readback", "readback_device_states", "create_report", "generate_report");

    @Test
    void buildsVersionedDataOnlyRequestWithVerifiedOfficeContextAndNoExecutionTools() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        Map<String, Object> reference = Map.of("type", "task", "taskId", "task-1", "label", "任务一");
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", "region-1", reference));
        when(iot.loadOfficeRegions(any())).thenReturn(Mono.just(
                List.of(new OfficeRegion("region-1", "26楼运营办公区"))));
        TaskFacade tasks = mock(TaskFacade.class);
        TaskFact task = new TaskFact("task-1", 3, "SCHEDULED",
                "{\"label\":\"真实任务一\",\"source\":\"office\"}");
        when(tasks.current(PRINCIPAL, "task-1", "office")).thenReturn(task);
        when(tasks.view(PRINCIPAL, task, "office")).thenReturn(new LinkedHashMap<>(Map.of(
                "taskId", "task-1", "label", "真实任务一", "status", "SCHEDULED")));
        AgentRunPolicyProvider provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, iot, null, tasks, null, CLOCK, 8);

        AgentRunPolicy policy = provider.prepare(request("office", "region-1", reference));
        Map<String, Object> body = policy.request();

        assertThat(body).containsEntry("protocolVersion", 1)
                .containsEntry("runId", "run-1")
                .containsEntry("conversationId", "conversation-1")
                .containsEntry("turnId", "turn-1")
                .containsEntry("toolContextId", "tool-1")
                .containsEntry("content", "打开当前位置的灯");
        assertThat(body).doesNotContainKeys("source", "tenantCode", "focusRegionId", "reference");
        assertThat(String.valueOf(body.get("sessionScope"))).startsWith("v1:").doesNotContain("TENANT");

        Map<String, Object> definition = map(body.get("definition"));
        assertThat(definition).containsOnlyKeys("instructions", "tools", "maxTurns");
        assertThat(definition).containsEntry("maxTurns", 8);
        String instructions = String.valueOf(definition.get("instructions"));
        assertThat(instructions).contains("2026-11-02T10:00:00+08:00")
                .contains("26楼运营办公区")
                .contains("current_time")
                .contains("taskId=task-1")
                .contains("<reference_facts>")
                .contains("真实任务一");

        List<Map<String, Object>> tools = listOfMaps(definition.get("tools"));
        Set<String> names = tools.stream().map(tool -> String.valueOf(tool.get("name"))).collect(Collectors.toSet());
        assertThat(names).contains("find_device_candidates", "read_device_states", "propose_action_draft",
                "read_action_draft", "propose_task", "get_task");
        assertThat(names).doesNotContain("query_alerts", "create_energy_report").doesNotContainAnyElementsOf(FORBIDDEN_TOOLS);
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool).containsKeys("name", "label", "description", "parameters");
            assertThat(map(tool.get("parameters"))).containsEntry("type", "object");
        });

        assertThat(policy.projection().apply(event("run.started", Map.of()))).singleElement()
                .satisfies(projected -> {
                    assertThat(map(projected.fields().get("focusRegion")))
                            .containsEntry("id", "region-1")
                            .containsEntry("name", "26楼运营办公区");
                    assertThat(map(projected.fields().get("reference")))
                            .containsEntry("taskId", "task-1")
                            .containsEntry("label", "真实任务一")
                            .containsEntry("verified", true);
                });
    }

    @Test
    void toolSchemasKeepNestedPropertiesAsSchemasRatherThanSchemaMetadata() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        AgentRunPolicyProvider provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, null, null, null, CLOCK, 8);
        for (String source : List.of("office", "company")) {
            when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                    .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                            source, null, null));
            Map<String, Object> definition = map(provider.prepare(request(source, null, null))
                    .request().get("definition"));
            for (Map<String, Object> tool : listOfMaps(definition.get("tools"))) {
                assertToolSchema(tool.get("parameters"), source + ":" + tool.get("name"));
            }
        }
    }

    @Test
    void resolvesSafetyEventAndOriginalReviewWindowFromServerFact() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ObservationAdapter observations = mock(ObservationAdapter.class);
        Map<String, Object> reference = Map.of("type", "safety_event", "eventId", "event-1",
                "deviceId", "gas-1", "ledger", "gas", "label", "客户端伪造标题");
        when(conversations.toolContext("tool-2", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, reference));
        AtomicReference<ObservationRequest> seen = new AtomicReference<>();
        when(observations.read(any(ObservationRequest.class))).thenAnswer(invocation -> {
            seen.set(invocation.getArgument(0));
            return Mono.just(ObservationResponse.available(Map.of("record", Map.of(
                    "eventId", "event-1", "deviceId", "gas-1", "deviceName", "燃气一号",
                    "recordedDeviceName", "燃气一号", "occurredAt", "2026-11-01T10:00:00Z",
                    "report", false))));
        });
        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, observations, null, null, CLOCK, 8)
                .prepare(requestFor("tool-2", "company", null, reference));

        assertThat(seen.get().operation()).isEqualTo(ObservationOperation.SAFETY_EVENT);
        assertThat(seen.get().parameters()).containsEntry("id", "event-1")
                .containsEntry("deviceId", "gas-1").containsEntry("ledger", "gas");
        Map<String, Object> referenceView = map(policy.projection()
                .apply(event("run.started", Map.of())).getFirst().fields().get("reference"));
        assertThat(referenceView).containsEntry("label", "燃气一号 · 2026-11-01T10:00:00Z")
                .containsEntry("verified", true);
        Map<String, Object> window = map(referenceView.get("window"));
        assertThat(window).containsEntry("from", "2026-11-01T09:45:00Z")
                .containsEntry("to", "2026-11-01T10:15:00Z");
        String instructions = String.valueOf(map(policy.request().get("definition")).get("instructions"));
        assertThat(instructions).contains("<reference_facts>", "safetyEvent", "reviewWindow")
                .doesNotContain("客户端伪造标题");
    }

    @Test
    void resolvesEnvironmentWindowWithoutReplacingOriginalRange() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ObservationAdapter observations = mock(ObservationAdapter.class);
        String from = "2026-11-01T00:00:00+08:00";
        String to = "2026-11-02T00:00:00+08:00";
        Map<String, Object> reference = Map.of("type", "environment_window", "from", from, "to", to);
        when(conversations.toolContext("tool-3", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, reference));
        AtomicReference<ObservationRequest> seen = new AtomicReference<>();
        when(observations.read(any(ObservationRequest.class))).thenAnswer(invocation -> {
            seen.set(invocation.getArgument(0));
            return Mono.just(ObservationResponse.available(Map.of(
                    "window", Map.of("from", from, "to", to), "samples", List.of())));
        });
        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, observations, null, null, CLOCK, 8)
                .prepare(requestFor("tool-3", "company", null, reference));

        assertThat(seen.get().operation()).isEqualTo(ObservationOperation.ENVIRONMENT_HISTORY);
        assertThat(seen.get().parameters()).containsEntry("from", from).containsEntry("to", to);
        Map<String, Object> referenceView = map(policy.projection()
                .apply(event("run.started", Map.of())).getFirst().fields().get("reference"));
        assertThat(referenceView).containsEntry("from", from).containsEntry("to", to)
                .containsEntry("verified", true);
        String instructions = String.valueOf(map(policy.request().get("definition")).get("instructions"));
        assertThat(instructions).contains("environmentWindow").contains(from).contains(to);
    }

    @Test
    void resolvesReportOwnershipAndIgnoresClientTitle() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ReportService reports = mock(ReportService.class);
        Map<String, Object> reference = Map.of("type", "report", "reportId", "report-1",
                "label", "客户端伪造报告", "title", "客户端伪造标题", "blocks", List.of());
        when(conversations.toolContext("tool-4", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, reference));
        AtomicReference<RequestIdentity> identity = new AtomicReference<>();
        when(reports.read(org.mockito.ArgumentMatchers.eq("report-1"), any(RequestIdentity.class)))
                .thenAnswer(invocation -> {
                    identity.set(invocation.getArgument(1));
                    return new LinkedHashMap<>(Map.of("id", "report-1", "title", "真实报告",
                            "status", "completed", "generatedAt", "2026-11-02T10:00:00+08:00",
                            "period", Map.of("from", "2026-10-01T00:00:00+08:00",
                                    "to", "2026-11-01T00:00:00+08:00")));
                });
        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, null, null, reports, CLOCK, 8)
                .prepare(requestFor("tool-4", "company", null, reference));

        assertThat(identity.get().source().wireValue()).isEqualTo("company");
        assertThat(identity.get().tenantCode()).isEqualTo("TENANT");
        Map<String, Object> referenceView = map(policy.projection()
                .apply(event("run.started", Map.of())).getFirst().fields().get("reference"));
        assertThat(referenceView).containsEntry("reportId", "report-1")
                .containsEntry("label", "真实报告").containsEntry("verified", true);
        String instructions = String.valueOf(map(policy.request().get("definition")).get("instructions"));
        assertThat(instructions).contains("真实报告").doesNotContain("客户端伪造标题");
    }

    @Test
    void companyDefinitionFiltersOfficeOnlySurfaceAndKeepsCanonicalReadTools() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, Map.of()));
        AgentRunPolicyProvider provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, CLOCK, 6);

        AgentRunPolicy policy = provider.prepare(request("company", null, Map.of()));
        String instructions = String.valueOf(map(policy.request().get("definition")).get("instructions"));
        assertThat(instructions).contains("公司设施助手").doesNotContain("办公室物联助手");

        List<Map<String, Object>> tools = listOfMaps(map(policy.request().get("definition")).get("tools"));
        Set<String> names = tools.stream()
                .map(tool -> String.valueOf(tool.get("name"))).collect(Collectors.toSet());
        assertThat(names).contains("query_alerts", "query_environment_history", "query_energy_history",
                "summarize_energy", "compare_energy", "create_energy_report", "review_safety_event");
        assertThat(names).doesNotContainAnyElementsOf(FORBIDDEN_TOOLS);

        Map<String, Object> report = tools.stream()
                .filter(tool -> "create_energy_report".equals(tool.get("name"))).findFirst().orElseThrow();
        Map<String, Object> reportProperties = map(map(report.get("parameters")).get("properties"));
        assertThat(reportProperties).containsKeys("kind", "title", "period", "comparisons", "scope", "grain")
                .doesNotContainKeys("blocks", "totals", "status", "meterIds");
    }

    @Test
    void usesOneJavaAdminSnapshotForModelBudgetAndInstructions() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        AdminConfigurationStore admin = mock(AdminConfigurationStore.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", null, Map.of()));
        when(admin.active()).thenReturn(settings());

        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, () -> admin, CLOCK, 8)
                .prepare(request("office", null, Map.of()));
        Map<String, Object> definition = map(policy.request().get("definition"));

        assertThat(definition).containsEntry("maxTurns", 6)
                .containsEntry("maxToolCalls", 12)
                .containsEntry("runTimeoutMs", 120000);
        Map<String, Object> model = map(definition.get("model"));
        assertThat(model).hasSize(11)
                .containsEntry("modelId", "configured-model")
                .containsEntry("modelBaseUrl", "https://model.example/v1")
                .containsEntry("modelApiKey", "private-key")
                .containsEntry("contextWindow", 128000L)
                .containsEntry("maxOutputTokens", 16384L)
                .containsEntry("thinkingLevel", "low")
                .containsEntry("maxTokensField", "max_completion_tokens");
        String instructions = String.valueOf(definition.get("instructions"));
        assertThat(instructions).contains("office configured guidance", "reply configured guidance")
                .doesNotContain("private-key");
    }

    @Test
    void projectsToolResultsToRuntimeBusinessEventsAndAwaitingConfirmation() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", null, Map.of()));
        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, CLOCK, 8).prepare(request("office", null, Map.of()));

        List<AgentEngineEvent> started = policy.projection().apply(event("run.started", Map.of()));
        assertThat(started).singleElement().satisfies(projected -> {
            assertThat(projected.type()).isEqualTo("run.started");
            assertThat(projected.fields()).containsKey("createdAt");
        });

        policy.projection().apply(event("tool.started", Map.of(
                "tool", "propose_action_draft", "toolCallId", "call-1",
                "arguments", Map.of("summary", "打开 1 盏灯"))));
        List<AgentEngineEvent> proposed = policy.projection().apply(event("tool.result", Map.of(
                "tool", "propose_action_draft", "toolCallId", "call-1",
                "result", Map.of("draftId", "draft-1", "status", "PENDING_CONFIRMATION", "revision", 1))));

        // ControlDomain persists and emits the full action.proposed fact; projection must only remember pending state.
        assertThat(proposed).isEmpty();

        List<AgentEngineEvent> completed = policy.projection().apply(event("run.completed",
                Map.of("outcome", "message")));
        assertThat(completed).singleElement().satisfies(projected -> {
            assertThat(projected.type()).isEqualTo("run.awaiting_confirmation");
            assertThat(projected.fields()).containsEntry("draftId", "draft-1");
        });
    }

    @Test
    void projectsReportVideoAndObservationBlocksWithoutForwardingInternalResults() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, Map.of()));
        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, CLOCK, 8).prepare(request("company", null, Map.of()));

        List<AgentEngineEvent> report = policy.projection().apply(event("tool.result", Map.of(
                "tool", "create_energy_report", "toolCallId", "call-report",
                "result", Map.of("reportId", "report-1", "reused", false,
                        "report", Map.of("id", "report-1", "title", "用电日报",
                                "generatedAt", "2026-11-02T10:00:00+08:00",
                                "blocks", List.of(Map.of("type", "meter_report")))))));
        assertThat(report).singleElement().satisfies(projected -> {
            assertThat(projected.type()).isEqualTo("observation.reported");
            List<Map<String, Object>> blocks = listOfMaps(projected.fields().get("blocks"));
            assertThat(blocks).singleElement().satisfies(block -> {
                assertThat(block).containsEntry("type", "report_artifact");
                assertThat(map(block.get("report"))).containsEntry("id", "report-1");
            });
        });

        List<AgentEngineEvent> live = policy.projection().apply(event("tool.result", Map.of(
                "tool", "find_device_candidates", "toolCallId", "call-find",
                "result", Map.of("devices", List.of(Map.of("id", "camera-1", "kind", "video",
                        "name", "大堂摄像头", "location", "一层大堂"))))));
        assertThat(live).singleElement().satisfies(projected -> {
            List<Map<String, Object>> blocks = listOfMaps(projected.fields().get("blocks"));
            assertThat(blocks).singleElement().satisfies(block -> {
                assertThat(block).containsEntry("type", "video");
                List<Map<String, Object>> devices = listOfMaps(block.get("devices"));
                assertThat(devices).singleElement().satisfies(device ->
                        assertThat(device).containsEntry("deviceId", "camera-1")
                                .containsEntry("name", "大堂摄像头"));
            });
        });

        List<AgentEngineEvent> video = policy.projection().apply(event("tool.result", Map.of(
                "tool", "prepare_video_playback", "toolCallId", "call-video",
                "result", Map.of("videoPlayback", Map.of("deviceId", "camera-1", "name", "大堂")))));
        assertThat(video).singleElement().satisfies(projected -> {
            List<Map<String, Object>> blocks = listOfMaps(projected.fields().get("blocks"));
            assertThat(blocks).singleElement().satisfies(block ->
                    assertThat(block).containsEntry("type", "video_playback"));
        });

        List<AgentEngineEvent> forwarded = policy.projection().apply(event("tool.result", Map.of(
                "tool", "unknown_tool", "toolCallId", "call-x", "result", Map.of("value", 1))));
        assertThat(forwarded).isEmpty();
    }

    @Test
    void rejectsTamperedRunContextAndCrossSourceReferences() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", "region-1", Map.of()));
        AgentRunPolicyProvider provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, CLOCK, 8);

        BusinessException focusMismatch = catchThrowableOfType(
                () -> provider.prepare(request("office", null, Map.of())), BusinessException.class);
        assertThat(focusMismatch.code()).isEqualTo("AGENT_RUN_CONTEXT_MISMATCH");

        BusinessException sourceMismatch = catchThrowableOfType(
                () -> provider.prepare(request("company", "region-1", Map.of())), BusinessException.class);
        assertThat(sourceMismatch.code()).isEqualTo("AGENT_RUN_CONTEXT_MISMATCH");

        when(conversations.toolContext("tool-2", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", null, Map.of("type", "safety_event", "eventId", "event-1",
                        "deviceId", "gas-1", "ledger", "gas")));
        BusinessException crossSource = catchThrowableOfType(
                () -> provider.prepare(new AgentRunRequest("run-1", "conversation-1", "turn-1", "office",
                        "解释这个告警", "TENANT", null,
                        Map.of("type", "safety_event", "eventId", "event-1", "deviceId", "gas-1", "ledger", "gas"),
                        "tool-2")), BusinessException.class);
        assertThat(crossSource.code()).isEqualTo("INVALID_REFERENCE");
    }

    private static AgentRunRequest request(String source, String focusRegionId, Map<String, Object> reference) {
        return requestFor("tool-1", source, focusRegionId, reference);
    }

    private static AgentRunRequest requestFor(String toolContextId, String source, String focusRegionId,
            Map<String, Object> reference) {
        return new AgentRunRequest("run-1", "conversation-1", "turn-1", source,
                "打开当前位置的灯", "TENANT", focusRegionId, reference, toolContextId);
    }

    private static Map<String, Object> settings() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("modelId", "configured-model");
        settings.put("modelBaseUrl", "https://model.example/v1");
        settings.put("modelApiKey", "private-key");
        settings.put("contextWindow", 128000);
        settings.put("maxOutputTokens", 16384);
        settings.put("reasoning", true);
        settings.put("thinkingLevel", "low");
        settings.put("supportsDeveloperRole", false);
        settings.put("supportsReasoningEffort", true);
        settings.put("supportsUsageInStreaming", true);
        settings.put("maxTokensField", "max_completion_tokens");
        settings.put("maxAgentTurns", 6);
        settings.put("maxToolCalls", 12);
        settings.put("runTimeoutMs", 120000);
        settings.put("officeInstructions", "office configured guidance");
        settings.put("companyInstructions", "company configured guidance");
        settings.put("replyInstructions", "reply configured guidance");
        return settings;
    }

    private static AgentEngineEvent event(String type, Map<String, Object> fields) {
        return new AgentEngineEvent("run-1", "conversation-1", "turn-1", type, fields);
    }

    private static void assertToolSchema(Object value, String path) {
        assertThat(value).as(path).isInstanceOf(Map.class);
        Map<String, Object> schema = map(value);
        if (schema.containsKey("properties")) {
            assertThat(schema.get("properties")).as(path + ".properties").isInstanceOf(Map.class);
            map(schema.get("properties")).forEach((name, property) -> assertToolSchema(property, path + "." + name));
        }
        if (schema.containsKey("items")) assertToolSchema(schema.get("items"), path + "[]");
        for (String alternatives : List.of("oneOf", "anyOf", "allOf")) {
            if (schema.get(alternatives) instanceof List<?> options) {
                for (Object option : options) assertToolSchema(option, path + "." + alternatives);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
