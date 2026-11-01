package ltd.qianc.iotagent.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;

class ActionDraftEditRevalidationContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "TENANT");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private static final String CONVERSATION = "conversation-edit";

    @Test
    void rejectsOutOfRangeAndUnsupportedPatchesAtomicallyWhileKeepingEditableState() throws Exception {
        ActionDraftFact draft = acDraft();
        InMemoryActionDraftStore store = new InMemoryActionDraftStore(draft);
        ActionDraftFacade facade = facade(store, new TestIotAdapter(List.of(), identity -> Map.of()));

        assertThatThrownBy(() -> facade.parameters(PRINCIPAL, draft.draftId(), 1,
                List.of(Map.of("actionId", "ac-1", "patch", Map.of("temperature", 15)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("UNSUPPORTED_ACTION_DRAFT"));
        assertThatThrownBy(() -> facade.parameters(PRINCIPAL, draft.draftId(), 1,
                List.of(Map.of("actionId", "ac-1", "patch", Map.of("temperature", 31)))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> facade.parameters(PRINCIPAL, draft.draftId(), 1,
                List.of(Map.of("actionId", "ac-1", "patch", Map.of("humidity", 40)))))
                .isInstanceOf(BusinessException.class);

        ActionDraftFact stored = store.find(draft.draftId()).orElseThrow();
        assertThat(stored.revision()).isEqualTo(1);
        assertThat(commandOf(stored, "ac-1")).isEqualTo(Map.of(
                "power", "on", "temperature", 24, "mode", "cool", "fanSpeed", "low"));

        ActionDraftFact at16 = facade.parameters(PRINCIPAL, draft.draftId(), 1,
                List.of(Map.of("actionId", "ac-1", "patch", Map.of("temperature", 16))));
        assertThat(commandOf(at16, "ac-1").get("temperature")).isEqualTo(16);
        ActionDraftFact at30 = facade.parameters(PRINCIPAL, draft.draftId(), at16.revision(),
                List.of(Map.of("actionId", "ac-1", "patch", Map.of("temperature", 30))));
        assertThat(commandOf(at30, "ac-1").get("temperature")).isEqualTo(30);
    }

    @Test
    void bulkEditsAndPerDeviceOverridesBothPersist() throws Exception {
        ActionDraftFact draft = acDraft();
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(draft),
                new TestIotAdapter(List.of(), identity -> Map.of()));

        ActionDraftFact bulk = facade.parameters(PRINCIPAL, draft.draftId(), 1, List.of(
                Map.of("actionId", "ac-1", "patch", Map.of("temperature", 22)),
                Map.of("actionId", "ac-2", "patch", Map.of("temperature", 22))));
        assertThat(commandOf(bulk, "ac-1").get("temperature")).isEqualTo(22);
        assertThat(commandOf(bulk, "ac-2").get("temperature")).isEqualTo(22);

        ActionDraftFact overridden = facade.parameters(PRINCIPAL, draft.draftId(), bulk.revision(),
                List.of(Map.of("actionId", "ac-2", "patch", Map.of("temperature", 18))));
        assertThat(commandOf(overridden, "ac-1").get("temperature")).isEqualTo(22);
        assertThat(commandOf(overridden, "ac-2").get("temperature")).isEqualTo(18);
        assertThat((List<?>) facade.view(overridden).get("selectedActionIds")).hasSize(2);
    }

    @Test
    void lightingTargetEditUsesRuntimeCloseOrOpenShape() throws Exception {
        ObjectNode payload = basePayload("PENDING_CONFIRMATION");
        ArrayNode actions = payload.putArray("actions");
        actions.add(MAPPER.valueToTree(Map.of("id", "light-action", "deviceId", "light-1", "kind", "lighting",
                "command", Map.of("closeOrOpen", 0), "beforeState", false)));
        payload.putArray("selectedActionIds").add("light-action");
        payload.set("inventory", MAPPER.valueToTree(List.of(Map.of("deviceId", "light-1", "kind", "lighting",
                "state", false, "command", Map.of("closeOrOpen", 0), "disposition", "operate", "satisfied", false))));
        ActionDraftFact draft = fact("draft-light", 1, payload);
        ActionDraftFacade facade = facade(new InMemoryActionDraftStore(draft),
                new TestIotAdapter(List.of(), identity -> Map.of()));

        ActionDraftFact updated = facade.parameters(PRINCIPAL, draft.draftId(), 1,
                List.of(Map.of("actionId", "light-action", "patch", Map.of("closeOrOpen", 1))));
        assertThat(commandOf(updated, "light-1")).isEqualTo(Map.of("closeOrOpen", 1));
    }

    @Test
    void revalidationReadsFreshStatesIntoANewDraftAndConsumesTheOldOne() throws Exception {
        ObjectNode payload = basePayload("EXPIRED", "2026-10-30T08:00:00Z");
        ArrayNode actions = payload.putArray("actions");
        actions.add(action("old-light-1", "light-1", "灯1", 1));
        actions.add(action("old-light-2", "light-2", "灯2", 1));
        payload.putArray("selectedActionIds").add("old-light-1").add("old-light-2");
        payload.set("inventory", MAPPER.valueToTree(List.of(
                Map.of("deviceId", "light-1", "kind", "lighting", "state", true,
                        "command", Map.of("closeOrOpen", 1), "disposition", "operate", "satisfied", false),
                Map.of("deviceId", "light-2", "kind", "lighting", "state", false,
                        "command", Map.of("closeOrOpen", 1), "disposition", "operate", "satisfied", false))));
        ActionDraftFact old = fact("draft-old", 2, payload);
        InMemoryActionDraftStore store = new InMemoryActionDraftStore(old);
        AuthorizedDevice one = light("light-1", "灯1");
        AuthorizedDevice two = light("light-2", "灯2");
        TestIotAdapter iot = new TestIotAdapter(List.of(one, two),
                identity -> Map.of("light-1", true, "light-2", false));
        ActionDraftFacade facade = facade(store, iot);

        ActionDraftFact fresh = facade.revalidate(PRINCIPAL, old.draftId(), 2);

        assertThat(fresh.draftId()).isNotEqualTo(old.draftId());
        Map<String, Object> view = facade.view(fresh);
        assertThat(view).containsEntry("status", "PENDING_CONFIRMATION");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refreshedActions = (List<Map<String, Object>>) view.get("actions");
        assertThat(refreshedActions).hasSize(1);
        assertThat(refreshedActions.get(0)).containsEntry("deviceId", "light-2");
        Map<?, ?> counts = (Map<?, ?>) view.get("counts");
        assertThat(counts.get("operate")).isEqualTo(1);
        assertThat(counts.get("satisfied")).isEqualTo(1);
        assertThat(counts.get("total")).isEqualTo(2);

        ActionDraftFact consumed = store.find(old.draftId()).orElseThrow();
        assertThat(consumed.status()).isEqualTo("EXPIRED");
        assertThat(new ObjectMapper().readTree(consumed.payloadJson()).path("revalidatedInto").asText())
                .isEqualTo(fresh.draftId());
        assertThatThrownBy(() -> facade.revalidate(PRINCIPAL, old.draftId(), consumed.revision()))
                .isInstanceOf(ActionDraftExpiredException.class);
    }

    private static ActionDraftFacade facade(InMemoryActionDraftStore store, TestIotAdapter iot) {
        ActionCommandGateway gateway = new ActionCommandGateway() {
            @Override
            public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                                  String selectedActionsJson) {
                throw new IllegalStateException("control must not run in edit/revalidation tests");
            }
        };
        return new ActionDraftFacade(MAPPER, store, iot, null, gateway, null, null, null, null,
                CLOCK, Duration.ofSeconds(2), Duration.ofMinutes(10));
    }

    private static ActionDraftFact acDraft() throws Exception {
        ObjectNode payload = basePayload("PENDING_CONFIRMATION");
        ArrayNode actions = payload.putArray("actions");
        actions.add(acAction("ac-1", "机房空调1", 26));
        actions.add(acAction("ac-2", "机房空调2", 26));
        payload.putArray("selectedActionIds").add("ac-1").add("ac-2");
        payload.set("inventory", MAPPER.valueToTree(List.of(
                acRow("ac-1", "空调1", 26), acRow("ac-2", "空调2", 26))));
        return fact("draft-edit", 1, payload);
    }

    private static ObjectNode basePayload(String status) {
        return basePayload(status, "2026-11-05T09:00:00Z");
    }

    private static ObjectNode basePayload(String status, String expiresAt) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("tenantCode", PRINCIPAL.tenantCode());
        payload.put("ownerKey", PRINCIPAL.ownerKey());
        payload.put("source", "office");
        payload.put("conversationId", CONVERSATION);
        payload.put("turnId", "turn-1");
        payload.put("status", status);
        payload.put("expiresAt", expiresAt);
        payload.put("summary", "空调");
        payload.putNull("question");
        return payload;
    }

    private static ObjectNode acAction(String id, String name, int observed) {
        ObjectNode action = MAPPER.createObjectNode();
        action.put("id", id);
        action.put("deviceId", id);
        action.put("kind", "ac");
        action.put("deviceName", name);
        action.put("location", "机房");
        action.set("beforeState", MAPPER.valueToTree(Map.of(
                "power", "on", "temperature", observed, "mode", "cool", "fanSpeed", "low")));
        action.set("command", MAPPER.valueToTree(Map.of(
                "power", "on", "temperature", 24, "mode", "cool", "fanSpeed", "low")));
        return action;
    }

    private static Map<String, Object> acRow(String id, String name, int observed) {
        return Map.of("deviceId", id, "kind", "ac", "name", name, "location", "机房",
                "state", Map.of("power", "on", "temperature", observed, "mode", "cool", "fanSpeed", "low"),
                "command", Map.of("power", "on", "temperature", 24, "mode", "cool", "fanSpeed", "low"),
                "disposition", "operate", "satisfied", false);
    }

    private static ObjectNode action(String id, String deviceId, String name, int closeOrOpen) {
        ObjectNode action = MAPPER.createObjectNode();
        action.put("id", id);
        action.put("deviceId", deviceId);
        action.put("kind", "lighting");
        action.put("deviceName", name);
        action.put("location", "26楼");
        action.set("command", MAPPER.valueToTree(Map.of("closeOrOpen", closeOrOpen)));
        return action;
    }

    private static ActionDraftFact fact(String id, long revision, ObjectNode payload) throws Exception {
        return new ActionDraftFact(id, revision, payload.path("status").asText(), MAPPER.writeValueAsString(payload),
                "fixture:" + id);
    }

    private static Map<?, ?> commandOf(ActionDraftFact draft, String deviceId) throws Exception {
        for (var action : MAPPER.readTree(draft.payloadJson()).path("actions")) {
            if (deviceId.equals(action.path("deviceId").asText())) {
                return MAPPER.convertValue(action.path("command"), Map.class);
            }
        }
        throw new AssertionError("missing action for " + deviceId);
    }

    private static AuthorizedDevice light(String id, String name) {
        return new AuthorizedDevice(id, "lighting", name, "26楼", false, "control-" + id, id,
                "hub-" + id, null, List.of(List.of("26楼")), List.of("region-1"), Map.of());
    }
}
