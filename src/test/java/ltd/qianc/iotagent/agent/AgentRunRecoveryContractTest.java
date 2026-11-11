package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

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
                        List.of(new ltd.qianc.iotagent.iot.AuthorizedDevice("light-" + version.get(), "lighting",
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

    @Test
    void capabilityReadsDoNotResetFailuresJustBecauseCandidateIdsChange() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "company", null, Map.of()));
        var version = new AtomicInteger();
        IotAdapter iot = new IotAdapter() {
            @Override public Mono<ltd.qianc.iotagent.iot.AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                throw new UnsupportedOperationException();
            }
            @Override public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity,
                    DeviceCandidateQuery query, DeviceCandidateBinding binding) {
                return Mono.error(new BusinessException("UPSTREAM_UNAVAILABLE", "暂不可读", 503));
            }
            @Override public Mono<ltd.qianc.iotagent.iot.DeviceCandidateRecord> resolveDeviceCandidateSet(
                    RequestIdentity identity, String id, String conversationId) {
                return Mono.just(new ltd.qianc.iotagent.iot.DeviceCandidateRecord(id, conversationId,
                        identity.tenantCode(), identity.ownerKey(), identity.source(), "catalog",
                        new DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, null),
                        List.of(new ltd.qianc.iotagent.iot.AuthorizedDevice("light-1", "lighting", "灯", "26楼", false)),
                        true, 1, "2026-11-03T00:00:00Z", "exact", null));
            }
            @Override public Mono<ltd.qianc.iotagent.iot.DeviceCapabilityInspection> inspectDeviceCapabilities(
                    RequestIdentity identity, ltd.qianc.iotagent.iot.DeviceCapabilityRequest request) {
                return Mono.just(new ltd.qianc.iotagent.iot.DeviceCapabilityInspection(
                        request.candidateSets().stream().map(
                                ltd.qianc.iotagent.iot.DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId).toList(),
                        List.of(new ltd.qianc.iotagent.iot.DeviceCapabilityInspection.DeviceCapability(
                                "light-1", "lighting", List.of(Map.of("closeOrOpen", version.get())), null))));
            }
        };
        var bridge = AgentToolBridgeFactory.local(conversations, iot, mock(ActionDraftFacade.class),
                mock(TaskFacade.class), new ObjectMapper(), null);
        bridge.execute(capabilityRequest("set-1", "cap-1"));
        for (int i = 0; i < 2; i++) {
            assertThat(catchThrowableOfType(BusinessException.class,
                    () -> bridge.execute(request("26楼", "failed-cap-" + version.getAndIncrement()))).code())
                    .isEqualTo("UPSTREAM_UNAVAILABLE");
        }
        version.set(0);
        bridge.execute(capabilityRequest("set-2", "cap-2"));
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(request("26楼", "still-blocked-cap"))).code()).isEqualTo("AGENT_NO_PROGRESS");
        version.set(1);
        bridge.execute(capabilityRequest("set-3", "cap-3"));
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(request("26楼", "new-capability"))).code()).isEqualTo("UPSTREAM_UNAVAILABLE");
    }

    @Test
    void identicalProposalRetryIsBlockedUnlessPriorFailureWasTransientRead() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "company", null, Map.of()));
        var tasks = mock(TaskFacade.class);
        when(tasks.propose(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    throw new BusinessException("TASK_REJECTED", "任务参数无效", 400);
                });
        var allowlist = mock(ltd.qianc.iotagent.allowlist.PhoneAllowlist.class, org.mockito.Answers.CALLS_REAL_METHODS);
        when(allowlist.companyTasksEnabled(any())).thenReturn(true);
        AgentToolBridge bridge = AgentBusinessEngineFactory.toolBridge(conversations, mock(IotAdapter.class),
                null, null, mock(ActionDraftFacade.class), tasks, new ObjectMapper(), null, null,
                java.time.Clock.systemUTC(), allowlist);

        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(taskRequest("关灯A", "task-a1"))).code()).isEqualTo("TASK_REJECTED");
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(taskRequest("关灯A", "task-a2"))).code()).isEqualTo("AGENT_NO_PROGRESS");
        verify(tasks, times(1)).propose(any(), anyString(), anyString(), anyString(), anyString(), anyString());

        var upstreamAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            upstreamAttempts.incrementAndGet();
            throw new BusinessException("UPSTREAM_UNAVAILABLE", "暂不可读", 503);
        }).when(tasks).propose(any(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(taskRequest("关灯B", "task-b1"))).code()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(taskRequest("关灯B", "task-b2"))).code()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(upstreamAttempts.get()).isEqualTo(2);
    }

    @Test
    void renewedWholeScopeHandleIsNoProgressWhilePreciseAndDifferentScopeCorrectionsStayLegal() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "office", null, Map.of()));
        var scopeQuery = new DeviceCandidateQuery("lighting", List.of(), "26楼运营办公区", List.of(), List.of(), null, null);
        List<ltd.qianc.iotagent.iot.AuthorizedDevice> members = List.of(
                new ltd.qianc.iotagent.iot.AuthorizedDevice("light-1", "lighting", "运营办公区灯1", "26楼运营办公区", false),
                new ltd.qianc.iotagent.iot.AuthorizedDevice("light-2", "lighting", "运营办公区灯2", "26楼运营办公区", false),
                new ltd.qianc.iotagent.iot.AuthorizedDevice("light-3", "lighting", "运营办公区灯3", "26楼运营办公区", false));
        List<ltd.qianc.iotagent.iot.AuthorizedDevice> widerMembers = java.util.stream.Stream.concat(members.stream(),
                        java.util.stream.Stream.of(new ltd.qianc.iotagent.iot.AuthorizedDevice(
                                "light-4", "lighting", "27楼灯", "27楼", false)))
                .toList();
        IotAdapter iot = new IotAdapter() {
            @Override public Mono<ltd.qianc.iotagent.iot.AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                throw new UnsupportedOperationException();
            }
            @Override public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity,
                    DeviceCandidateQuery query, DeviceCandidateBinding binding) {
                if (query.deviceIds() != null && !query.deviceIds().isEmpty()) {
                    return Mono.just(candidateSet("s2", query, members, List.of("d2_1", "d2_2", "d2_3")));
                }
                if ("27楼".equals(query.scopeText())) {
                    return Mono.just(candidateSet("s3", query, widerMembers, List.of("d3_1", "d3_2", "d3_3", "d3_4")));
                }
                return Mono.just(candidateSet("s1", scopeQuery, members, List.of("d1_1", "d1_2", "d1_3")));
            }
            @Override public Mono<ltd.qianc.iotagent.iot.DeviceCandidateRecord> resolveDeviceCandidateSet(
                    RequestIdentity identity, String id, String conversationId) {
                return Mono.just(new ltd.qianc.iotagent.iot.DeviceCandidateRecord(id, "conversation", "TENANT",
                        "owner", ltd.qianc.iotagent.iot.IotSourceEnum.OFFICE, "catalog", scopeQuery, members,
                        true, members.size(), "2026-11-05T00:00:00Z", "fuzzy", "s1"));
            }
        };
        var actions = mock(ActionDraftFacade.class);
        Map<String, Object> scopeRecovery = Map.of("reason", "fuzzy", "retrySameScope", false,
                "nextTool", "get_device_details");
        var proposals = new AtomicInteger();
        when(actions.propose(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    proposals.incrementAndGet();
                    if (invocation.getArgument(4, String.class).contains("\"lighting\":{")) {
                        throw new BusinessException("CANDIDATE_SCOPE_UNRESOLVED",
                                "候选范围尚未精确查明，请给出精确成员或让用户澄清范围", scopeRecovery, 422);
                    }
                    return new ltd.qianc.iotagent.action.ActionDraftFact("draft-" + proposals.get(), 1,
                            "PENDING_CONFIRMATION", "{}", "key-" + proposals.get());
                });
        when(actions.view(any())).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "draftId", "draft-1", "status", "PENDING_CONFIRMATION",
                "actions", List.of(Map.of("deviceId", "light-1", "kind", "lighting", "satisfied", false)),
                "counts", Map.of("total", 1, "operate", 1, "satisfied", 0, "excluded", 0))));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot, actions,
                mock(TaskFacade.class), new ObjectMapper(), null);

        BusinessException rejected = catchThrowableOfType(BusinessException.class, () -> bridge.execute(
                toolRequest("propose_lighting_draft", Map.of("candidateSetId", "s1", "closeOrOpen", 0), "propose-1")));
        assertThat(rejected.code()).isEqualTo("CANDIDATE_SCOPE_UNRESOLVED");
        assertThat(rejected.recovery()).isEqualTo(scopeRecovery);
        bridge.execute(toolRequest("get_device_details",
                Map.of("candidateSetId", "s1", "deviceIds", List.of("d1_1", "d1_2", "d1_3")), "details-1"));

        BusinessException blocked = catchThrowableOfType(BusinessException.class, () -> bridge.execute(
                toolRequest("propose_lighting_draft", Map.of("candidateSetId", "s2", "closeOrOpen", 0), "propose-2")));
        assertThat(blocked.code()).isEqualTo("AGENT_NO_PROGRESS");
        assertThat(proposals.get()).isEqualTo(1);
        assertThat(blocked.recovery()).isInstanceOf(Map.class);
        var recovery = (Map<?, ?>) blocked.recovery();
        assertThat(recovery.get("reason")).isEqualTo("no_new_evidence");
        assertThat(recovery.get("retrySameScope")).isEqualTo(false);
        assertThat(recovery.get("nextTool")).isEqualTo("get_device_details");
        assertThat(blocked.getMessage() + String.valueOf(recovery)).doesNotContain("light-1");

        // A successful precise draft finalizes the run (confirmation card), so it comes last.
        bridge.execute(toolRequest("find_device_candidates",
                Map.of("kind", "lighting", "scopeText", "27楼"), "find-2"));
        BusinessException differentScope = catchThrowableOfType(BusinessException.class, () -> bridge.execute(
                toolRequest("propose_lighting_draft", Map.of("candidateSetId", "s3", "closeOrOpen", 0), "propose-3")));
        assertThat(differentScope.code()).isEqualTo("CANDIDATE_SCOPE_UNRESOLVED");
        assertThat(differentScope.recovery()).isEqualTo(scopeRecovery);
        assertThat(proposals.get()).isEqualTo(2);

        bridge.execute(toolRequest("propose_action_draft", Map.of("candidateSetIds", List.of("s2"),
                "summary", "关闭点名灯", "actions", List.of(Map.of("deviceId", "d2_1",
                        "closeOrOpen", 0, "selected", true))), "propose-4"));
        assertThat(proposals.get()).isEqualTo(3);
    }

    @Test
    void renewedDeviceHandleForTheSameMemberIsNoProgressButAChangedCommandIsLegal() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "office", null, Map.of()));
        var scopeQuery = new DeviceCandidateQuery("lighting", List.of(), "26楼运营办公区", List.of(), List.of(), null, null);
        List<ltd.qianc.iotagent.iot.AuthorizedDevice> members = List.of(
                new ltd.qianc.iotagent.iot.AuthorizedDevice("light-1", "lighting", "运营办公区灯1", "26楼运营办公区", false),
                new ltd.qianc.iotagent.iot.AuthorizedDevice("light-2", "lighting", "运营办公区灯2", "26楼运营办公区", false),
                new ltd.qianc.iotagent.iot.AuthorizedDevice("light-3", "lighting", "运营办公区灯3", "26楼运营办公区", false));
        IotAdapter iot = new IotAdapter() {
            @Override public Mono<ltd.qianc.iotagent.iot.AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                throw new UnsupportedOperationException();
            }
            @Override public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity,
                    DeviceCandidateQuery query, DeviceCandidateBinding binding) {
                if (query.deviceIds() != null && !query.deviceIds().isEmpty()) {
                    return Mono.just(candidateSet("s2", query, members, List.of("d2_1", "d2_2", "d2_3")));
                }
                return Mono.just(candidateSet("s1", scopeQuery, members, List.of("d1_1", "d1_2", "d1_3")));
            }
            @Override public Mono<ltd.qianc.iotagent.iot.DeviceCandidateRecord> resolveDeviceCandidateSet(
                    RequestIdentity identity, String id, String conversationId) {
                return Mono.just(new ltd.qianc.iotagent.iot.DeviceCandidateRecord(id, "conversation", "TENANT",
                        "owner", ltd.qianc.iotagent.iot.IotSourceEnum.OFFICE, "catalog", scopeQuery, members,
                        true, members.size(), "2026-11-05T00:00:00Z", "exact", "s1"));
            }
        };
        var actions = mock(ActionDraftFacade.class);
        var proposals = new AtomicInteger();
        when(actions.propose(any(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    proposals.incrementAndGet();
                    throw new BusinessException("INVALID_ACTION_DRAFT", "草案内容无效");
                });
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot, actions,
                mock(TaskFacade.class), new ObjectMapper(), null);

        bridge.execute(toolRequest("find_device_candidates",
                Map.of("kind", "lighting", "scopeText", "26楼运营办公区"), "find-1"));
        assertThat(catchThrowableOfType(BusinessException.class, () -> bridge.execute(toolRequest(
                "propose_action_draft", Map.of("candidateSetIds", List.of("s1"), "summary", "关灯",
                        "actions", List.of(Map.of("deviceId", "d1_1", "closeOrOpen", 0, "selected", true))),
                "propose-1"))).code()).isEqualTo("INVALID_ACTION_DRAFT");
        bridge.execute(toolRequest("get_device_details",
                Map.of("candidateSetId", "s1", "deviceIds", List.of("d1_1", "d1_2", "d1_3")), "details-1"));

        assertThat(catchThrowableOfType(BusinessException.class, () -> bridge.execute(toolRequest(
                "propose_action_draft", Map.of("candidateSetIds", List.of("s2"), "summary", "关灯",
                        "actions", List.of(Map.of("deviceId", "d2_1", "closeOrOpen", 0, "selected", true))),
                "propose-2"))).code()).isEqualTo("AGENT_NO_PROGRESS");
        assertThat(proposals.get()).isEqualTo(1);

        assertThat(catchThrowableOfType(BusinessException.class, () -> bridge.execute(toolRequest(
                "propose_action_draft", Map.of("candidateSetIds", List.of("s2"), "summary", "开灯",
                        "actions", List.of(Map.of("deviceId", "d2_1", "closeOrOpen", 1, "selected", true))),
                "propose-3"))).code()).isEqualTo("INVALID_ACTION_DRAFT");
        assertThat(proposals.get()).isEqualTo(2);
    }

    @Test
    void identicalCapabilityReadsDoNotReopenAProposalButChangedCapabilitiesDo() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "office", null, Map.of()));
        var iot = mock(IotAdapter.class);
        var member = new ltd.qianc.iotagent.iot.AuthorizedDevice("light-1", "lighting", "灯", "26楼", false);
        when(iot.resolveDeviceCandidateSet(any(), anyString(), anyString())).thenReturn(Mono.just(
                new ltd.qianc.iotagent.iot.DeviceCandidateRecord("set-1", "conversation", "TENANT", "owner",
                        ltd.qianc.iotagent.iot.IotSourceEnum.OFFICE, "catalog",
                        new DeviceCandidateQuery("lighting", List.of("light-1"), null, List.of(), List.of(), null, null),
                        List.of(member), true, 1, "2026-11-05T00:00:00Z", "exact", null)));
        var supportedPower = new AtomicInteger();
        when(iot.inspectDeviceCapabilities(any(), any(), any())).thenAnswer(invocation -> Mono.just(
                new ltd.qianc.iotagent.iot.DeviceCapabilityInspection(List.of("set-1"), List.of(
                        new ltd.qianc.iotagent.iot.DeviceCapabilityInspection.DeviceCapability("light-1", "lighting",
                                List.of(Map.of("closeOrOpen", supportedPower.get())), null)))));
        var tasks = mock(TaskFacade.class);
        when(tasks.propose(any(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new BusinessException("INVALID_TASK_PROPOSAL", "fixture capability rejection", 422));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot,
                mock(ActionDraftFacade.class), tasks, new ObjectMapper(), null);

        bridge.execute(capabilityRequest("set-1", "cap-1"));
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(taskRequest("开灯", "task-1"))).code()).isEqualTo("INVALID_TASK_PROPOSAL");
        bridge.execute(capabilityRequest("set-1", "cap-2"));
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(taskRequest("开灯", "task-2"))).code()).isEqualTo("AGENT_NO_PROGRESS");
        supportedPower.set(1);
        bridge.execute(capabilityRequest("set-1", "cap-3"));
        assertThat(catchThrowableOfType(BusinessException.class,
                () -> bridge.execute(taskRequest("开灯", "task-3"))).code()).isEqualTo("INVALID_TASK_PROPOSAL");
    }

    @Test
    void expiredReferenceFailuresAreIdempotentAndRepeatedNewCallsEventuallyStop() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "office", null, Map.of()));
        var iot = mock(IotAdapter.class);
        when(iot.resolveDeviceCandidateSet(any(), anyString(), anyString())).thenReturn(
                Mono.error(new BusinessException("CANDIDATE_SET_NOT_FOUND", "fixture expired scope", 404)));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot,
                mock(ActionDraftFacade.class), mock(TaskFacade.class), new ObjectMapper(), null);

        for (String call : List.of("expired-1", "expired-1", "expired-2")) {
            assertThat(catchThrowableOfType(BusinessException.class, () -> bridge.execute(toolRequest(
                    "propose_lighting_draft", Map.of("candidateSetId", "s9", "closeOrOpen", 0), call))).code())
                    .isEqualTo("CANDIDATE_SET_NOT_FOUND");
        }
        assertThat(catchThrowableOfType(BusinessException.class, () -> bridge.execute(toolRequest(
                "propose_lighting_draft", Map.of("candidateSetId", "s9", "closeOrOpen", 0), "expired-3"))).code())
                .isEqualTo("AGENT_NO_PROGRESS");
    }

    @Test
    void correctedPrecisionBindingsAndHiddenMembersAreEvidenceButObservationTimeIsNot() {
        var conversations = mock(ConversationFacade.class);
        when(conversations.toolContext(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new AgentToolContext(new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture"),
                        "run", "conversation", "turn", "office", null, Map.of()));
        var visible = java.util.stream.IntStream.rangeClosed(1, 50).mapToObj(index ->
                new ltd.qianc.iotagent.iot.AuthorizedDevice("light-" + index, "lighting", "灯" + index, "26楼", false))
                .toList();
        var discoveries = new AtomicInteger();
        var iot = mock(IotAdapter.class);
        var stored = new java.util.LinkedHashMap<String, ltd.qianc.iotagent.iot.DeviceCandidateRecord>();
        when(iot.findDeviceCandidates(any(), any(), any())).thenAnswer(invocation -> {
            int index = discoveries.incrementAndGet();
            var members = new java.util.ArrayList<>(visible);
            members.add(new ltd.qianc.iotagent.iot.AuthorizedDevice(
                    index >= 3 ? "changed-hidden-light" : "hidden-light", "lighting", "后区灯", "26楼", false,
                    index >= 4 ? "new-control" : null, null, null, null, List.of(), List.of(),
                    index == 5 ? Map.of("observedAt", "2026-11-05T05:00:00Z") : Map.of()));
            DeviceCandidateQuery query = invocation.getArgument(1);
            var record = new ltd.qianc.iotagent.iot.DeviceCandidateRecord("uuid-s" + index, "conversation", "TENANT",
                    "owner", ltd.qianc.iotagent.iot.IotSourceEnum.OFFICE, "catalog", query, members, true, members.size(),
                    "2026-11-05T00:00:00Z", index == 1 ? "fuzzy" : "exact", "s" + index);
            stored.put(record.candidateSetId(), record);
            stored.put(record.handle(), record);
            return Mono.just(new DeviceCandidateSet("uuid-s" + index, query, visible, false, members.size(),
                    null, List.of(), List.of(), new DeviceCandidateSet.Resolution(index == 1 ? "fuzzy" : "exact",
                    query.scopeText(), List.of(), List.of()), "s" + index,
                    java.util.stream.IntStream.rangeClosed(1, 50).mapToObj(position -> "d" + index + "_" + position).toList(),
                    members));
        });
        when(iot.resolveDeviceCandidateSet(any(), anyString(), anyString())).thenAnswer(invocation ->
                Mono.justOrEmpty(stored.get(invocation.getArgument(1, String.class))));
        var actions = mock(ActionDraftFacade.class);
        when(actions.propose(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException("INVALID_ACTION_DRAFT", "fixture capability rejection", 422));
        AgentToolBridge bridge = AgentToolBridgeFactory.local(conversations, iot, actions,
                mock(TaskFacade.class), new ObjectMapper(), null);

        for (int index = 1; index <= 4; index++) {
            bridge.execute(toolRequest("find_device_candidates",
                    Map.of("kind", "lighting", "scopeText", "第" + index + "次范围"), "find-" + index));
            String set = "s" + index;
            assertThat(catchThrowableOfType(BusinessException.class, () -> bridge.execute(toolRequest(
                    "propose_lighting_draft", Map.of("candidateSetId", set, "closeOrOpen", 0),
                    "propose-" + set))).code()).isEqualTo("INVALID_ACTION_DRAFT");
        }
        assertThat(bridge.execute(toolRequest("find_device_candidates",
                Map.of("kind", "lighting", "scopeText", "仅观察时间更新"), "find-5")).get("status"))
                .isEqualTo("duplicate_result");
    }

    private static DeviceCandidateSet candidateSet(String id, DeviceCandidateQuery query,
            List<ltd.qianc.iotagent.iot.AuthorizedDevice> members, List<String> handles) {
        return new DeviceCandidateSet("uuid-" + id, query, members, true, members.size(),
                null, List.of(), List.of(),
                new DeviceCandidateSet.Resolution("fuzzy", query.scopeText(), List.of(), List.of()),
                id, handles, members);
    }

    private static Map<String, Object> toolRequest(String toolName, Map<String, Object> arguments, String call) {
        return Map.of("runId", "run", "toolContextId", "context", "conversationId", "conversation",
                "turnId", "turn", "toolName", toolName, "toolCallId", call, "arguments", arguments);
    }

    private static Map<String, Object> taskRequest(String label, String call) {
        return Map.of("runId", "run", "toolContextId", "context", "conversationId", "conversation",
                "turnId", "turn", "toolName", "propose_task", "toolCallId", call,
                "arguments", Map.of("label", label, "candidateSetIds", List.of("set-1"),
                        "phases", List.of(Map.of("at", "2026-11-04T10:00:00+08:00", "actions", List.of()))));
    }

    private static Map<String, Object> capabilityRequest(String set, String call) {
        return Map.of("runId", "run", "toolContextId", "context", "conversationId", "conversation",
                "turnId", "turn", "toolName", "inspect_device_capabilities", "toolCallId", call,
                "arguments", Map.of("candidateSets", List.of(
                        Map.of("candidateSetId", set, "deviceIds", List.of("light-1")))));
    }

    private static Map<String, Object> request(String scope, String call) {
        return Map.of("runId", "run", "toolContextId", "context", "conversationId", "conversation",
                "turnId", "turn", "toolName", "find_device_candidates", "toolCallId", call,
                "arguments", Map.of("kind", "lighting", "scopeText", scope));
    }
}
