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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class TaskRangeTargetContractTest {
    private static final Instant NOW = Instant.parse("2026-11-02T05:00:00Z");
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "TENANT", "owner", "fixture");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void wholeCompanyFutureLightingContainsAll156MembersNotTwoRepresentatives() throws Exception {
        TaskProposalFact proposal = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"全楼照明关闭","phases":[{"at":"2026-11-02T05:03:00Z","targets":[
                  {"kind":"lighting","allAuthorized":true,"command":{"closeOrOpen":0}}
                ]}]}
                """, "all-lights");
        JsonNode phase = mapper.readTree(proposal.payloadJson()).path("phases").get(0);
        assertThat(phase.path("actions")).hasSize(156);
        List<String> ids = new ArrayList<>();
        for (JsonNode action : phase.path("actions")) {
            ids.add(action.path("deviceId").asText());
            assertThat(action.path("command").path("closeOrOpen").asInt(-1)).isZero();
        }
        assertThat(ids).doesNotHaveDuplicates().contains("light-1", "light-81", "light-82", "light-156")
                .doesNotContain("ac-1");
        assertThat(proposal.status()).isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void fullScopeHonorsAnExplicitExcludedLocation() throws Exception {
        TaskProposalFact proposal = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"关灯但保留27楼","phases":[{"at":"2026-11-02T05:03:00Z","targets":[
                  {"kind":"lighting","allAuthorized":true,"excludeTexts":["27楼"],"command":{"closeOrOpen":0}}
                ]}]}
                """, "exclude-floor");
        assertThat(mapper.readTree(proposal.payloadJson()).path("phases").get(0).path("actions")).hasSize(81);
    }

    @Test
    void preservesDifferentDeviceFamiliesAndScopesInTwoFuturePhases() throws Exception {
        TaskProposalFact proposal = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"分阶段关闭","phases":[
                  {"at":"2026-11-02T05:03:00Z","targets":[{"kind":"lighting","scopeTexts":["26楼"],"command":{"closeOrOpen":0}}]},
                  {"at":"2026-11-02T05:05:00Z","targets":[{"kind":"ac","scopeTexts":["27楼"],"command":{"power":"off"}}]}
                ]}
                """, "two-phases");
        JsonNode phases = mapper.readTree(proposal.payloadJson()).path("phases");
        assertThat(phases).hasSize(2);
        assertThat(phases.get(0).path("actions")).hasSize(81);
        assertThat(phases.get(1).path("actions")).hasSize(1);
        assertThat(phases.get(1).path("actions").get(0).path("deviceId").asText()).isEqualTo("ac-1");
        assertThat(phases.get(1).path("actions").get(0).path("command").path("power").asText()).isEqualTo("off");
    }

    @Test
    void rejectsForgedCandidateReferenceEvenWhenActionDeviceIsAuthorized() {
        assertThatThrownBy(() -> facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"点选灯","candidateSetIds":["foreign-or-invented"],"phases":[
                  {"at":"2026-11-02T05:03:00Z","actions":[{"deviceId":"light-1","closeOrOpen":0}]}
                ]}
                """, "forged-set")).isInstanceOf(BusinessException.class);
    }

    @Test
    void explicitReadAllBindsTheWholeFamilyWithoutInventingControls() throws Exception {
        var saved = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"全公司空调只读巡检","phases":[{"at":"2026-11-02T05:03:00Z",
                  "read":{"all":true,"ac":{"mode":"inspect","target":26}}}]}
                """, "read-all");
        JsonNode phase = mapper.readTree(saved.payloadJson()).path("phases").get(0);
        assertThat(phase.path("actions")).isEmpty();
        assertThat(phase.path("read").path("devices")).hasSize(1);
        assertThat(phase.path("read").path("devices").get(0).path("id").asText()).isEqualTo("ac-1");
    }

    @Test
    void readOnlyAgentAssignmentBindsAllMetersAndRetainsConfirmedOutputAndPeriod() throws Exception {
        var saved = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"明日读取昨日用电简报","phases":[{"at":"2026-11-02T05:03:00Z",
                  "read":{"all":true,"agent":{"objective":"统计昨日用电并保留原采样窗口","output":"result",
                    "spec":{"period":{"kind":"previous","unit":"day"},"comparisons":[]}}}}]}
                """, "read-energy-all");
        JsonNode phase = mapper.readTree(saved.payloadJson()).path("phases").get(0);
        assertThat(phase.path("read").path("devices")).hasSize(75);
        assertThat(phase.path("read").path("agent").path("output").asText()).isEqualTo("result");
        assertThat(phase.path("read").path("agent").path("spec").path("period").path("unit").asText()).isEqualTo("day");
        assertThat(phase.path("actions")).isEmpty();
    }

    @Test
    void omittedReadMembersAreNotInterpretedAsAllAndForgedScopeFactsCannotBypassCandidateValidation() {
        assertThatThrownBy(() -> facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"遗漏范围","phases":[{"at":"2026-11-02T05:03:00Z","read":{"ac":{"mode":"inspect"}}}]}
                """, "missing-scope")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"伪造范围证明","candidateSetIds":["foreign"],"phases":[{"at":"2026-11-02T05:03:00Z",
                  "scopeFacts":{"allAuthorized":true},"actions":[{"deviceId":"light-1","closeOrOpen":0}]}]}
                """, "forged-scope-proof")).isInstanceOf(BusinessException.class);
    }

    @Test
    void lightingCandidateRangeRemovesOnlyTheExactExcludedMembers() throws Exception {
        AuthorizedDevice corridor1 = new AuthorizedDevice("light-157", "lighting", "过道灯1", "26楼过道", false);
        AuthorizedDevice corridor2 = new AuthorizedDevice("light-158", "lighting", "过道灯2", "26楼过道", false);
        RangeAdapter adapter = new RangeAdapter(corridor1, corridor2);
        adapter.seed("exclude-corridor", "26楼过道", List.of(corridor1, corridor2), "exact", 2);
        String includedId = discoverLighting(adapter, "26楼");
        TaskProposalFact proposal = facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"关26楼但保留过道","candidateSetIds":["%s"],
                 "phases":[{"at":"2026-11-02T05:03:00Z","actions":[],
                  "lighting":{"candidateSetId":"%s","excludeCandidateSetIds":["exclude-corridor"],"closeOrOpen":0}
                }]}
                """.formatted(includedId, includedId), "exclude-set");
        JsonNode phase = mapper.readTree(proposal.payloadJson()).path("phases").get(0);
        assertThat(phase.path("actions")).hasSize(81);
        for (JsonNode action : phase.path("actions")) {
            assertThat(action.path("deviceId").asText()).isNotIn("light-157", "light-158");
        }
    }

    @Test
    void lightingCandidateExclusionsRejectForeignOrUnresolvedSubsets() {
        AuthorizedDevice corridor = new AuthorizedDevice("light-157", "lighting", "过道灯1", "26楼过道", false);
        AuthorizedDevice otherFloor = new AuthorizedDevice("light-82", "lighting", "灯82", "27楼会议室", false);
        RangeAdapter adapter = new RangeAdapter(corridor);
        adapter.seed("exclude-other-floor", "27楼会议室", List.of(otherFloor), "exact", 1);
        adapter.seed("exclude-fuzzy", "过道", List.of(corridor), "partial", 5);
        String includedId = discoverLighting(adapter, "26楼");
        TaskFacade facade = facade(adapter);
        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"排除不属于包含范围","candidateSetIds":["%s"],
                 "phases":[{"at":"2026-11-02T05:03:00Z","actions":[],
                  "lighting":{"candidateSetId":"%s","excludeCandidateSetIds":["exclude-other-floor"],"closeOrOpen":0}
                }]}
                """.formatted(includedId, includedId), "exclude-foreign")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"排除集不精确","candidateSetIds":["%s"],
                 "phases":[{"at":"2026-11-02T05:03:00Z","actions":[],
                  "lighting":{"candidateSetId":"%s","excludeCandidateSetIds":["exclude-fuzzy"],"closeOrOpen":0}
                }]}
                """.formatted(includedId, includedId), "exclude-fuzzy")).isInstanceOf(BusinessException.class);
    }

    @Test
    void readOnlyCheckpointsNeedExplicitMembersEvenAlongsideDeclarativeReportRequest() {
        assertThatThrownBy(() -> facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"简报","reportRequest":{"kind":"energy","period":{"kind":"previous","unit":"day"}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"agent":{"objective":"昨日用电简报","output":"result"}}}]}
                """, "agent-no-members")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"安全周报","reportRequest":{"kind":"safety","period":{"kind":"previous","unit":"week"}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"alertWindow":"previous_week",
                   "devices":[]}}]}
                """, "safety-no-members")).isInstanceOf(BusinessException.class);
    }

    @Test
    void energyReportTaskIsPinnedToTheVerifiedReadMembersNotTheLiveCatalog() throws Exception {
        TaskProposalFact proposal = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"两表昨日报告","reportRequest":{"kind":"energy","period":{"kind":"previous","unit":"day"}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"deviceIds":["meter-3","meter-7"]}}]}
                """, "pin-energy");
        JsonNode report = mapper.readTree(proposal.payloadJson()).path("reportRequest");
        assertThat(report.path("scope").path("level").asText()).isEqualTo("selection");
        List<String> pinned = new ArrayList<>();
        report.path("scope").path("meterIds").forEach(id -> pinned.add(id.asText()));
        assertThat(pinned).containsExactly("meter-3", "meter-7");

        TaskProposalFact equivalent = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"同集合报告","reportRequest":{"kind":"energy","period":{"kind":"previous","unit":"day"},
                 "scope":{"level":"selection","meterIds":["meter-7","meter-3"]}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"deviceIds":["meter-3","meter-7"]}}]}
                """, "pin-energy-equal");
        JsonNode canonical = mapper.readTree(equivalent.payloadJson()).path("reportRequest").path("scope");
        assertThat(canonical.path("meterIds").get(0).asText()).isEqualTo("meter-3");
        assertThat(canonical.path("meterIds").get(1).asText()).isEqualTo("meter-7");
    }

    @Test
    void energyReportScopeConflictingWithTheReadMembersIsRejected() {
        assertThatThrownBy(() -> facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"窄范围报告","reportRequest":{"kind":"energy","period":{"kind":"previous","unit":"day"},
                 "scope":{"level":"selection","meterIds":["meter-3"]}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"deviceIds":["meter-3","meter-7"]}}]}
                """, "pin-energy-narrow")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"全量报告","reportRequest":{"kind":"energy","period":{"kind":"previous","unit":"day"},
                 "scope":{"level":"all"}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"deviceIds":["meter-3","meter-7"]}}]}
                """, "pin-energy-all")).isInstanceOf(BusinessException.class);
    }

    @Test
    void readAllEnergyReportRewritesLevelAllToTheFixedSelection() throws Exception {
        TaskProposalFact proposal = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"全量日报","reportRequest":{"kind":"energy","period":{"kind":"previous","unit":"day"},
                 "scope":{"level":"all"}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"all":true,
                   "agent":{"objective":"统计昨日全部用电","output":"report"}}}]}
                """, "pin-energy-full");
        JsonNode scope = mapper.readTree(proposal.payloadJson()).path("reportRequest").path("scope");
        assertThat(scope.path("level").asText()).isEqualTo("selection");
        assertThat(scope.path("meterIds")).hasSize(75);
    }

    @Test
    void safetyReportTaskCarriesItsFixedMembersIntoTheReportRequest() throws Exception {
        AuthorizedDevice smoke = new AuthorizedDevice("safe-1", "safety", "烟感1", "26楼", true);
        AuthorizedDevice flood = new AuthorizedDevice("safe-2", "safety", "水浸2", "27楼", true);
        RangeAdapter adapter = new RangeAdapter(smoke, flood);
        TaskProposalFact pinned = facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"烟感周报","reportRequest":{"kind":"safety","period":{"kind":"previous","unit":"week"}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"alertWindow":"previous_week","deviceIds":["safe-1"]}}]}
                """, "pin-safety");
        JsonNode fixed = mapper.readTree(pinned.payloadJson()).path("reportRequest").path("fixedDeviceIds");
        assertThat(fixed).hasSize(1);
        assertThat(fixed.get(0).asText()).isEqualTo("safe-1");

        TaskProposalFact whole = facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"全部安全周报","reportRequest":{"kind":"safety","period":{"kind":"previous","unit":"week"}},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"all":true,"alertWindow":"previous_week"}}]}
                """, "pin-safety-all");
        JsonNode all = mapper.readTree(whole.payloadJson()).path("reportRequest").path("fixedDeviceIds");
        assertThat(all).hasSize(2);
        assertThat(all.get(0).asText()).isEqualTo("safe-1");
        assertThat(all.get(1).asText()).isEqualTo("safe-2");

        assertThatThrownBy(() -> facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"伪造固定成员","reportRequest":{"kind":"safety","period":{"kind":"previous","unit":"week"},
                 "fixedDeviceIds":["safe-1"]},
                 "phases":[{"at":"2026-11-02T05:03:00Z","read":{"alertWindow":"previous_week","deviceIds":["safe-1"]}}]}
                """, "pin-safety-forged")).isInstanceOf(BusinessException.class);
    }

    @Test
    void rootLevelReadUsesTheSameFixedMemberRules() throws Exception {
        var saved = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"根级精确只读","at":"2026-11-02T05:03:00Z","read":{"deviceIds":["meter-3"]},
                 "reportRequest":{"kind":"energy","period":{"kind":"previous","unit":"day"}}}
                """, "root-read");
        var ids = mapper.readTree(saved.payloadJson()).path("reportRequest").path("scope").path("meterIds");
        assertThat(ids).hasSize(1);
        assertThat(ids.get(0).asText()).isEqualTo("meter-3");
        var adapter = new RangeAdapter(
                new AuthorizedDevice("safe-a", "safety", "烟感A", "26楼", true),
                new AuthorizedDevice("safe-b", "safety", "烟感B", "27楼", true));
        assertThatThrownBy(() -> facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"根级伪造固定成员","at":"2026-11-02T05:03:00Z",
                 "read":{"alertWindow":"previous_week","deviceIds":["safe-a"]},
                 "reportRequest":{"kind":"safety","fixedDeviceIds":["safe-b"],
                   "period":{"kind":"previous","unit":"week"}}}
                """, "root-forged-safety")).isInstanceOf(BusinessException.class);
    }

    @Test
    void equivalentFloorAndNameScopesArePinnedToTheExactReadSet() throws Exception {
        for (var declared : List.of(
                Map.of("level", "floor", "floor", "26楼"),
                Map.of("level", "nameMatch", "floor", "26楼", "text", "电表"))) {
            var request = mapper.createObjectNode().put("label", "等价范围");
            var report = request.putObject("reportRequest").put("kind", "energy");
            report.putObject("period").put("kind", "previous").put("unit", "day");
            report.set("scope", mapper.valueToTree(declared));
            var ids = request.putArray("phases").addObject().put("at", "2026-11-02T05:03:00Z")
                    .putObject("read").putArray("deviceIds");
            for (int i = 1; i <= 75; i++) ids.add("meter-" + i);
            var saved = facade().propose(PRINCIPAL, "company", "conversation", "turn",
                    request.toString(), "equivalent-" + declared.get("level"));
            var pinned = mapper.readTree(saved.payloadJson()).path("reportRequest").path("scope");
            assertThat(pinned.path("level").asText()).isEqualTo("selection");
            assertThat(pinned.path("meterIds")).isEqualTo(ids);
            ids.remove(0);
            assertThatThrownBy(() -> facade().propose(PRINCIPAL, "company", "conversation", "turn",
                    request.toString(), "conflict-" + declared.get("level"))).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void targetPreflightRecordsSatisfiedAndUnreadableWithoutDroppingFutureMembers() throws Exception {
        RangeAdapter adapter = new RangeAdapter();
        adapter.state("light-1", false);
        adapter.unreadable("light-2");
        TaskProposalFact proposal = facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"全楼关灯","phases":[{"at":"2026-11-02T05:03:00Z","targets":[
                  {"kind":"lighting","allAuthorized":true,"command":{"closeOrOpen":0}}
                ]}]}
                """, "preflight-counts");
        JsonNode phase = mapper.readTree(proposal.payloadJson()).path("phases").get(0);
        assertThat(phase.path("actions")).hasSize(156);
        JsonNode preflight = phase.path("preflight");
        assertThat(preflight.path("targetCount").asInt()).isEqualTo(156);
        assertThat(preflight.path("alreadySatisfiedCount").asInt()).isEqualTo(1);
        assertThat(preflight.path("unreadableCount").asInt()).isEqualTo(1);
        assertThat(preflight.path("needsDispatchCount").asInt()).isEqualTo(154);
    }

    @Test
    void unavailableCurrentStateRetainsEveryVerifiedFutureTarget() throws Exception {
        RangeAdapter adapter = new RangeAdapter();
        adapter.stateFailure = new com.ehe.iotagent.iot.UpstreamUnavailableException("state");
        var proposal = facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"状态未知仍保留未来目标","phases":[{"at":"2026-11-02T05:03:00Z","targets":[
                  {"kind":"lighting","allAuthorized":true,"command":{"closeOrOpen":0}}
                ]}]}
                """, "unavailable-state");
        JsonNode phase = mapper.readTree(proposal.payloadJson()).path("phases").get(0);
        assertThat(phase.path("actions")).hasSize(156);
        assertThat(phase.path("preflight").path("unreadableCount").asInt()).isEqualTo(156);
        assertThat(phase.path("preflight").path("alreadySatisfiedCount").asInt()).isZero();
        assertThat(phase.path("preflight").path("needsDispatchCount").asInt()).isZero();
        assertThat(phase.path("preflight").path("observedAt").asText()).isEmpty();
    }

    @Test
    void stateAuthorizationFailureCannotBecomeUnknownPreflight() {
        RangeAdapter adapter = new RangeAdapter();
        adapter.stateFailure = new com.ehe.iotagent.iot.GatewayAuthorizationException(401);
        assertThatThrownBy(() -> facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"鉴权失败不提案","phases":[{"at":"2026-11-02T05:03:00Z","targets":[
                  {"kind":"lighting","allAuthorized":true,"command":{"closeOrOpen":0}}
                ]}]}
                """, "unauthorized-state"))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.code()).isEqualTo("IOT_GATEWAY_UNAUTHORIZED"));
    }

    @Test
    void targetWithoutVerifiedControlCapabilityRejectsTheProposal() {
        RangeAdapter adapter = new RangeAdapter();
        adapter.incapable("light-5");
        assertThatThrownBy(() -> facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"含不可控目标","phases":[{"at":"2026-11-02T05:03:00Z","targets":[
                  {"kind":"lighting","allAuthorized":true,"command":{"closeOrOpen":0}}
                ]}]}
                """, "preflight-incapable")).isInstanceOf(BusinessException.class);
    }

    @Test
    void controllableKindWithoutAControlBindingCannotBecomeAFutureTarget() {
        var unbound = new AuthorizedDevice("unbound", "lighting", "未接线照明", "26楼", false);
        assertThatThrownBy(() -> facade(new RangeAdapter(unbound)).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"缺控制绑定","phases":[{"at":"2026-11-02T05:03:00Z","targets":[
                  {"kind":"lighting","allAuthorized":true,"command":{"closeOrOpen":0}}
                ]}]}
                """, "missing-control-binding")).isInstanceOf(BusinessException.class);
    }

    @Test
    void preciseActionMayReferenceACompletePersistedSetWhosePresentationIsBounded() throws Exception {
        var adapter = new RangeAdapter();
        var identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSourceEnum.COMPANY, PRINCIPAL.ownerKey());
        var found = adapter.findDeviceCandidates(identity,
                new com.ehe.iotagent.iot.DeviceCandidateQuery("lighting", List.of(), "26楼", List.of(), List.of(), null, null),
                new com.ehe.iotagent.iot.DeviceCandidateBinding("conversation")).block();
        assertThat(found.complete()).isFalse();
        var request = mapper.createObjectNode().put("label", "只选一台");
        request.putArray("candidateSetIds").add(found.candidateSetId());
        request.putArray("phases").addObject().put("at", "2026-11-02T05:03:00Z")
                .putArray("actions").addObject().put("deviceId", "light-1").put("closeOrOpen", 0);
        var saved = facade(adapter).propose(PRINCIPAL, "company", "conversation", "turn",
                request.toString(), "precise-from-large-set");
        var actions = mapper.readTree(saved.payloadJson()).path("phases").get(0).path("actions");
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).path("deviceId").asText()).isEqualTo("light-1");
    }

    @Test
    void canonicalCommandsStoreTheValidatedIntegralTargetWithoutPowerAliases() throws Exception {
        var saved = facade(new RangeAdapter()).propose(PRINCIPAL, "company", "conversation", "turn",
                """
                {"label":"规范目标","phases":[{"at":"2026-11-02T05:03:00Z","actions":[
                  {"deviceId":"light-1","command":{"power":"on"}}
                ]}]}
                """, "canonical-power-on");
        var command = mapper.readTree(saved.payloadJson()).path("phases").get(0).path("actions").get(0).path("command");
        assertThat(command.path("closeOrOpen").asInt(-1)).isEqualTo(1);
        assertThat(command.has("power")).isFalse();
    }

    private String discoverLighting(RangeAdapter adapter, String location) {
        var identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSourceEnum.COMPANY, PRINCIPAL.ownerKey());
        return adapter.findDeviceCandidates(identity,
                new com.ehe.iotagent.iot.DeviceCandidateQuery("lighting", List.of(), location, List.of(), List.of(), null, null),
                new com.ehe.iotagent.iot.DeviceCandidateBinding("conversation")).block().candidateSetId();
    }

    private TaskFacade facade() {
        return facade(new RangeAdapter());
    }

    private TaskFacade facade(RangeAdapter adapter) {
        TaskProposalStore store = mock(TaskProposalStore.class);
        when(store.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(store.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new TaskFacade(mapper, mock(TaskFactStore.class), store, mock(TaskPlanStore.class),
                mock(TaskNotificationStore.class), mock(TaskScheduler.class), null, adapter, null, null,
                mock(com.ehe.iotagent.readmodel.ReadSnapshots.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Company catalog fixture: 156 lights on two floors, one AC, 75 meters, plus per-test extras. */
    private static final class RangeAdapter implements IotAdapter {
        private final List<AuthorizedDevice> extra;
        private record Seed(String scopeText, List<AuthorizedDevice> members, String resolutionMode, int totalMatches) { }
        private final java.util.Map<String, Seed> seeds = new java.util.HashMap<>();
        private final java.util.Map<String, com.ehe.iotagent.iot.DeviceCandidateRecord> candidates = new java.util.HashMap<>();

        RangeAdapter(AuthorizedDevice... extra) { this.extra = List.of(extra); }

        void seed(String id, String scopeText, List<AuthorizedDevice> members, String resolutionMode, int totalMatches) {
            seeds.put(id, new Seed(scopeText, List.copyOf(members), resolutionMode, totalMatches));
        }

        private final java.util.Set<String> incapable = new java.util.HashSet<>();
        private final java.util.Map<String, Object> stateOverrides = new java.util.HashMap<>();
        private final java.util.Set<String> unreadable = new java.util.HashSet<>();
        private RuntimeException stateFailure;

        void incapable(String id) { incapable.add(id); }

        void state(String id, Object state) { stateOverrides.put(id, state); }

        void unreadable(String id) { unreadable.add(id); }

        @Override public Mono<com.ehe.iotagent.iot.DeviceStateReadResult> readDeviceStates(
                RequestIdentity identity, List<String> deviceIds) {
            if (stateFailure != null) return Mono.error(stateFailure);
            java.util.Map<String, Object> states = new java.util.LinkedHashMap<>();
            for (String id : deviceIds) {
                if (unreadable.contains(id)) continue;
                if (stateOverrides.containsKey(id)) {
                    states.put(id, stateOverrides.get(id));
                    continue;
                }
                AuthorizedDevice device = loadAuthorizedDeviceScope(identity).block()
                        .device(id).orElse(null);
                if (device == null) continue;
                if ("lighting".equals(device.kind())) states.put(id, Boolean.TRUE);
                else if ("ac".equals(device.kind())) {
                    states.put(id, java.util.Map.of("power", "on", "mode", "cool", "temperature", 24));
                }
            }
            return Mono.just(new com.ehe.iotagent.iot.DeviceStateReadResult(states, NOW.toString()));
        }

        @Override public Mono<com.ehe.iotagent.iot.DeviceCapabilityInspection> inspectDeviceCapabilities(
                RequestIdentity identity, com.ehe.iotagent.iot.DeviceCapabilityRequest request) {
            List<com.ehe.iotagent.iot.DeviceCapabilityInspection.DeviceCapability> capabilities = new ArrayList<>();
            var scope = loadAuthorizedDeviceScope(identity).block();
            for (var selection : request.candidateSets()) {
                for (String id : selection.deviceIds()) {
                    AuthorizedDevice device = scope.device(id).orElse(null);
                    if (device == null) continue;
                    List<java.util.Map<String, Object>> commands = new ArrayList<>();
                    if ("lighting".equals(device.kind()) && !incapable.contains(id)) {
                        commands.add(java.util.Map.of("closeOrOpen", 0));
                        commands.add(java.util.Map.of("closeOrOpen", 1));
                    }
                    com.ehe.iotagent.iot.DeviceCapabilityInspection.MapParameters parameters =
                            "ac".equals(device.kind()) && !device.observationOnly()
                                    ? new com.ehe.iotagent.iot.DeviceCapabilityInspection.MapParameters(
                                            new com.ehe.iotagent.iot.DeviceCapabilityInspection.Temperature(16, 30, 1),
                                            List.of("cool", "heat", "dry", "fan"), List.of("low", "high"))
                                    : null;
                    capabilities.add(new com.ehe.iotagent.iot.DeviceCapabilityInspection.DeviceCapability(
                            id, device.kind(), commands, parameters));
                }
            }
            return Mono.just(new com.ehe.iotagent.iot.DeviceCapabilityInspection(
                    request.candidateSets().stream()
                            .map(com.ehe.iotagent.iot.DeviceCapabilityRequest.DeviceCapabilitySelection::candidateSetId)
                            .toList(),
                    capabilities));
        }

        @Override public Mono<com.ehe.iotagent.iot.DeviceCandidateSet> findDeviceCandidates(RequestIdentity identity,
                com.ehe.iotagent.iot.DeviceCandidateQuery query, com.ehe.iotagent.iot.DeviceCandidateBinding binding) {
            var scope = loadAuthorizedDeviceScope(identity).block();
            var members = scope.devices().stream().filter(d -> d.kind().equals(query.kind()))
                    .filter(d -> query.deviceIds().isEmpty() || query.deviceIds().contains(d.id()))
                    .filter(d -> query.scopeText() == null || d.installAddress().contains(query.scopeText())).toList();
            String id = "scope-" + query.kind() + "-" + query.scopeText();
            candidates.put(id, new com.ehe.iotagent.iot.DeviceCandidateRecord(id, binding.conversationId(), identity.tenantCode(), identity.ownerKey(), identity.source(), scope.catalogFingerprint(), query, members, members.size() <= 50, members.size(), NOW.toString(), "exact", null));
            return Mono.just(new com.ehe.iotagent.iot.DeviceCandidateSet(id, query,
                    members.stream().limit(50).toList(), members.size() <= 50, members.size(),
                    null, List.of(), List.of(), null, members));
        }

        @Override public Mono<com.ehe.iotagent.iot.DeviceCandidateRecord> resolveDeviceCandidateSet(
                RequestIdentity identity, String id, String conversation) {
            var record = candidates.get(id);
            if (record == null) {
                Seed seed = seeds.get(id);
                if (seed != null) {
                    record = new com.ehe.iotagent.iot.DeviceCandidateRecord(id, conversation, identity.tenantCode(), identity.ownerKey(), identity.source(), "seeded-catalog", new com.ehe.iotagent.iot.DeviceCandidateQuery("lighting", List.of(), seed.scopeText(),
                            List.of(), List.of(), null, null), seed.members(), seed.members().size() <= seed.totalMatches(), seed.totalMatches(), NOW.toString(), seed.resolutionMode(), null);
                }
            }
            if (record == null || !record.conversationId().equals(conversation)) {
                return Mono.error(new BusinessException("CANDIDATE_SET_NOT_FOUND", "候选不存在", 404));
            }
            return Mono.just(record);
        }

        @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
            List<AuthorizedDevice> devices = new ArrayList<>();
            for (int n = 1; n <= 156; n++) devices.add(boundDevice("light-" + n, "lighting",
                    "灯" + n, n <= 81 ? "26楼办公室" : "27楼会议室"));
            devices.add(boundDevice("ac-1", "ac", "空调", "27楼会议室"));
            for (int n = 1; n <= 75; n++) devices.add(new AuthorizedDevice("meter-" + n, "energy", "电表" + n, "26楼", true));
            devices.addAll(extra);
            return Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY, devices));
        }

        private static AuthorizedDevice boundDevice(String id, String kind, String name, String location) {
            return new AuthorizedDevice(id, kind, name, location, false, "control-" + id,
                    id, null, null, List.of(), List.of(), java.util.Map.of());
        }
    }
}
