package ltd.qianc.iotagent.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;

class ActionDraftProposalContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "TENANT");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private static final String CONVERSATION = "conversation-1";

    private static final List<AuthorizedDevice> LIGHTS = List.of(
            light("light-1", "灯1", "26楼运营办公区", List.of("region-1")),
            light("light-2", "灯2", "26楼运营办公区", List.of("region-1")),
            light("corridor", "过道灯", "26楼过道", List.of("region-1")),
            light("other-region", "其他区域灯", "25楼", List.of("region-2")));

    private final Map<String, Object> states = Map.of(
            "light-1", true, "light-2", false, "corridor", false, "other-region", false);

    @Test
    void newExpressionRetiresOnlyOwnedUnconfirmedCardsAndPreservesExecutingActions() throws Exception {
        InMemoryActionDraftStore store = new InMemoryActionDraftStore();
        for (String status : List.of("PENDING_CONFIRMATION", "EXPIRED", "EXECUTING", "ACCEPTED")) {
            ObjectNode body = MAPPER.createObjectNode().put("tenantCode", PRINCIPAL.tenantCode())
                    .put("ownerKey", PRINCIPAL.ownerKey()).put("source", "office")
                    .put("conversationId", CONVERSATION).put("status", status);
            store.create(new ActionDraftFact(status, 7, status, body.toString(), status));
        }
        ObjectNode foreign = MAPPER.createObjectNode().put("tenantCode", PRINCIPAL.tenantCode())
                .put("ownerKey", "other-owner").put("source", "office")
                .put("conversationId", CONVERSATION).put("status", "PENDING_CONFIRMATION");
        store.create(new ActionDraftFact("foreign", 7, "PENDING_CONFIRMATION", foreign.toString(), "foreign"));
        ActionDraftFacade facade = new ActionDraftFacade(MAPPER, store,
                org.mockito.Mockito.mock(ltd.qianc.iotagent.iot.IotAdapter.class), null,
                org.mockito.Mockito.mock(ActionCommandGateway.class), null, null, null, null,
                org.mockito.Mockito.mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class),
                CLOCK, Duration.ofSeconds(1), Duration.ofMinutes(10), null);
        assertThat(facade.retireForConversation(PRINCIPAL, CONVERSATION, "office"))
                .containsExactly("PENDING_CONFIRMATION", "EXPIRED");
        assertThat(store.find("PENDING_CONFIRMATION").orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(store.find("PENDING_CONFIRMATION").orElseThrow().revision()).isEqualTo(8);
        assertThat(store.find("EXECUTING").orElseThrow().status()).isEqualTo("EXECUTING");
        assertThat(store.find("ACCEPTED").orElseThrow().status()).isEqualTo("ACCEPTED");
        assertThat(store.find("foreign").orElseThrow().status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(facade.confirm(PRINCIPAL, "PENDING_CONFIRMATION", 7L).status()).isEqualTo("CANCELLED");
        assertThat(facade.retireForConversation(PRINCIPAL, CONVERSATION, "office")).isEmpty();
    }

    @Test
    void expandsFixedMembersSubtractsCorridorAndSeparatesCounts() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        InMemoryActionDraftStore drafts = new InMemoryActionDraftStore();
        DeviceCandidateRecord set = set("set-lighting", "lighting", LIGHTS.subList(0, 3), iot);
        ActionDraftFacade facade = facade(drafts, new InMemoryCandidateStore(set), iot);

        ActionDraftFact draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-1",
                payload(Map.of(
                        "candidateSetIds", List.of("set-lighting"),
                        "expandCandidates", true,
                        "excludedDeviceIds", List.of("corridor"),
                        "summary", "打开当前位置的灯",
                        "question", "是否打开这些灯？",
                        "actions", List.of(Map.of("deviceId", "light-2", "kind", "lighting",
                                "closeOrOpen", 1, "selected", true)))), "idem-1");

        Map<String, Object> view = facade.view(draft);
        Map<?, ?> counts = (Map<?, ?>) view.get("counts");
        assertThat(counts.get("total")).isEqualTo(3);
        assertThat(counts.get("operate")).isEqualTo(1);
        assertThat(counts.get("excluded")).isEqualTo(1);
        assertThat(counts.get("satisfied")).isEqualTo(1);
        assertThat(counts.get("untouched")).isEqualTo(0);
        List<Map<String, Object>> actions = actions(view);
        assertThat(actions).extracting(action -> action.get("deviceId"))
                .containsExactly("light-1", "light-2");
        Map<String, Object> on = actions.get(0);
        assertThat(on).containsEntry("deviceName", "灯1").containsEntry("location", "26楼运营办公区")
                .containsEntry("satisfied", true).containsEntry("beforeState", true);
        assertThat(on.get("command")).isEqualTo(Map.of("closeOrOpen", 1));
        Map<String, Object> off = actions.get(1);
        assertThat(off).containsEntry("beforeState", false).containsEntry("satisfied", false);
        assertThat(view.get("selectedActionIds")).isEqualTo(List.of(off.get("id")));

        List<Map<String, Object>> inventory = inventory(view);
        assertThat(inventory).extracting(row -> row.get("disposition"))
                .containsExactly("none", "operate", "excluded");
        assertThat(inventory.get(0)).containsEntry("satisfied", true);
    }

    @Test
    void explicitActionsDoNotExpandAWholeCandidateUniverse() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(),
                new InMemoryCandidateStore(set("all", "lighting", LIGHTS, iot)), iot);
        var draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn", payload(Map.of(
                "candidateSetIds", List.of("all"), "summary", "只操作点名两台",
                "actions", List.of(Map.of("deviceId", "light-1", "closeOrOpen", 1),
                        Map.of("deviceId", "light-2", "closeOrOpen", 1)))), "precise-two");
        assertThat(actions(facade.view(draft))).extracting(row -> row.get("deviceId"))
                .containsExactly("light-1", "light-2");
        assertThat(inventory(facade.view(draft))).filteredOn(row -> "none".equals(row.get("disposition")))
                .extracting(row -> row.get("deviceId")).contains("corridor", "other-region");
    }

    @Test
    void subtractsOverlappingExactSubsetsWithoutA50MemberTransportLimit() throws Exception {
        List<AuthorizedDevice> devices = new java.util.ArrayList<>();
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        for (int n = 0; n < 120; n++) {
            devices.add(light("light-" + n, "灯" + n, "26楼", List.of("region-1")));
            state.put("light-" + n, false);
        }
        TestIotAdapter iot = new TestIotAdapter(devices, identity -> state);
        RequestIdentity identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSourceEnum.OFFICE, PRINCIPAL.ownerKey());
        String fingerprint = AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, devices).catalogFingerprint();
        var query = new DeviceCandidateQuery("lighting", List.of(), "26楼", List.of(), List.of(), null, null);
        var all = new DeviceCandidateRecord("all", CONVERSATION, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), IotSourceEnum.OFFICE, fingerprint, query, devices, false, devices.size(), CLOCK.instant().toString(), "exact", null);
        var excluded = new DeviceCandidateRecord("excluded", CONVERSATION, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), IotSourceEnum.OFFICE, fingerprint, query, devices.subList(0, 75), false, 75, CLOCK.instant().toString(), "exact", null);
        var overlapping = new DeviceCandidateRecord("overlap", CONVERSATION, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), IotSourceEnum.OFFICE, fingerprint, query, devices.subList(0, 25), true, 25, CLOCK.instant().toString(), "exact", null);
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(), new InMemoryCandidateStore(all, excluded, overlapping), iot);
        var draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn", payload(Map.of(
                "candidateSetIds", List.of("all"), "expandCandidates", true,
                "excludeCandidateSetIds", List.of("excluded", "overlap"), "summary", "保留排除子集",
                "actions", List.of(Map.of("deviceId", "light-119", "closeOrOpen", 1)))), "large-exclusion");
        var view = facade.view(draft);
        assertThat(actions(view)).hasSize(45).extracting(row -> row.get("deviceId"))
                .contains("light-75", "light-119").doesNotContain("light-0", "light-74");
        assertThat(((Map<?, ?>) view.get("counts")).get("excluded")).isEqualTo(75);
    }

    @Test
    void rejectsEmptyFuzzyAndOutsideExclusionsRatherThanPreparingAPartialDraft() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var all = set("all", "lighting", LIGHTS.subList(0, 3), iot);
        for (String mode : List.of("exact", "fuzzy", "unresolved", "browse")) {
            List<AuthorizedDevice> members = "exact".equals(mode) ? List.of() : List.of(LIGHTS.get(2));
            var excluded = new DeviceCandidateRecord("excluded", CONVERSATION, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), IotSourceEnum.OFFICE, all.catalogFingerprint(), all.query(), members, true, members.size(), CLOCK.instant().toString(), mode, null);
            var drafts = new InMemoryActionDraftStore();
            ActionDraftFacade facade = facade(drafts, new InMemoryCandidateStore(all, excluded), iot);
            String body = payload(Map.of("candidateSetIds", List.of("all"), "expandCandidates", true,
                    "excludeCandidateSetIds", List.of("excluded"),
                    "actions", List.of(Map.of("deviceId", "light-1", "closeOrOpen", 0))));
            assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", CONVERSATION, "turn", body, mode))
                    .isInstanceOf(BusinessException.class);
            assertThat(drafts.findByConversation(CONVERSATION)).isEmpty();
        }
        var drafts = new InMemoryActionDraftStore();
        ActionDraftFacade facade = facade(drafts, new InMemoryCandidateStore(all,
                set("outside", "lighting", List.of(LIGHTS.get(3)), iot)), iot);
        String body = payload(Map.of("candidateSetIds", List.of("all"), "expandCandidates", true,
                "excludeCandidateSetIds", List.of("outside"),
                "actions", List.of(Map.of("deviceId", "light-1", "closeOrOpen", 0))));
        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", CONVERSATION, "turn", body, "outside"))
                .isInstanceOf(BusinessException.class);
        assertThat(drafts.findByConversation(CONVERSATION)).isEmpty();
    }

    @Test
    void realisticTenantAndTurnFitTheBoundedDurableIdempotencyColumn() throws Exception {
        // Production diagnosis: tenant 3 + owner 64 + source 6 + caller 66 + NULs = 142.
        PrincipalInfo realPrincipal = new PrincipalInfo("Bearer fixture", "XSJ");
        RequestIdentity identity = new RequestIdentity(realPrincipal.authorization(), realPrincipal.tenantCode(),
                IotSourceEnum.OFFICE, realPrincipal.ownerKey());
        String fingerprint = AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, LIGHTS).catalogFingerprint();
        DeviceCandidateRecord realSet = new DeviceCandidateRecord("set-real", CONVERSATION, realPrincipal.tenantCode(), realPrincipal.ownerKey(), IotSourceEnum.OFFICE, fingerprint, new DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, null), LIGHTS.subList(0, 3), true, 3, CLOCK.instant().toString(), "exact", null);
        InMemoryActionDraftStore drafts = new InMemoryActionDraftStore(128);
        ActionDraftFacade facade = facade(drafts, new InMemoryCandidateStore(realSet),
                new TestIotAdapter(LIGHTS, identityValue -> states));

        String payload = payload(Map.of("candidateSetIds", List.of("set-real"), "summary", "打开当前位置的灯",
                "actions", List.of(Map.of("deviceId", "light-2", "kind", "lighting",
                        "closeOrOpen", 1, "selected", true))));
        String turnKey = "12345678-1234-1234-1234-123456789abc:" + "call_" + "a".repeat(24);
        ActionDraftFact created = facade.propose(realPrincipal, "office", CONVERSATION, "turn-1", payload, turnKey);
        ActionDraftFact retried = facade.propose(realPrincipal, "office", CONVERSATION, "turn-1", payload, turnKey);

        assertThat(created.idempotencyKey()).hasSizeLessThanOrEqualTo(128);
        assertThat(created.idempotencyKey()).startsWith("sha256:");
        assertThat(retried.draftId()).isEqualTo(created.draftId());
    }

    @Test
    void rejectsStaleOrCrossConversationCandidateSets() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        String payload = payload(Map.of(
                "candidateSetIds", List.of("set-lighting"),
                "summary", "灯",
                "actions", List.of(Map.of("deviceId", "light-2", "kind", "lighting",
                        "closeOrOpen", 1, "selected", true))));
        DeviceCandidateRecord stale = new DeviceCandidateRecord("set-lighting", CONVERSATION, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), IotSourceEnum.OFFICE, "stale", new DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, null), LIGHTS.subList(0, 3), true, 3, CLOCK.instant().toString(), "exact", null);
        ActionDraftFacade staleFacade = facade(new InMemoryActionDraftStore(),
                new InMemoryCandidateStore(stale), iot);
        assertThatThrownBy(() -> staleFacade.propose(PRINCIPAL, "office", CONVERSATION, "turn-1", payload, "idem"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("CANDIDATE_SET_STALE"));

        DeviceCandidateRecord foreign = set("set-lighting", "lighting", LIGHTS.subList(0, 3), iot,
                "another-conversation");
        ActionDraftFacade foreignFacade = facade(new InMemoryActionDraftStore(),
                new InMemoryCandidateStore(foreign), iot);
        assertThatThrownBy(() -> foreignFacade.propose(PRINCIPAL, "office", CONVERSATION, "turn-1", payload, "idem"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("CANDIDATE_SET_NOT_FOUND"));
    }

    @Test
    void runtimeLightingScopeExpandsFixedMembersAndSubtractsTheExactSubset() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var drafts = new InMemoryActionDraftStore();
        var included = set("included", "lighting", LIGHTS.subList(0, 3), iot);
        var excluded = set("excluded", "lighting", List.of(LIGHTS.get(2)), iot);
        var facade = facade(drafts, new InMemoryCandidateStore(included, excluded), iot);
        var draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-lighting",
                payload(Map.of("candidateSetIds", List.of(), "actions", List.of(),
                        "lighting", Map.of("candidateSetIds", List.of("included"),
                                "excludeCandidateSetIds", List.of("excluded"), "closeOrOpen", 1))), "lighting");
        var view = facade.view(draft);
        assertThat(actions(view)).extracting(row -> row.get("deviceId")).containsExactly("light-1", "light-2");
        assertThat(((Map<?, ?>) view.get("counts")).get("excluded")).isEqualTo(1);
        assertThat(((Map<?, ?>) view.get("counts")).get("total")).isEqualTo(3);
        assertThat(inventory(view)).filteredOn(row -> "excluded".equals(row.get("disposition")))
                .extracting(row -> row.get("deviceId")).containsExactly("corridor");
    }

    @Test
    void lightingExclusionResolvesTheDeviceHandleFromItsOwnCandidateSet() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var included = handled(set("included", "lighting", LIGHTS.subList(0, 3), iot), "s7");
        var corridor = handled(set("corridor-source", "lighting",
                List.of(LIGHTS.get(2), LIGHTS.get(0), LIGHTS.get(1)), iot), "s8");
        var facade = facade(new InMemoryActionDraftStore(), new InMemoryCandidateStore(included, corridor), iot);

        var draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-excluded-ref",
                payload(Map.of("candidateSetIds", List.of(), "actions", List.of(),
                        "lighting", Map.of("candidateSetId", "s7",
                                "excludedDeviceIds", List.of("d8_1"), "closeOrOpen", 1))), "excluded-ref");

        var view = facade.view(draft);
        assertThat(draft.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(actions(view)).extracting(row -> row.get("deviceId")).containsExactly("light-1", "light-2");
        assertThat(inventory(view)).filteredOn(row -> "excluded".equals(row.get("disposition")))
                .extracting(row -> row.get("deviceId")).containsExactly("corridor");
        assertThat(((Map<?, ?>) view.get("counts")).get("total")).isEqualTo(3);
    }

    @Test
    void rootAndLightingExclusionsMergeByDeviceIdentityInsteadOfReferenceSpelling() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var included = handled(set("included", "lighting", LIGHTS.subList(0, 3), iot), "s7");
        var corridor = handled(set("corridor-source", "lighting", List.of(LIGHTS.get(2)), iot), "s8");
        var facade = facade(new InMemoryActionDraftStore(), new InMemoryCandidateStore(included, corridor), iot);
        var draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-overlap-ref",
                payload(Map.of("candidateSetIds", List.of(), "actions", List.of(),
                        "excludedDeviceIds", List.of("d8_1"),
                        "lighting", Map.of("candidateSetId", "s7",
                                "excludedDeviceIds", List.of("d8_1"), "closeOrOpen", 1))), "overlap-ref");

        var view = facade.view(draft);
        assertThat(draft.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(actions(view)).extracting(row -> row.get("deviceId")).containsExactly("light-1", "light-2");
        assertThat(((Map<?, ?>) view.get("counts")).get("excluded")).isEqualTo(1);
        assertThat(MAPPER.readTree(draft.payloadJson()).path("excludedDeviceIds"))
                .isEqualTo(MAPPER.valueToTree(List.of("corridor")));
    }

    @Test
    void ordinaryExclusionResolvesDetailReferencesWithoutIncludingTheirOtherMembers() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var included = handled(set("included", "lighting", LIGHTS.subList(0, 3), iot), "s7");
        var detail = handled(set("detail", "lighting", List.of(LIGHTS.get(2), LIGHTS.get(3)), iot), "s9");
        var facade = facade(new InMemoryActionDraftStore(), new InMemoryCandidateStore(included, detail), iot);

        var draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-detail-ref",
                payload(Map.of("candidateSetIds", List.of("s7"), "expandCandidates", true,
                        "excludedDeviceIds", List.of("d9_1"),
                        "actions", List.of(Map.of("deviceId", "light-2", "closeOrOpen", 1)))), "detail-ref");

        var view = facade.view(draft);
        assertThat(actions(view)).extracting(row -> row.get("deviceId")).containsExactly("light-1", "light-2");
        assertThat(inventory(view)).extracting(row -> row.get("deviceId"))
                .containsExactly("light-1", "light-2", "corridor");
        assertThat(((Map<?, ?>) view.get("counts")).get("excluded")).isEqualTo(1);
        assertThat(((Map<?, ?>) view.get("counts")).get("total")).isEqualTo(3);
    }

    @Test
    void exclusionSourceStillRequiresTheSameIdentityConversationCatalogAndExactScope() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var included = handled(set("included", "lighting", LIGHTS.subList(0, 3), iot), "s7");
        var base = set("exclusion", "lighting", List.of(LIGHTS.get(2)), iot);
        String request = payload(Map.of("candidateSetIds", List.of(), "actions", List.of(),
                "lighting", Map.of("candidateSetId", "s7",
                        "excludedDeviceIds", List.of("d8_1"), "closeOrOpen", 1)));
        for (String failure : List.of("tenant", "owner", "source", "conversation", "catalog", "browse")) {
            var excluded = new DeviceCandidateRecord(base.candidateSetId(),
                    "conversation".equals(failure) ? "foreign-conversation" : base.conversationId(),
                    "tenant".equals(failure) ? "OTHER" : base.tenantCode(),
                    "owner".equals(failure) ? "foreign-owner" : base.ownerKey(),
                    "source".equals(failure) ? IotSourceEnum.COMPANY : base.source(),
                    "catalog".equals(failure) ? "stale" : base.catalogFingerprint(),
                    base.query(), base.members(), true, 1, base.createdAt(),
                    "browse".equals(failure) ? "browse" : "exact", "s8");
            var drafts = new InMemoryActionDraftStore();
            var facade = facade(drafts, new InMemoryCandidateStore(included, excluded), iot);
            String expected = switch (failure) {
                case "catalog" -> "CANDIDATE_SET_STALE";
                case "browse" -> "CANDIDATE_SCOPE_UNRESOLVED";
                default -> "CANDIDATE_SET_NOT_FOUND";
            };
            assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", CONVERSATION,
                    "turn-invalid-ref", request, failure))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo(expected));
            assertThat(drafts.findByConversation(CONVERSATION)).isEmpty();
        }
    }

    @Test
    void resolvedExclusionOutsideTheIncludedLightingStillRejectsTheWholeProposal() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var included = handled(set("included", "lighting", LIGHTS.subList(0, 3), iot), "s7");
        var outside = handled(set("outside", "lighting", List.of(LIGHTS.get(3)), iot), "s8");
        var drafts = new InMemoryActionDraftStore();
        var facade = facade(drafts, new InMemoryCandidateStore(included, outside), iot);
        String request = payload(Map.of("candidateSetIds", List.of(), "actions", List.of(),
                "lighting", Map.of("candidateSetId", "s7",
                        "excludedDeviceIds", List.of("d8_1"), "closeOrOpen", 1)));

        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", CONVERSATION,
                "turn-outside-ref", request, "outside-ref")).isInstanceOf(BusinessException.class);
        assertThat(drafts.findByConversation(CONVERSATION)).isEmpty();
    }

    @Test
    void aHandleFromAnIncludedBrowseSetCannotBecomeAnExactExclusion() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var base = set("included", "lighting", LIGHTS.subList(0, 3), iot);
        var browse = new DeviceCandidateRecord(base.candidateSetId(), base.conversationId(), base.tenantCode(),
                base.ownerKey(), base.source(), base.catalogFingerprint(), base.query(), base.members(),
                true, base.totalMatches(), base.createdAt(), "browse", "s7");
        var drafts = new InMemoryActionDraftStore();
        var facade = facade(drafts, new InMemoryCandidateStore(browse), iot);
        String request = payload(Map.of("candidateSetIds", List.of("s7"), "expandCandidates", true,
                "excludedDeviceIds", List.of("d7_3"),
                "actions", List.of(Map.of("deviceId", "light-2", "closeOrOpen", 1))));

        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", CONVERSATION,
                "turn-browse-ref", request, "browse-ref"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code())
                        .isEqualTo("CANDIDATE_SCOPE_UNRESOLVED"));
        assertThat(drafts.findByConversation(CONVERSATION)).isEmpty();
    }

    @Test
    void loadingAnExclusionSourceDoesNotAuthorizeItsHandlesAsActionReferences() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var included = handled(set("included", "lighting", LIGHTS.subList(0, 3), iot), "s7");
        var detail = handled(set("detail", "lighting", List.of(LIGHTS.get(2), LIGHTS.get(1)), iot), "s8");
        var drafts = new InMemoryActionDraftStore();
        var facade = facade(drafts, new InMemoryCandidateStore(included, detail), iot);
        String request = payload(Map.of("candidateSetIds", List.of("s7"), "expandCandidates", true,
                "excludedDeviceIds", List.of("d8_1"),
                "actions", List.of(Map.of("deviceId", "d8_2", "closeOrOpen", 1))));

        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", CONVERSATION,
                "turn-action-ref", request, "action-ref")).isInstanceOf(BusinessException.class);
        assertThat(drafts.findByConversation(CONVERSATION)).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "AGENT_TASK_FIXTURE_MYSQL_URL", matches = ".+")
    void anotherConversationCannotHideAnExclusionSourceWithTheSameShortName() throws Exception {
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                System.getenv("AGENT_TASK_FIXTURE_MYSQL_URL"), System.getenv("AGENT_TASK_FIXTURE_MYSQL_USERNAME"),
                System.getenv("AGENT_TASK_FIXTURE_MYSQL_PASSWORD"));
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/mysql/device-candidate-sets.sql"))
                .execute(dataSource);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var candidates = ltd.qianc.iotagent.iot.IotAdapterFactory.candidateSets(jdbc, MAPPER);
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        String conversation = "exclusion-" + java.util.UUID.randomUUID();
        var included = handled(set(conversation + "-included", "lighting",
                LIGHTS.subList(0, 3), iot, conversation), "s7");
        var corridor = handled(set(conversation + "-corridor", "lighting",
                List.of(LIGHTS.get(2)), iot, conversation), "s8");
        var foreign = handled(set(conversation + "-foreign", "lighting",
                List.of(LIGHTS.get(3)), iot, conversation + "-other"), "s8");
        try {
            candidates.save(included);
            candidates.save(corridor);
            candidates.save(foreign);
            var facade = facade(new InMemoryActionDraftStore(), candidates, iot);
            var draft = facade.propose(PRINCIPAL, "office", conversation, "turn-shared-ref",
                    payload(Map.of("candidateSetIds", List.of(), "actions", List.of(),
                            "lighting", Map.of("candidateSetId", "s7",
                                    "excludedDeviceIds", List.of("d8_1"), "closeOrOpen", 1))), "shared-ref");

            assertThat(draft.status()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(actions(facade.view(draft))).extracting(row -> row.get("deviceId"))
                    .containsExactly("light-1", "light-2");
            assertThat(inventory(facade.view(draft))).filteredOn(row -> "excluded".equals(row.get("disposition")))
                    .extracting(row -> row.get("deviceId")).containsExactly("corridor");
        } finally {
            jdbc.update("DELETE FROM device_candidate_sets WHERE candidate_set_id IN (?,?,?)",
                    included.candidateSetId(), corridor.candidateSetId(), foreign.candidateSetId());
        }
    }

    @Test
    void alreadySatisfiedImmediateTargetsDoNotRemainPendingConfirmation() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        var facade = facade(new InMemoryActionDraftStore(),
                new InMemoryCandidateStore(set("already-on", "lighting", List.of(LIGHTS.get(0)), iot)), iot);
        var draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-satisfied",
                payload(Map.of("candidateSetIds", List.of("already-on"),
                        "actions", List.of(Map.of("deviceId", "light-1", "closeOrOpen", 1)))), "satisfied");
        assertThat(draft.status()).isEqualTo("NO_CHANGE");
        assertThat(facade.view(draft).get("selectedActionIds")).isEqualTo(List.of());
    }

    @Test
    void focusRegionIsMembershipCheckedAndNarrowsTheScope() throws Exception {
        List<OfficeRegion> regions = List.of(new OfficeRegion("region-1", "运营办公区"));
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states, regions);
        RequestIdentity identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSourceEnum.OFFICE, PRINCIPAL.ownerKey());
        String filteredFingerprint = AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, LIGHTS)
                .retain(device -> device.officeIds().contains("region-1")).catalogFingerprint();
        DeviceCandidateRecord focusSet = candidateSet("set-focus", CONVERSATION, filteredFingerprint,
                LIGHTS.subList(0, 3));
        DeviceCandidateRecord outsideSet = candidateSet("set-outside", "conversation-2", filteredFingerprint,
                List.of(LIGHTS.get(3)));
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(),
                new InMemoryCandidateStore(focusSet, outsideSet), iot);

        String payload = payload(Map.of("candidateSetIds", List.of("set-focus"), "summary", "灯",
                "actions", List.of(Map.of("deviceId", "light-2", "kind", "lighting",
                        "closeOrOpen", 1, "selected", true))));
        ActionDraftFact draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-1", payload, "idem", "region-1");
        assertThat(facade.view(draft)).containsEntry("focusRegionId", "region-1");

        String outside = payload(Map.of("candidateSetIds", List.of("set-outside"), "summary", "灯",
                "actions", List.of(Map.of("deviceId", "other-region", "kind", "lighting",
                        "closeOrOpen", 1, "selected", true))));
        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", "conversation-2", "turn-2", outside, "idem2", "region-1"))
                .isInstanceOf(ActionDraftScopeChangedException.class);
        assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", "conversation-3", "turn-3", outside, "idem3", "region-x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("FOCUS_REGION_UNAVAILABLE"));
    }

    @Test
    void namedRegionCandidateKeepsCatalogVersionWhileExecutionRemainsFocused() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states,
                List.of(new OfficeRegion("region-1", "运营办公区")));
        DeviceCandidateRecord named = set("named-region", "lighting", LIGHTS.subList(0, 3), iot);
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(), new InMemoryCandidateStore(named), iot);
        String request = payload(Map.of("candidateSetIds", List.of("named-region"),
                "expandCandidates", true,
                "actions", List.of(Map.of("deviceId", "light-2", "kind", "lighting", "closeOrOpen", 1))));
        ActionDraftFact fact = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-1",
                request, "named-region-proposal", "region-1");
        assertThat(actions(facade.view(fact))).extracting(row -> row.get("deviceId"))
                .containsExactly("light-1", "light-2", "corridor");

        DeviceCandidateRecord outside = set("outside-focus", "lighting", List.of(LIGHTS.get(3)), iot, "other-conversation");
        ActionDraftFacade outsideFacade = facade(new InMemoryActionDraftStore(), new InMemoryCandidateStore(outside), iot);
        String outsideRequest = payload(Map.of("candidateSetIds", List.of("outside-focus"),
                "actions", List.of(Map.of("deviceId", "other-region", "kind", "lighting", "closeOrOpen", 1))));
        assertThatThrownBy(() -> outsideFacade.propose(PRINCIPAL, "office", "other-conversation", "turn-2",
                outsideRequest, "outside-proposal", "region-1")).isInstanceOf(ActionDraftScopeChangedException.class);
    }

    @Test
    void rejectsOverflowFractionalAndTextualControlValuesInsteadOfTruncating() throws Exception {
        List<AuthorizedDevice> devices = List.of(
                light("light-1", "灯1", "26楼运营办公区", List.of("region-1")),
                new AuthorizedDevice("door-1", "access", "门禁", "一层", false,
                        "door-control", "door-1", "hub-door", null, List.of(), List.of("region-1"), Map.of()),
                new AuthorizedDevice("ac-1", "ac", "会议室空调", "26楼会议室", false,
                        "ac-control", "ac-1", "hub-ac", null, List.of(), List.of("region-1"), Map.of()));
        Map<String, Object> states = Map.of(
                "light-1", false,
                "ac-1", Map.of("power", "on", "temperature", 24, "mode", "cool", "fanSpeed", "low"));
        TestIotAdapter iot = new TestIotAdapter(devices, identity -> states);
        RequestIdentity identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSourceEnum.OFFICE, PRINCIPAL.ownerKey());
        String fingerprint = AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, devices).catalogFingerprint();
        // 每个会话绑定自己的候选集：拒绝路径与两个正例各自独立，不共享归属。
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(),
                new InMemoryCandidateStore(controlSet("all-devices", CONVERSATION, devices, fingerprint)), iot);

        for (String body : List.of(
                payload(Map.of("candidateSetIds", List.of("all-devices"), "actions", List.of(
                        Map.of("deviceId", "light-1", "closeOrOpen", 4294967297L)))),
                payload(Map.of("candidateSetIds", List.of("all-devices"), "actions", List.of(
                        Map.of("deviceId", "light-1", "closeOrOpen", 1.9d)))),
                payload(Map.of("candidateSetIds", List.of("all-devices"), "actions", List.of(
                        Map.of("deviceId", "light-1", "closeOrOpen", "1")))),
                payload(Map.of("candidateSetIds", List.of("all-devices"), "actions", List.of(
                        Map.of("deviceId", "door-1", "controlType", 4294967298L)))),
                payload(Map.of("candidateSetIds", List.of("all-devices"), "actions", List.of(
                        Map.of("deviceId", "door-1", "controlType", 2.0d)))),
                payload(Map.of("candidateSetIds", List.of("all-devices"), "actions", List.of(
                        Map.of("deviceId", "ac-1", "command", Map.of("temperature", 4294967322L))))),
                payload(Map.of("candidateSetIds", List.of("all-devices"), "actions", List.of(
                        Map.of("deviceId", "ac-1", "command", Map.of("temperature", 26.5d))))))) {
            assertThatThrownBy(() -> facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-strict",
                    body, "strict-" + System.nanoTime()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).code())
                            .isEqualTo("UNSUPPORTED_ACTION_DRAFT"));
        }

        ActionDraftFacade powerFacade = facade(new InMemoryActionDraftStore(),
                new InMemoryCandidateStore(controlSet("power-set", "conversation-power", devices, fingerprint)), iot);
        ActionDraftFact power = powerFacade.propose(PRINCIPAL, "office", "conversation-power", "turn-power",
                payload(Map.of("candidateSetIds", List.of("power-set"), "actions", List.of(
                        Map.of("deviceId", "light-1", "command", Map.of("power", "on"))))), "power-on");
        assertThat(actions(powerFacade.view(power)).get(0).get("command")).isEqualTo(Map.of("closeOrOpen", 1));

        ActionDraftFacade accessFacade = facade(new InMemoryActionDraftStore(),
                new InMemoryCandidateStore(controlSet("access-set", "conversation-access", devices, fingerprint)), iot);
        ActionDraftFact access = accessFacade.propose(PRINCIPAL, "office", "conversation-access", "turn-access",
                payload(Map.of("candidateSetIds", List.of("access-set"), "actions", List.of(
                        Map.of("deviceId", "door-1", "controlType", 2)))), "access-open");
        assertThat(actions(accessFacade.view(access)).get(0).get("command")).isEqualTo(Map.of("controlType", 2));
    }

    private static DeviceCandidateRecord controlSet(String id, String conversationId,
                                                    List<AuthorizedDevice> devices, String fingerprint) {
        return new DeviceCandidateRecord(id, conversationId, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(),
                IotSourceEnum.OFFICE, fingerprint,
                new DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, null),
                devices, true, devices.size(), CLOCK.instant().toString(), "exact", null);
    }

    private static DeviceCandidateRecord candidateSet(String id, String conversationId, String fingerprint,
                                                      List<AuthorizedDevice> members) {
        return new DeviceCandidateRecord(id, conversationId, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), IotSourceEnum.OFFICE, fingerprint, new DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, "region-1"), members, true, members.size(), CLOCK.instant().toString(), "exact", null);
    }

    private static ActionDraftFacade facade(InMemoryActionDraftStore drafts,
                                            DeviceCandidateSetStore candidates,
                                            TestIotAdapter iot) {
        ActionCommandGateway gateway = new ActionCommandGateway() {
            @Override
            public ActionDispatchResult dispatch(ltd.qianc.iotagent.iot.RequestIdentity identity,
                                                  AuthorizedDeviceScope scope, String selectedActionsJson) {
                throw new IllegalStateException("control must not run in proposal tests");
            }
        };
        return new ActionDraftFacade(MAPPER, drafts, iot, candidates, gateway,
                null, null, null, null, org.mockito.Mockito.mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class),
                CLOCK, Duration.ofSeconds(2), Duration.ofMinutes(10), null);
    }

    private DeviceCandidateRecord set(String id, String kind, List<AuthorizedDevice> members,
                                      TestIotAdapter iot) {
        return set(id, kind, members, iot, CONVERSATION);
    }

    private DeviceCandidateRecord set(String id, String kind, List<AuthorizedDevice> members,
                                      TestIotAdapter iot, String conversationId) {
        RequestIdentity identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSourceEnum.OFFICE, PRINCIPAL.ownerKey());
        String fingerprint = AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, LIGHTS).catalogFingerprint();
        return new DeviceCandidateRecord(id, conversationId, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), IotSourceEnum.OFFICE, fingerprint, new DeviceCandidateQuery(kind, List.of(), null, List.of(), List.of(), null, null), members, true, members.size(), CLOCK.instant().toString(), "exact", null);
    }

    private static DeviceCandidateRecord handled(DeviceCandidateRecord record, String handle) {
        return new DeviceCandidateRecord(record.candidateSetId(), record.conversationId(), record.tenantCode(),
                record.ownerKey(), record.source(), record.catalogFingerprint(), record.query(), record.members(),
                record.complete(), record.totalMatches(), record.createdAt(), record.resolutionMode(), handle);
    }

    private static String payload(Map<String, Object> body) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        body.forEach((key, value) -> payload.set(key, MAPPER.valueToTree(value)));
        return MAPPER.writeValueAsString(payload);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> actions(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("actions");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> inventory(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("inventory");
    }

    private static AuthorizedDevice light(String id, String name, String location, List<String> officeIds) {
        return new AuthorizedDevice(id, "lighting", name, location, false, "control-" + id, id,
                "hub-" + id, null, List.of(List.of("26楼", name)), officeIds, Map.of());
    }
}
