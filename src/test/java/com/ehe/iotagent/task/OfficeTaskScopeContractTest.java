package com.ehe.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.DeviceCandidateBinding;
import com.ehe.iotagent.iot.DeviceCandidateQuery;
import com.ehe.iotagent.iot.DeviceCandidateRecord;
import com.ehe.iotagent.iot.DeviceCandidateSet;
import com.ehe.iotagent.iot.DeviceCapabilityInspection;
import com.ehe.iotagent.iot.DeviceCapabilityRequest;
import com.ehe.iotagent.iot.DeviceStateReadResult;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class OfficeTaskScopeContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-05T02:42:44Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "LOCAL");

    @Test
    void authorizedBrowseScopeBuildsBothWeekdayStagesWithoutDroppingCurrentlySatisfiedLights() throws Exception {
        ScopeAdapter adapter = new ScopeAdapter("browse");
        adapter.scopeText = "26楼运营办公区";
        TaskProposalFact proposal = facade(adapter).propose(PRINCIPAL, "office", "conversation", "turn", request(), "weekday");
        JsonNode normalized = JSON.readTree(proposal.payloadJson());

        assertThat(proposal.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(normalized.path("repeat").path("frequency").asText()).isEqualTo("weekdays");
        assertThat(normalized.path("phases")).hasSize(2);
        for (int index = 0; index < 2; index++) {
            JsonNode phase = normalized.path("phases").get(index);
            int expectedPower = index == 0 ? 1 : 0;
            assertThat(phase.path("actions")).extracting(action -> action.path("deviceId").asText())
                    .containsExactly("light-1", "light-2", "light-3");
            assertThat(phase.path("actions")).allSatisfy(action ->
                    assertThat(action.path("command").path("closeOrOpen").asInt()).isEqualTo(expectedPower));
            assertThat(phase.path("preflight").path("includedScopes")).extracting(JsonNode::asText)
                    .containsExactly("26楼运营办公区");
            assertThat(phase.path("preflight").path("excludedScopes").isArray()).isTrue();
            assertThat(phase.path("preflight").path("excludedScopes")).isEmpty();
            assertThat(phase.path("preflight").path("excludedCount").asInt(-1)).isZero();
        }
        assertThat(normalized.path("phases").get(1).path("preflight").path("alreadySatisfiedCount").asInt()).isEqualTo(3);
    }

    @Test
    void fuzzyCompleteScopeReturnsAnActionableFailureRatherThanPermissionToExpandIt() {
        assertThatThrownBy(() -> facade(new ScopeAdapter("fuzzy")).propose(
                PRINCIPAL, "office", "conversation", "turn", request(), "fuzzy"))
                .isInstanceOfSatisfying(com.ehe.iotagent.error.BusinessException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("TASK_SCOPE_UNRESOLVED");
                    assertThat(failure.recovery()).isInstanceOfSatisfying(Map.class, recovery -> {
                        assertThat(recovery.get("reason")).isEqualTo("scope_unresolved");
                        assertThat(recovery.get("resolution")).isEqualTo("fuzzy");
                        assertThat(recovery.get("nextTool")).isEqualTo("get_device_details");
                        assertThat(recovery.get("retrySameScope")).isEqualTo(false);
                    });
                });
    }

    @Test
    void explicitOfficeMembersCannotBypassCapabilityVerification() {
        ScopeAdapter adapter = new ScopeAdapter("fuzzy");
        adapter.incapable = "light-2";
        assertThatThrownBy(() -> facade(adapter).propose(PRINCIPAL, "office", "conversation", "turn",
                """
                {"label":"点选设备","candidateSetIds":["s1"],"phases":[{"at":"2026-11-05T10:45:00+08:00",
                 "actions":[{"deviceId":"d1_2","closeOrOpen":1}]}]}
                """, "incapable")).isInstanceOf(com.ehe.iotagent.error.BusinessException.class);
    }

    @Test
    void explicitOfficeSelectionNamesItsVerifiedMemberInTheConfirmationScope() throws Exception {
        JsonNode proposal = JSON.readTree(facade(new ScopeAdapter("fuzzy")).propose(PRINCIPAL, "office",
                "conversation", "turn", """
                {"label":"点选设备","candidateSetIds":["s1"],"phases":[{"at":"2026-11-05T10:45:00+08:00",
                 "actions":[{"deviceId":"d1_2","closeOrOpen":1}]}]}
                """, "explicit").payloadJson());
        JsonNode phase = proposal.path("phases").get(0);
        assertThat(phase.path("preflight").path("includedScopes")).extracting(JsonNode::asText).containsExactly("灯2");
        assertThat(phase.path("preflight").path("excludedScopes").isArray()).isTrue();
        assertThat(phase.path("preflight").path("excludedScopes")).isEmpty();
        assertThat(phase.path("preflight").path("excludedCount").asInt(-1)).isZero();
        assertThat(phase.path("actions")).extracting(action -> action.path("deviceId").asText()).containsExactly("light-2");
    }

    @Test
    void unavailableStateDoesNotRemoveAnyVerifiedFutureMember() throws Exception {
        ScopeAdapter adapter = new ScopeAdapter("exact");
        adapter.stateFailure = new com.ehe.iotagent.iot.UpstreamUnavailableException("fixture state");
        JsonNode proposal = JSON.readTree(facade(adapter).propose(PRINCIPAL, "office", "conversation", "turn",
                request(), "state-unavailable").payloadJson());
        for (JsonNode phase : proposal.path("phases")) {
            assertThat(phase.path("actions")).extracting(action -> action.path("deviceId").asText())
                    .containsExactly("light-1", "light-2", "light-3");
            assertThat(phase.path("preflight").path("unreadableCount").asInt()).isEqualTo(3);
            assertThat(phase.path("preflight").path("alreadySatisfiedCount").asInt()).isZero();
        }
    }

    @Test
    void stateAuthorizationFailureStillRejectsTheProposal() {
        ScopeAdapter adapter = new ScopeAdapter("exact");
        adapter.stateFailure = new com.ehe.iotagent.iot.GatewayAuthorizationException(401);
        assertThatThrownBy(() -> facade(adapter).propose(PRINCIPAL, "office", "conversation", "turn",
                request(), "state-unauthorized"))
                .isInstanceOfSatisfying(com.ehe.iotagent.error.BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("IOT_GATEWAY_UNAUTHORIZED"));
    }

    @Test
    void browseExclusionCannotBeIgnoredOrUsedAsAnExactSubset() throws Exception {
        ScopeAdapter adapter = new ScopeAdapter("exact");
        adapter.subset("excluded", "browse", List.of(adapter.devices.get(1)));
        var input = JSON.readTree(request());
        for (JsonNode phase : input.path("phases")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) phase.path("lighting"))
                    .putArray("excludeCandidateSetIds").add("excluded");
        }
        assertThatThrownBy(() -> facade(adapter).propose(PRINCIPAL, "office", "conversation", "turn",
                input.toString(), "bad-exclusion"))
                .isInstanceOfSatisfying(com.ehe.iotagent.error.BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("TASK_EXCLUSION_UNRESOLVED"));
    }

    @Test
    void exactExclusionLeavesTheSameTwoFixedMembersInBothStages() throws Exception {
        ScopeAdapter adapter = new ScopeAdapter("browse");
        adapter.subset("excluded", "exact", List.of(adapter.devices.get(1)));
        var input = JSON.readTree(request());
        for (JsonNode phase : input.path("phases")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) phase.path("lighting"))
                    .putArray("excludeCandidateSetIds").add("excluded");
        }
        JsonNode proposal = JSON.readTree(facade(adapter).propose(PRINCIPAL, "office", "conversation", "turn",
                input.toString(), "exact-exclusion").payloadJson());
        for (JsonNode phase : proposal.path("phases")) {
            assertThat(phase.path("actions")).extracting(action -> action.path("deviceId").asText())
                    .containsExactly("light-1", "light-3");
            assertThat(phase.path("preflight").path("excludedScopes")).extracting(JsonNode::asText)
                    .containsExactly("过道");
            assertThat(phase.path("preflight").path("excludedCount").asInt()).isEqualTo(1);
        }
    }

    private static String request() {
        return """
                {"label":"工作日办公室照明","candidateSetIds":["s1"],"repeat":{"frequency":"weekdays"},"phases":[
                  {"at":"2026-11-05T10:45:00+08:00","actions":[],"lighting":{"candidateSetId":"s1","closeOrOpen":1}},
                  {"at":"2026-11-05T10:46:00+08:00","actions":[],"lighting":{"candidateSetId":"s1","closeOrOpen":0}}
                ]}
                """;
    }

    private static TaskFacade facade(ScopeAdapter adapter) {
        TaskProposalStore proposals = mock(TaskProposalStore.class);
        when(proposals.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new TaskFacade(JSON, mock(TaskFactStore.class), proposals, mock(TaskPlanStore.class),
                mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, adapter, null, null,
                mock(com.ehe.iotagent.readmodel.ReadSnapshots.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class ScopeAdapter implements IotAdapter {
        final List<AuthorizedDevice> devices = new ArrayList<>();
        final Map<String, DeviceCandidateRecord> records = new LinkedHashMap<>();
        final String mode;
        int nextSet = 1;
        String incapable;
        String scopeText;
        RuntimeException stateFailure;

        ScopeAdapter(String mode) {
            this.mode = mode;
            for (int index = 1; index <= 3; index++) devices.add(new AuthorizedDevice("light-" + index,
                    "lighting", "灯" + index, "26楼运营部公共办公区", false, "control-" + index,
                    "office-" + index, "hub-" + index, "area", List.of(), List.of("region"), Map.of()));
        }

        void subset(String ref, String precision, List<AuthorizedDevice> members) {
            records.put(ref, new DeviceCandidateRecord(ref, "conversation", PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(),
                    IotSourceEnum.OFFICE, "catalog", new DeviceCandidateQuery("lighting", List.of(), "过道", List.of(),
                    List.of(), null, null), members, true, members.size(), NOW.toString(), precision, null));
        }

        @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
            return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), devices));
        }

        @Override public Mono<DeviceCandidateRecord> resolveDeviceCandidateSet(RequestIdentity identity, String ref, String conversation) {
            if (records.containsKey(ref)) return Mono.just(records.get(ref));
            if (!"s1".equals(ref)) return Mono.error(new AssertionError("Unexpected set " + ref));
            DeviceCandidateRecord initial = new DeviceCandidateRecord("initial", conversation, identity.tenantCode(), identity.ownerKey(),
                    identity.source(), "catalog", new DeviceCandidateQuery("lighting", List.of(), scopeText, List.of(), List.of(), null, "region"),
                    devices, true, devices.size(), NOW.toString(), mode, "s1");
            records.put("s1", initial); records.put("initial", initial);
            return Mono.just(initial);
        }

        @Override public Mono<DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity, DeviceCandidateQuery query, DeviceCandidateBinding binding) {
            List<AuthorizedDevice> selected = devices.stream().filter(device -> query.deviceIds().contains(device.id())).toList();
            String id = "fixed-" + nextSet++;
            DeviceCandidateRecord record = new DeviceCandidateRecord(id, binding.conversationId(), identity.tenantCode(), identity.ownerKey(),
                    identity.source(), "catalog", query, selected, true, selected.size(), NOW.toString(), "exact", null);
            records.put(id, record);
            return Mono.just(new DeviceCandidateSet(id, query, selected, true, selected.size()));
        }

        @Override public Mono<DeviceCapabilityInspection> inspectDeviceCapabilities(RequestIdentity identity, DeviceCapabilityRequest request, String conversation) {
            List<DeviceCapabilityInspection.DeviceCapability> capabilities = request.candidateSets().stream()
                    .flatMap(selection -> selection.deviceIds().stream()).distinct().map(id ->
                            new DeviceCapabilityInspection.DeviceCapability(id, "lighting", id.equals(incapable) ? List.of()
                                    : List.of(Map.of("closeOrOpen", 0), Map.of("closeOrOpen", 1)), null)).toList();
            return Mono.just(new DeviceCapabilityInspection(request.candidateSets().stream().map(DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId).toList(), capabilities));
        }

        @Override public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> ids) {
            if (stateFailure != null) return Mono.error(stateFailure);
            Map<String,Object> states = new LinkedHashMap<>(); ids.forEach(id -> states.put(id, false));
            return Mono.just(new DeviceStateReadResult(states, NOW.toString()));
        }
    }
}
