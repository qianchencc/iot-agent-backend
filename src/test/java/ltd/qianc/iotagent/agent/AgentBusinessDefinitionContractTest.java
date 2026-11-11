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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentEngineEvent;
import ltd.qianc.iotagent.conversation.AgentRunPolicy;
import ltd.qianc.iotagent.conversation.AgentRunPolicyProvider;
import ltd.qianc.iotagent.conversation.AgentRunRequest;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperationEnum;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportService;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import reactor.core.publisher.Mono;

class AgentBusinessDefinitionContractTest {
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "TENANT", "owner-1", "fixture");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

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
        when(iot.loadAuthorizedDeviceScope(any())).thenReturn(Mono.just(AuthorizedDeviceScope.of(
                new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(), IotSourceEnum.OFFICE,
                        PRINCIPAL.ownerKey()),
                IotSourceEnum.OFFICE, List.of(new AuthorizedDevice("light-1", "lighting", "吸顶灯", "", true)))));
        TaskFacade tasks = mock(TaskFacade.class);
        when(tasks.taskView(PRINCIPAL, "task-1", "office")).thenReturn(new LinkedHashMap<>(Map.of(
                "id", "task-1", "label", "真实任务一", "status", "scheduled")));
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
        assertThat(definition).containsEntry("maxTurns", 8);


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
    void currentCatalogContextCountsEveryAuthorizedMemberAndDeduplicatesLocationPaths() throws Exception {
        var conversations = mock(ConversationFacade.class);
        var iot = mock(IotAdapter.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, Map.of()));
        var devices = new java.util.ArrayList<AuthorizedDevice>();
        for (int n = 1; n <= 156; n++) {
            String floor = n <= 81 ? "26楼" : "27楼";
            devices.add(new AuthorizedDevice("light-" + n, "lighting", "照明开关 - 走廊-筒灯", floor, false,
                    null, null, null, null, List.of(List.of(floor, "办公室"), List.of(floor, "公共区")),
                    List.of(), Map.of()));
        }
        var identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSourceEnum.COMPANY, PRINCIPAL.ownerKey());
        when(iot.loadAuthorizedDeviceScope(any())).thenReturn(Mono.just(
                AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY, devices)));
        var provider = AgentBusinessEngineFactory.policyProvider(() -> conversations, iot, CLOCK, 8);
        var definition = map(provider.prepare(request("company", null, Map.of())).request().get("definition"));
        String context = (String) definition.get("context");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var catalog = json.readTree(context.split("<device_context>", 2)[1].split("</device_context>", 2)[0]);
        assertThat(catalog.path("complete").asBoolean()).isTrue();
        var lighting = catalog.path("deviceKinds").get(0);
        assertThat(lighting.path("kind").asText()).isEqualTo("lighting");
        assertThat(lighting.path("count").asInt()).isEqualTo(156);
        assertThat(lighting.path("locationCounts").get(0).path("label").asText()).isEqualTo("26楼");
        assertThat(lighting.path("locationCounts").get(0).path("count").asInt()).isEqualTo(81);
        assertThat(lighting.path("locationCounts").get(1).path("count").asInt()).isEqualTo(75);
        assertThat(lighting.path("baseNames").get(0).asText()).isEqualTo("筒灯");
        assertThat(catalog.toString()).doesNotContain("light-1");
        assertThat(String.valueOf(definition.get("instructions"))).doesNotContain("<device_context>");

        when(iot.loadAuthorizedDeviceScope(any())).thenReturn(Mono.just(
                AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY, devices.subList(0, 81))));
        String refreshed = (String) map(provider.prepare(request("company", null, Map.of()))
                .request().get("definition")).get("context");
        var current = json.readTree(refreshed.split("<device_context>", 2)[1].split("</device_context>", 2)[0]);
        assertThat(current.path("deviceKinds").get(0).path("count").asInt()).isEqualTo(81);
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

        assertThat(seen.get().operation()).isEqualTo(ObservationOperationEnum.SAFETY_EVENT);
        assertThat(seen.get().parameters()).containsEntry("id", "event-1")
                .containsEntry("deviceId", "gas-1").containsEntry("ledger", "gas");
        Map<String, Object> referenceView = map(policy.projection()
                .apply(event("run.started", Map.of())).getFirst().fields().get("reference"));
        assertThat(referenceView).containsEntry("label", "燃气一号 · 2026-11-01T10:00:00Z")
                .containsEntry("verified", true);
        Map<String, Object> window = map(referenceView.get("window"));
        assertThat(window).containsEntry("from", "2026-11-01T09:45:00Z")
                .containsEntry("to", "2026-11-01T10:15:00Z");
        assertThat(policy.request().toString()).doesNotContain("客户端伪造标题");
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

        assertThat(seen.get().operation()).isEqualTo(ObservationOperationEnum.ENVIRONMENT_HISTORY);
        assertThat(seen.get().parameters()).containsEntry("from", from).containsEntry("to", to);
        Map<String, Object> referenceView = map(policy.projection()
                .apply(event("run.started", Map.of())).getFirst().fields().get("reference"));
        assertThat(referenceView).containsEntry("from", from).containsEntry("to", to)
                .containsEntry("verified", true);
    }

    @ParameterizedTest
    @ValueSource(booleans={true,false})
    void videoDiscoveryStaysInternalUntilTheRequestedRangeIsRead(boolean knownStates) {
        try(var fixture=new ltd.qianc.iotagent.iot.EnvironmentHistoryFixture()) {
            new ltd.qianc.iotagent.schema.SchemaMigrations(fixture.jdbc.getDataSource()).ensureDeviceCandidateSets();
            fixture.jdbc.execute("ALTER TABLE device_candidate_sets ALTER COLUMN payload_json VARCHAR(1000000) NOT NULL");
            fixture.devices=List.of(
                    new AuthorizedDevice("fixture-video-online","video","测试摄像头甲","测试区域",true),
                    new AuthorizedDevice("fixture-video-offline","video","测试摄像头乙","测试区域",true),
                    new AuthorizedDevice("fixture-video-unknown","video","测试摄像头丙","测试区域",true));
            String sampledAt=fixture.clock.instant().minusSeconds(10).toString();
            fixture.currentResult=new ltd.qianc.iotagent.iot.DeviceStateReadResult(
                    knownStates?Map.of("fixture-video-online",true,"fixture-video-offline",false):Map.of(),
                    null,List.of(),knownStates?Map.of("fixture-video-offline",sampledAt):Map.of());
            var identity=fixture.identity("Bearer fixture-environment","TENANT");
            fixture.register(identity);
            fixture.directory.synchronizeDue();
            fixture.unavailable=true;
            var viewer=new PrincipalInfo(identity.authorization(),identity.tenantCode(),"fixture-principal","fixture-realm");
            ConversationFacade conversations=mock(ConversationFacade.class);
            when(conversations.toolContext("tool-video","conversation-1","turn-1","run-1"))
                    .thenReturn(new AgentToolContext(viewer,"run-1","conversation-1","turn-1","company",null,Map.of()));
            AgentRunPolicy policy=AgentBusinessEngineFactory.policyProvider(() -> conversations,fixture.ordinary,
                    fixture.composed(),null,null,fixture.clock,8)
                    .prepare(requestFor("tool-video","company",null,Map.of()));
            AgentToolBridge bridge=AgentToolBridgeFactory.local(conversations,fixture.ordinary,fixture.composed(),
                    mock(TaskProjectionStore.class),mock(ActionDraftFacade.class),mock(TaskFacade.class),new ObjectMapper(),null);
            Map<String,Object> result=bridge.execute(Map.of("runId","run-1","toolContextId","tool-video",
                    "conversationId","conversation-1","turnId","turn-1","toolCallId","call-video",
                    "toolName","find_device_candidates","arguments",Map.of("kind","video","scopeText","测试区域")));
            var events=policy.projection().apply(event("tool.result",Map.of(
                    "tool","find_device_candidates","toolCallId","call-video","result",result)));
            assertThat(events).isEmpty();
            Map<String,Object> selected=bridge.execute(Map.of("runId","run-1","toolContextId","tool-video",
                    "conversationId","conversation-1","turnId","turn-1","toolCallId","call-read-video",
                    "toolName","read_device_states","arguments",Map.of("candidateSets",List.of(
                            Map.of("candidateSetId",result.get("candidateSetId"),
                                    "deviceIds",fixture.devices.stream().map(AuthorizedDevice::id).toList())))));
            events=policy.projection().apply(event("tool.result",Map.of(
                    "tool","read_device_states","toolCallId","call-read-video","result",selected)));
            assertThat(events).singleElement().satisfies(observation -> {
                assertThat(observation.type()).isEqualTo("observation.reported");
                assertThat(observation.fields()).containsEntry("observedAt",null);
                var rows=listOfMaps(listOfMaps(observation.fields().get("blocks")).getFirst().get("devices"));
                var onlineById=new LinkedHashMap<String,Object>();
                rows.forEach(row -> onlineById.put(String.valueOf(row.get("deviceId")),row.get("online")));
                assertThat(onlineById).containsEntry("fixture-video-online",knownStates?true:null)
                        .containsEntry("fixture-video-offline",knownStates?false:null)
                        .containsEntry("fixture-video-unknown",null);
                if(knownStates) assertThat(map(observation.fields().get("observedAtByDevice")))
                        .containsEntry("fixture-video-offline",sampledAt).doesNotContainKey("fixture-video-online");
            });
        }
    }

    @Test
    void environmentToolAndReferenceReadTheWholeWindowWhileRawHistoryStaysPaged() {
        try(var fixture=new ltd.qianc.iotagent.iot.EnvironmentHistoryFixture()) {
            String from="2020-01-01T00:00:00Z",to="2020-01-08T00:00:00Z";
            var rows=new java.util.ArrayList<Map<String,Object>>();
            for(int index=0;index<165;index++) rows.add(ltd.qianc.iotagent.iot.EnvironmentHistoryFixture.row(
                    "fixture-hour-"+index,"fixture-env-code",Instant.parse(from).plusSeconds(index*3600L).toString(),
                    index==0?"35":"25"));
            fixture.rows=rows;
            var identity=fixture.identity("Bearer fixture-environment","TENANT");
            fixture.register(identity);
            fixture.directory.synchronizeDue();
            fixture.directory.synchronizeHistoryDue();
            fixture.unavailable=true;
            int sourceCalls=fixture.sourceCalls.get();
            var viewer=new PrincipalInfo(identity.authorization(),identity.tenantCode(),"fixture-principal","fixture-realm");
            ConversationFacade conversations=mock(ConversationFacade.class);
            Map<String,Object> reference=Map.of("type","environment_window","from",from,"to",to);
            when(conversations.toolContext("tool-full-env","conversation-1","turn-1","run-1"))
                    .thenReturn(new AgentToolContext(viewer,"run-1","conversation-1","turn-1","company",null,reference));
            AtomicReference<ObservationResponse> referenceRead=new AtomicReference<>();
            ObservationAdapter referenceObservations=query -> fixture.composed().read(query).doOnNext(referenceRead::set);
            AgentRunPolicy policy=AgentBusinessEngineFactory.policyProvider(() -> conversations,fixture.ordinary,
                    referenceObservations,null,null,fixture.clock,8)
                    .prepare(requestFor("tool-full-env","company",null,reference));
            AgentToolBridge bridge=AgentToolBridgeFactory.local(conversations,fixture.ordinary,fixture.composed(),
                    mock(TaskProjectionStore.class),mock(ActionDraftFacade.class),mock(TaskFacade.class),new ObjectMapper(),null);
            Map<String,Object> result=bridge.execute(Map.of("runId","run-1","toolContextId","tool-full-env",
                    "conversationId","conversation-1","turnId","turn-1","toolCallId","call-full-env",
                    "toolName","query_environment_history","arguments",Map.of("from",from,"to",to)));
            assertThat(listOfMaps(result.get("samples"))).hasSize(165);
            assertThat(result.get("nextPage")).isNull();
            assertThat(map(result.get("coverage"))).containsEntry("complete",true).containsEntry("from",from);
            assertThat(map(listOfMaps(result.get("metrics")).getFirst().get("peak")))
                    .containsEntry("at",from);
            assertThat(new java.math.BigDecimal(String.valueOf(map(listOfMaps(result.get("metrics")).getFirst()
                    .get("peak")).get("value")))).isEqualByComparingTo("35");
            assertThat(map(result.get("window"))).containsEntry("from",from).containsEntry("to",to);
            assertThat(listOfMaps(referenceRead.get().payload().get("samples"))).hasSize(165);
            assertThat(referenceRead.get().payload().get("metrics")).isEqualTo(result.get("metrics"));
            var projected=policy.projection().apply(event("tool.result",Map.of("tool","query_environment_history",
                    "toolCallId","call-full-env","result",result)));
            Map<String,Object> card=map(listOfMaps(projected.getFirst().fields().get("blocks")).getFirst().get("report"));
            assertThat(listOfMaps(card.get("samples"))).hasSize(165);
            assertThat(card.get("metrics")).isEqualTo(result.get("metrics"));
            var first=fixture.composed().read(fixture.request(identity,from,to,0)).block();
            var last=fixture.composed().read(fixture.request(identity,from,to,1)).block();
            assertThat(listOfMaps(first.payload().get("samples"))).hasSize(100);
            assertThat(first.payload().get("nextPage")).isEqualTo(1);
            assertThat(listOfMaps(last.payload().get("samples"))).hasSize(65);
            assertThat(listOfMaps(last.payload().get("samples")).getFirst()).containsEntry("id","fixture-hour-0");
            assertThat(last.payload().get("nextPage")).isNull();
            assertThat(fixture.sourceCalls.get()).isEqualTo(sourceCalls);
        }
    }

    @Test
    void localEnvironmentGapsReachToolsAndUnknownTimeCannotBecomeCurrentTime() {
        try(var fixture=new ltd.qianc.iotagent.iot.EnvironmentHistoryFixture()) {
            var rows=new java.util.ArrayList<Map<String,Object>>();
            for(int index=0;index<=100;index++) rows.add(ltd.qianc.iotagent.iot.EnvironmentHistoryFixture.row(
                    "fixture-undated-"+index,"fixture-env-code","invalid-time","25"));
            fixture.rows=rows;
            var identity=fixture.identity("Bearer fixture-environment","TENANT");
            fixture.register(identity);
            fixture.directory.synchronizeDue();
            fixture.directory.synchronizeHistoryDue();
            fixture.unavailable=true;
            int calls=fixture.sourceCalls.get();
            var viewer=new PrincipalInfo(identity.authorization(),identity.tenantCode(),"fixture-principal","fixture-realm");
            ConversationFacade conversations=mock(ConversationFacade.class);
            when(conversations.toolContext("tool-local-env","conversation-1","turn-1","run-1"))
                    .thenReturn(new AgentToolContext(viewer,"run-1","conversation-1","turn-1","company",null,Map.of()));
            AgentRunPolicy policy=AgentBusinessEngineFactory.policyProvider(() -> conversations,fixture.ordinary,
                    fixture.composed(),null,null,fixture.clock,8)
                    .prepare(requestFor("tool-local-env","company",null,Map.of()));
            AgentToolBridge bridge=AgentToolBridgeFactory.local(conversations,fixture.ordinary,fixture.composed(),
                    mock(TaskProjectionStore.class),mock(ActionDraftFacade.class),mock(TaskFacade.class),new ObjectMapper(),null);
            Map<String,Object> result=bridge.execute(Map.of("runId","run-1","toolContextId","tool-local-env",
                    "conversationId","conversation-1","turnId","turn-1","toolCallId","call-local-env",
                    "toolName","query_environment_history","arguments",Map.of("from","2020-01-01T00:00:00Z","to","2020-01-02T00:00:00Z")));
            // The generic engine uses result when no separate modelResult is supplied.
            assertThat(result).containsEntry("status","partial").containsEntry("coverageComplete",false).containsEntry("nextPage",null);
            assertThat(map(result.get("coverage"))).containsEntry("unknownTimeSamples",101).containsEntry("truncated",false);
            assertThat(listOfMaps(result.get("metrics")).getFirst().get("peak")).isNull();
            var projected=policy.projection().apply(event("tool.result",Map.of("tool","query_environment_history",
                    "toolCallId","call-local-env","result",result)));
            assertThat(projected).singleElement().satisfies(observation -> {
                assertThat(observation.type()).isEqualTo("observation.reported");
                assertThat(observation.fields()).containsKey("observedAt");
                assertThat(observation.fields().get("observedAt")).isNull();
            });
            assertThat(fixture.sourceCalls.get()).isEqualTo(calls);
        }
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
        assertThat(policy.request().toString()).doesNotContain("客户端伪造标题");
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
        assertThat(instructions).doesNotContain("private-key");
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
    void taskChangeAndCancellationEventsKeepAuthoritativeBeforeAfterAndTargetSnapshots() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", null, Map.of()));
        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, CLOCK, 8).prepare(request("office", null, Map.of()));

        Map<String, Object> before = new LinkedHashMap<>(Map.of(
                "label", "工作日巡检照明",
                "repeat", Map.of("frequency", "weekdays"),
                "phases", List.of(Map.of(
                        "at", "2026-11-09T02:26:00Z",
                        "actions", List.of(Map.of("deviceId", "light-1", "kind", "lighting",
                                "command", Map.of("power", true)))))));
        Map<String, Object> after = new LinkedHashMap<>(Map.of(
                "label", "工作日巡检照明",
                "repeat", Map.of("frequency", "weekdays"),
                "phases", List.of(Map.of(
                        "at", "2026-11-09T01:30:00Z",
                        "actions", List.of(Map.of("deviceId", "light-2", "kind", "lighting",
                                "command", Map.of("power", false)))))));

        List<AgentEngineEvent> change = policy.projection().apply(event("tool.result", Map.of(
                "tool", "propose_task_change", "toolCallId", "call-change",
                "result", Map.of("proposalId", "proposal-1", "version", 3,
                        "status", "PENDING_CONFIRMATION", "expiresAt", "2026-11-02T02:15:00Z",
                        "kind", "CHANGE", "operation", "edit",
                        "target", Map.of("type", "task_plan", "planId", "plan-1"),
                        "before", before, "after", after,
                        "payload", Map.of("target", Map.of("type", "task_plan", "planId", "plan-1"))))));
        assertThat(change).singleElement().satisfies(projected -> {
            assertThat(projected.type()).isEqualTo("task.change.proposed");
            assertThat(projected.fields()).containsEntry("changeId", "proposal-1")
                    .containsEntry("expiresAt", "2026-11-02T02:15:00Z");
            Map<String, Object> task = map(projected.fields().get("task"));
            // Flattened compatibility fields stay for existing consumers…
            assertThat(task).containsEntry("kind", "change")
                    .containsEntry("changeId", "proposal-1")
                    .containsEntry("operation", "edit")
                    .containsEntry("planId", "plan-1")
                    .containsEntry("version", 3)
                    .containsEntry("status", "PENDING_CONFIRMATION");
            // …while the authoritative snapshots the confirmation card renders survive,
            // including the edited 10:26 → 09:30 checkpoint, repeat rule and device actions.
            Map<String, Object> beforeView = map(task.get("before"));
            assertThat(beforeView).containsEntry("label", "工作日巡检照明")
                    .containsEntry("repeat", Map.of("frequency", "weekdays"));
            assertThat(listOfMaps(beforeView.get("phases"))).singleElement().satisfies(phase -> {
                assertThat(phase).containsEntry("at", "2026-11-09T02:26:00Z");
                assertThat(listOfMaps(phase.get("actions"))).singleElement()
                        .satisfies(action -> assertThat(action).containsEntry("deviceId", "light-1"));
            });
            Map<String, Object> afterView = map(task.get("after"));
            assertThat(afterView).containsEntry("label", "工作日巡检照明")
                    .containsEntry("repeat", Map.of("frequency", "weekdays"));
            assertThat(listOfMaps(afterView.get("phases"))).singleElement().satisfies(phase -> {
                assertThat(phase).containsEntry("at", "2026-11-09T01:30:00Z");
                assertThat(listOfMaps(phase.get("actions"))).singleElement()
                        .satisfies(action -> assertThat(action).containsEntry("deviceId", "light-2"));
            });
            assertThat(map(task.get("target"))).containsEntry("type", "task_plan")
                    .containsEntry("planId", "plan-1");
        });

        List<AgentEngineEvent> cancellation = policy.projection().apply(event("tool.result", Map.of(
                "tool", "propose_task_cancellation", "toolCallId", "call-cancel",
                "result", Map.of("proposalId", "proposal-2", "version", 5,
                        "status", "PENDING_CONFIRMATION", "expiresAt", "2026-11-02T02:15:00Z",
                        "kind", "CANCELLATION", "operation", "cancel",
                        "target", Map.of("type", "task", "taskId", "task-9"),
                        "before", before, "after", before,
                        "payload", Map.of("target", Map.of("type", "task", "taskId", "task-9"))))));
        assertThat(cancellation).singleElement().satisfies(projected -> {
            assertThat(projected.type()).isEqualTo("task.cancellation.proposed");
            assertThat(projected.fields()).containsEntry("cancellationId", "proposal-2");
            Map<String, Object> task = map(projected.fields().get("task"));
            assertThat(task).containsEntry("kind", "cancellation")
                    .containsEntry("cancellationId", "proposal-2")
                    .containsEntry("taskId", "task-9")
                    .containsEntry("operation", "cancel");
            assertThat(map(task.get("target"))).containsEntry("type", "task")
                    .containsEntry("taskId", "task-9");
            Map<String, Object> cancelledView = map(task.get("before"));
            assertThat(cancelledView).containsEntry("label", "工作日巡检照明");
            assertThat(listOfMaps(cancelledView.get("phases"))).singleElement()
                    .satisfies(phase -> assertThat(phase).containsEntry("at", "2026-11-09T02:26:00Z"));
        });
    }

    @Test
    void candidateProgressReportsTotalMatchesRatherThanTheDisplayWindow() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", null, Map.of()));
        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, CLOCK, 8).prepare(request("office", null, Map.of()));
        var sample = java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(index -> Map.of("name", "照明" + index, "location", "26楼")).toList();
        policy.projection().apply(event("tool.result", Map.of(
                "tool", "find_device_candidates", "toolCallId", "call-1",
                "result", Map.of("devices", sample, "totalMatches", 81, "complete", false))));

        var completed = policy.projection().apply(event("tool.completed", Map.of(
                "tool", "find_device_candidates", "toolCallId", "call-1")));

        assertThat(completed).singleElement().satisfies(projected -> {
            Map<String, Object> details = map(projected.fields().get("details"));
            assertThat(details).containsEntry("count", 81).containsEntry("totalMatches", 81)
                    .containsEntry("complete", false);
        });
    }

    @Test
    void closedToolPhaseProjectsAsSkippedStopAndFailuresKeepBoundedText() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", null, Map.of()));
        AgentRunPolicy policy = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, CLOCK, 8).prepare(request("office", null, Map.of()));

        List<AgentEngineEvent> skipped = policy.projection().apply(event("tool.completed", Map.of(
                "tool", "find_device_candidates", "toolCallId", "call-1", "status", "failed",
                "code", "AGENT_TOOL_PHASE_CLOSED")));
        assertThat(skipped).singleElement().satisfies(projected -> {
            assertThat(projected.type()).isEqualTo("tool.completed");
            assertThat(projected.fields()).containsEntry("status", "skipped")
                    .containsEntry("label", "停止继续处理")
                    .containsEntry("summary", "已改用现有结果收尾");
            assertThat(projected.fields()).doesNotContainKeys("code", "error");
        });

        String longError = "x".repeat(260);
        List<AgentEngineEvent> failed = policy.projection().apply(event("tool.completed", Map.of(
                "tool", "get_device_details", "toolCallId", "call-2", "status", "failed",
                "error", longError)));
        assertThat(failed).singleElement().satisfies(projected -> {
            assertThat(projected.fields()).containsEntry("status", "failed")
                    .containsEntry("summary", "本步骤未完成");
            assertThat(map(projected.fields().get("details"))).containsEntry("error", "x".repeat(200));
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

        List<AgentEngineEvent> discovery = policy.projection().apply(event("tool.result", Map.of(
                "tool", "find_device_candidates", "toolCallId", "call-find",
                "result", Map.of("devices", List.of(
                        Map.of("id", "camera-1", "kind", "video", "name", "大堂摄像头", "location", "一层大堂"),
                        Map.of("id", "camera-2", "kind", "video", "name", "走廊摄像头", "location", "一层走廊"))))));
        assertThat(discovery).isEmpty();
        List<AgentEngineEvent> live = policy.projection().apply(event("tool.result", Map.of(
                "tool", "read_device_states", "toolCallId", "call-read",
                "result", Map.of("states", Map.of("camera-1", true),
                        "observedAt", "2026-11-02T01:55:00Z"))));
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
        List<AgentEngineEvent> unknown = policy.projection().apply(event("tool.result", Map.of(
                "tool", "read_device_states", "toolCallId", "call-unknown",
                "result", Map.of("status", "unavailable",
                        "states", java.util.Collections.singletonMap("camera-1", null)))));
        assertThat(unknown).singleElement().satisfies(projected -> {
            assertThat(projected.fields()).containsEntry("observedAt", null);
            assertThat(listOfMaps(projected.fields().get("blocks"))).singleElement().satisfies(block ->
                    assertThat(listOfMaps(block.get("devices"))).singleElement().satisfies(device ->
                            assertThat(device).containsEntry("deviceId", "camera-1")
                                    .containsEntry("online", null).containsEntry("observedAt", null)));
        });
        var snapshotBlock = Map.of("type", "video", "devices",
                List.of(Map.of("deviceId", "camera-1", "name", "大堂摄像头", "online", true)));
        for (Map<String, Object> failure : List.<Map<String, Object>>of(
                Map.of("status", "failed", "blocks", List.of(snapshotBlock)),
                Map.of("ok", false, "status", "unavailable", "blocks", List.of(snapshotBlock)),
                Map.of("ok", false, "status", "unavailable",
                        "states", java.util.Collections.singletonMap("camera-1", null)),
                Map.of("status", "unavailable", "blocks", List.of()))) {
            assertThat(policy.projection().apply(event("tool.result", Map.of(
                    "tool", "read_device_states", "toolCallId", "call-failed",
                    "result", failure)))).isEmpty();
        }


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

    @Test
    void savedReportReferenceExposesOnlyEnergyReadonlyInvestigationTools() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ReportService reports = mock(ReportService.class);
        Map<String, Object> reference = Map.of("type", "report", "reportId", "report-9");
        when(conversations.toolContext("tool-9", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, reference));
        when(reports.read(org.mockito.ArgumentMatchers.eq("report-9"), any(RequestIdentity.class)))
                .thenReturn(new LinkedHashMap<>(Map.of("id", "report-9", "title", "十月用电月报",
                        "status", "completed", "generatedAt", "2026-11-02T09:00:00+08:00")));
        AgentRunPolicyProvider provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, null, null, reports, CLOCK, 8);

        AgentRunPolicy policy = provider.prepare(requestFor("tool-9", "company", null, reference));

        List<Map<String, Object>> tools = listOfMaps(map(policy.request().get("definition")).get("tools"));
        Set<String> names = tools.stream().map(tool -> String.valueOf(tool.get("name"))).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("find_device_candidates", "get_device_details",
                "query_energy_history", "summarize_energy", "compare_energy", "create_energy_report");
    }

    @Test
    void energyResultReferenceIsInterpretationOnlyAndRechecksCurrentMeterAuthorization() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        Map<String, Object> reference = Map.of("type", "energy_report", "reportId", "result-1");
        when(conversations.toolContext("tool-9", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, reference));
        when(conversations.observationReport("tool-9", "conversation-1", "turn-1", "run-1", "result-1"))
                .thenReturn(Map.of("type", "meter-report", "reportId", "result-1", "kind", "statistics",
                        "authorizationMeterIds", List.of("meter-1"), "meters", List.of(),
                        "coverage", Map.of("meters", 1, "dataMeters", 1), "total", Map.of("value", 42.0)));
        when(iot.loadAuthorizedDeviceScope(any())).thenAnswer(invocation -> Mono.just(
                AuthorizedDeviceScope.of(invocation.getArgument(0), IotSourceEnum.COMPANY, List.of(
                        new ltd.qianc.iotagent.iot.AuthorizedDevice("meter-1", "energy", "电表", "26楼", true)))));
        AgentRunPolicyProvider provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, iot, null, null, null, CLOCK, 6);
        AgentRunPolicy policy = provider.prepare(requestFor("tool-9", "company", null, reference));
        assertThat(listOfMaps(map(policy.request().get("definition")).get("tools"))).isEmpty();
        org.mockito.Mockito.doAnswer(invocation ->
                Mono.just(AuthorizedDeviceScope.of(invocation.getArgument(0), IotSourceEnum.COMPANY, List.of())))
                .when(iot).loadAuthorizedDeviceScope(any());
        assertThat(catchThrowableOfType(BusinessException.class, () ->
                provider.prepare(requestFor("tool-9", "company", null, reference))).httpStatus()).isEqualTo(403);
    }

    @Test
    void safetyEventReferenceWindowNeverEndsAfterCurrentTime() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        ObservationAdapter observations = mock(ObservationAdapter.class);
        Map<String, Object> reference = Map.of("type", "safety_event", "eventId", "event-9",
                "deviceId", "gas-9", "ledger", "gas");
        when(conversations.toolContext("tool-10", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, reference));
        when(observations.read(any(ObservationRequest.class))).thenReturn(Mono.just(ObservationResponse.available(
                Map.of("record", Map.of("eventId", "event-9", "deviceId", "gas-9", "deviceName", "燃气九号",
                        "occurredAt", "2026-11-02T01:58:00Z", "report", false)))));
        AgentRunPolicyProvider provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, observations, null, null, CLOCK, 8);

        AgentRunPolicy policy = provider.prepare(requestFor("tool-10", "company", null, reference));

        Map<String, Object> referenceView = map(policy.projection()
                .apply(event("run.started", Map.of())).getFirst().fields().get("reference"));
        Map<String, Object> window = map(referenceView.get("window"));
        assertThat(window).containsEntry("from", "2026-11-02T01:43:00Z")
                .containsEntry("to", "2026-11-02T02:00:00Z");
    }

    @Test
    void officeAlertsFollowTheCurrentCatalogCensus() {
        ConversationFacade conversations = mock(ConversationFacade.class);
        IotAdapter iot = mock(IotAdapter.class);
        when(conversations.toolContext("tool-11", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", null, Map.of()));
        RequestIdentity office = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSourceEnum.OFFICE, PRINCIPAL.ownerKey());
        when(iot.loadAuthorizedDeviceScope(any())).thenReturn(Mono.just(AuthorizedDeviceScope.of(office,
                IotSourceEnum.OFFICE, List.of(new AuthorizedDevice("light-1", "lighting", "吸顶灯", "", true)))));
        AgentRunPolicyProvider withoutSafety = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, iot, null, null, null, CLOCK, 8);

        Set<String> names = toolNames(withoutSafety.prepare(requestFor("tool-11", "office", null, Map.of())));
        assertThat(names).doesNotContain("query_alerts");

        when(iot.loadAuthorizedDeviceScope(any())).thenReturn(Mono.just(AuthorizedDeviceScope.of(office,
                IotSourceEnum.OFFICE, List.of(new AuthorizedDevice("light-1", "lighting", "吸顶灯", "", true),
                        new AuthorizedDevice("smoke-1", "safety", "光电感烟探测器", "", true)))));
        AgentRunPolicyProvider withSafety = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, iot, null, null, null, CLOCK, 8);

        assertThat(toolNames(withSafety.prepare(requestFor("tool-11", "office", null, Map.of()))))
                .contains("query_alerts");
    }

    @Test
    void companyTaskToolsFollowEligibilityAndOldRunsCannotBypassRevocation() {
        var list = mock(ltd.qianc.iotagent.allowlist.PhoneAllowlist.class, org.mockito.Answers.CALLS_REAL_METHODS);
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "company", null, Map.of()));
        var provider = AgentBusinessEngineFactory.policyProvider(
                () -> conversations, null, null, null, null, () -> null, CLOCK, 8, list);
        var request = request("company", null, Map.of());
        assertThat(toolNames(provider.prepare(request))).contains("query_alerts")
                .doesNotContain("propose_task", "list_tasks");
        when(list.companyTasksEnabled(PRINCIPAL)).thenReturn(true);
        assertThat(toolNames(provider.prepare(request))).contains("propose_task", "list_tasks", "get_task");
        var bridge = AgentBusinessEngineFactory.toolBridge(
                conversations, null, null, null, null, null, new ObjectMapper(), null, null, CLOCK, list);
        when(list.companyTasksEnabled(PRINCIPAL)).thenReturn(false);
        assertThat(toolNames(provider.prepare(request))).contains("query_alerts")
                .doesNotContain("propose_task", "list_tasks");
        var error = catchThrowableOfType(BusinessException.class, () -> bridge.execute(Map.of(
                "runId", "run-1", "conversationId", "conversation-1", "turnId", "turn-1",
                "toolContextId", "tool-1", "toolCallId", "old-run-call",
                "toolName", "propose_task", "arguments", Map.of())));
        assertThat(error.code()).isEqualTo("COMPANY_TASKS_NOT_ALLOWED");
        when(conversations.toolContext("tool-1", "conversation-1", "turn-1", "run-1"))
                .thenReturn(new AgentToolContext(PRINCIPAL, "run-1", "conversation-1", "turn-1",
                        "office", null, Map.of()));
        assertThat(toolNames(provider.prepare(request("office", null, Map.of()))))
                .contains("propose_task", "list_tasks");
    }

    private static Set<String> toolNames(AgentRunPolicy policy) {
        return listOfMaps(map(policy.request().get("definition")).get("tools")).stream()
                .map(tool -> String.valueOf(tool.get("name"))).collect(Collectors.toSet());
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
