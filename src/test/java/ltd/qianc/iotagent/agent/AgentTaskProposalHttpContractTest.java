package ltd.qianc.iotagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import ltd.qianc.iotagent.IotAgentBackendApplication;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskProposalFact;
import ltd.qianc.iotagent.task.TaskProposalStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

/** Real task normalization and run policy, observed through the private HTTP contract. */
@SpringBootTest(classes = IotAgentBackendApplication.class,
        properties = {"iot.identity.verify=false", "agent.bridge.token=test-bridge",
                "agent.storage.mysql.enabled=false", "agent.storage.redis.enabled=false"})
@AutoConfigureMockMvc
class AgentTaskProposalHttpContractTest {
    @Autowired private MockMvc http;
    @Autowired private ObjectMapper mapper;
    @MockBean private ConversationFacade conversations;
    @MockBean private IotAdapter iot;
    @MockBean private TaskProposalStore proposals;
    @MockBean(answer = org.mockito.Answers.CALLS_REAL_METHODS)
    private ltd.qianc.iotagent.allowlist.PhoneAllowlist allowlist;

    @Test
    void candidateTaskSurvivesTransportAndAllowsASecondIndependentProposal() throws Exception {
        String context = UUID.randomUUID().toString();
        PrincipalInfo principal = new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture");
        when(allowlist.companyTasksEnabled(principal)).thenReturn(true);
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(),
                IotSourceEnum.COMPANY, principal.ownerKey());
        when(conversations.toolContext(context, "conversation", "turn", "run")).thenReturn(
                new AgentToolContext(principal, "run", "conversation", "turn", "company", null, Map.of()));
        List<AuthorizedDevice> lights = IntStream.rangeClosed(1, 5).mapToObj(index ->
                new AuthorizedDevice("light-" + index, "lighting", "灯" + index, "26楼运营部", false,
                        "control-" + index, "hub-1", null, null, List.of(), List.of(), Map.of())).toList();
        when(iot.loadAuthorizedDeviceScope(any())).thenReturn(Mono.just(
                AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY, lights)));
        DeviceCandidateRecord set = new DeviceCandidateRecord("set-2", "conversation", identity.tenantCode(),
                identity.ownerKey(), IotSourceEnum.COMPANY, "catalog",
                new DeviceCandidateQuery("lighting", List.of(), "26楼运营部", List.of(), List.of(), null, null),
                lights, true, 5, Instant.now().toString(), "exact", "s2");
        when(iot.resolveDeviceCandidateSet(any(), anyString(), anyString())).thenReturn(Mono.just(set));
        allowControlPreflight(set);
        List<TaskProposalFact> saved = new ArrayList<>();
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> {
            TaskProposalFact fact = invocation.getArgument(0);
            saved.add(fact);
            return fact;
        });

        JsonNode first = propose(context, "first", "关闭运营部照明", 0);
        assertThat(first.path("halt").asBoolean()).isFalse();
        // A completion with halt=false is rejected by the real Node engine before tool.result,
        // losing the confirmation card even though Java already saved the proposal.
        assertThat(first.has("completion")).as("first candidate task must remain usable by the engine").isFalse();
        assertThat(first.path("result").path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        JsonNode actions = first.path("result").path("payload").path("phases").get(0).path("actions");
        assertThat(actions).hasSize(5);
        for (int index = 0; index < 5; index++) {
            assertThat(actions.get(index).path("deviceId").asText()).isEqualTo("light-" + (index + 1));
            assertThat(actions.get(index).path("command").path("closeOrOpen").asInt(-1)).isZero();
        }
        JsonNode modelPhase = first.path("modelResult").path("payload").path("phases").get(0);
        assertThat(modelPhase.path("actionTargets").get(0).path("count").asInt()).isEqualTo(5);
        assertThat(modelPhase.path("actionTargets").get(0).path("command").path("closeOrOpen").asInt(-1)).isZero();
        assertThat(modelPhase.path("preflight").path("unreadableCount").asInt()).isEqualTo(5);
        assertThat(first.path("modelResult").toString()).doesNotContain("light-1", "control-", "deviceName", "includedScopes");
        assertThat(first.path("modelResult").path("proposalId")).isEqualTo(first.path("result").path("proposalId"));
        JsonNode second = propose(context, "second", "恢复运营部照明", 1);
        assertThat(second.path("halt").asBoolean()).isTrue();
        assertThat(second.has("completion")).as("candidate tasks use the model summary, not the targets receipt").isFalse();
        assertThat(second.path("result").path("proposalId").asText())
                .isNotEqualTo(first.path("result").path("proposalId").asText());
        assertThat(saved).hasSize(2).allSatisfy(fact -> assertThat(fact.status()).isEqualTo("PENDING_CONFIRMATION"));
    }

    @Test
    void officeTaskResolvesExplicitAuthorizedCandidatesOutsideTheFocusedRegion() throws Exception {
        String context = UUID.randomUUID().toString();
        PrincipalInfo principal = new PrincipalInfo("Bearer office", "TENANT", "office-owner", "fixture");
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(),
                IotSourceEnum.OFFICE, principal.ownerKey());
        when(conversations.toolContext(context, "conversation", "turn", "run")).thenReturn(
                new AgentToolContext(principal, "run", "conversation", "turn", "office", "room-1", Map.of()));
        AuthorizedDevice current = new AuthorizedDevice("room-light", "lighting", "当前办公室灯", "26楼", false,
                "room-control", "room-hub", null, null, List.of(), List.of("room-1"), Map.of());
        AuthorizedDevice other = new AuthorizedDevice("other-light", "lighting", "其他办公室灯", "27楼", false,
                "other-control", "other-hub", null, null, List.of(), List.of("room-2"), Map.of());
        when(iot.loadAuthorizedDeviceScope(any())).thenReturn(Mono.just(
                AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, List.of(current, other))));
        DeviceCandidateRecord set = new DeviceCandidateRecord("office-set", "conversation", "TENANT",
                principal.ownerKey(), IotSourceEnum.OFFICE, "catalog",
                new DeviceCandidateQuery("lighting", List.of(), "26楼", List.of(), List.of(), null, null),
                List.of(current, other), true, 2, Instant.now().toString(), "exact", "s1");
        when(iot.resolveDeviceCandidateSet(any(), anyString(), anyString())).thenReturn(Mono.just(set));
        allowControlPreflight(set);
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> arguments = Map.of("label", "明天关闭已选办公室灯", "candidateSetIds", List.of("s1"),
                "phases", List.of(Map.of("at", Instant.now().plusSeconds(3600).toString(),
                        "actions", List.of(
                                Map.of("deviceId", "d1_1", "kind", "lighting", "command", Map.of("closeOrOpen", 0)),
                                Map.of("deviceId", "d1_2", "kind", "lighting", "command", Map.of("closeOrOpen", 0))))));
        String body = mapper.writeValueAsString(Map.of("protocolVersion", 1, "runId", "run",
                "toolContextId", context, "conversationId", "conversation", "turnId", "turn",
                "toolName", "propose_task", "toolCallId", "office-read", "arguments", arguments));

        JsonNode response = mapper.readTree(http.perform(post("/internal/agent/tools")
                        .header("X-Agent-Bridge-Token", "test-bridge")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(response.path("result").path("status").asText()).isEqualTo("PENDING_CONFIRMATION");
        JsonNode actions = response.path("result").path("payload").path("phases").get(0).path("actions");
        assertThat(actions).extracting(action -> action.path("deviceId").asText())
                .containsExactly("room-light", "other-light");
    }

    private void allowControlPreflight(DeviceCandidateRecord set) {
        when(iot.findDeviceCandidates(any(), any(), any())).thenAnswer(invocation ->
                Mono.just(new DeviceCandidateSet(set.candidateSetId(), invocation.getArgument(1),
                        set.members(), true, set.totalMatches())));
        when(iot.inspectDeviceCapabilities(any(), any(), any())).thenReturn(Mono.just(
                new DeviceCapabilityInspection(List.of(set.candidateSetId()),
                        set.members().stream().map(device -> new DeviceCapabilityInspection.DeviceCapability(
                                device.id(), "lighting", List.of(Map.of("closeOrOpen", 0),
                                Map.of("closeOrOpen", 1)), null)).toList())));
        when(iot.readDeviceStates(any(), any(), any()))
                .thenReturn(Mono.just(new DeviceStateReadResult(Map.of(), Instant.now().toString())));
    }

    private JsonNode propose(String context, String call, String label, int power) throws Exception {
        Map<String, Object> arguments = Map.of("label", label, "candidateSetIds", List.of("s2"),
                "phases", List.of(Map.of("at", Instant.now().plusSeconds(120).toString(), "actions", List.of(),
                        "lighting", Map.of("candidateSetId", "s2", "closeOrOpen", power))));
        String body = mapper.writeValueAsString(Map.of("protocolVersion", 1, "runId", "run",
                "toolContextId", context, "conversationId", "conversation", "turnId", "turn",
                "toolName", "propose_task", "toolCallId", call, "arguments", arguments));
        return mapper.readTree(http.perform(post("/internal/agent/tools")
                .header("X-Agent-Bridge-Token", "test-bridge").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
