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
import ltd.qianc.iotagent.iot.IotSource;
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
    void expandsFixedMembersSubtractsCorridorAndSeparatesCounts() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        InMemoryActionDraftStore drafts = new InMemoryActionDraftStore();
        DeviceCandidateRecord set = set("set-lighting", "lighting", LIGHTS.subList(0, 3), iot);
        ActionDraftFacade facade = facade(drafts, new InMemoryCandidateStore(set), iot);

        ActionDraftFact draft = facade.propose(PRINCIPAL, "office", CONVERSATION, "turn-1",
                payload(Map.of(
                        "candidateSetIds", List.of("set-lighting"),
                        "excludedDeviceIds", List.of("corridor"),
                        "summary", "打开当前位置的灯",
                        "question", "是否打开这些灯？",
                        "actions", List.of(Map.of("deviceId", "light-2", "kind", "lighting",
                                "closeOrOpen", 1, "selected", true)))), "idem-1");

        Map<String, Object> view = facade.view(draft);
        assertThat(view).containsEntry("summary", "打开当前位置的灯")
                .containsEntry("question", "是否打开这些灯？");
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
    void rejectsStaleOrCrossConversationCandidateSets() throws Exception {
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states);
        String payload = payload(Map.of(
                "candidateSetIds", List.of("set-lighting"),
                "summary", "灯",
                "actions", List.of(Map.of("deviceId", "light-2", "kind", "lighting",
                        "closeOrOpen", 1, "selected", true))));
        DeviceCandidateRecord stale = new DeviceCandidateRecord("set-lighting", CONVERSATION,
                PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(), IotSource.OFFICE, "stale",
                new DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, null),
                LIGHTS.subList(0, 3), true, 3, CLOCK.instant().toString());
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
    void focusRegionIsMembershipCheckedAndNarrowsTheScope() throws Exception {
        List<OfficeRegion> regions = List.of(new OfficeRegion("region-1", "运营办公区"));
        TestIotAdapter iot = new TestIotAdapter(LIGHTS, identity -> states, regions);
        RequestIdentity identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSource.OFFICE, PRINCIPAL.ownerKey());
        String filteredFingerprint = AuthorizedDeviceScope.of(identity, IotSource.OFFICE, LIGHTS)
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

    private static DeviceCandidateRecord candidateSet(String id, String conversationId, String fingerprint,
                                                      List<AuthorizedDevice> members) {
        return new DeviceCandidateRecord(id, conversationId, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(),
                IotSource.OFFICE, fingerprint,
                new DeviceCandidateQuery("lighting", List.of(), null, List.of(), List.of(), null, null),
                members, true, members.size(), CLOCK.instant().toString());
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
                null, null, null, null, CLOCK, Duration.ofSeconds(2), Duration.ofMinutes(10));
    }

    private DeviceCandidateRecord set(String id, String kind, List<AuthorizedDevice> members,
                                      TestIotAdapter iot) {
        return set(id, kind, members, iot, CONVERSATION);
    }

    private DeviceCandidateRecord set(String id, String kind, List<AuthorizedDevice> members,
                                      TestIotAdapter iot, String conversationId) {
        RequestIdentity identity = new RequestIdentity(PRINCIPAL.authorization(), PRINCIPAL.tenantCode(),
                IotSource.OFFICE, PRINCIPAL.ownerKey());
        String fingerprint = AuthorizedDeviceScope.of(identity, IotSource.OFFICE, LIGHTS).catalogFingerprint();
        return new DeviceCandidateRecord(id, conversationId, PRINCIPAL.tenantCode(), PRINCIPAL.ownerKey(),
                IotSource.OFFICE, fingerprint,
                new DeviceCandidateQuery(kind, List.of(), null, List.of(), List.of(), null, null),
                members, true, members.size(), CLOCK.instant().toString());
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
