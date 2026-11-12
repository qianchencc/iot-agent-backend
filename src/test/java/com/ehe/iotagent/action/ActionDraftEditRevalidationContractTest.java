package com.ehe.iotagent.action;

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
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.RequestIdentity;
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
    void editPatchesRejectFractionalOverflowAndTextualValuesWithoutTruncating() throws Exception {
        ActionDraftFact draft = acDraft();
        InMemoryActionDraftStore store = new InMemoryActionDraftStore(draft);
        ActionDraftFacade facade = facade(store, new TestIotAdapter(List.of(), identity -> Map.of()));

        for (Map<String, Object> patch : List.<Map<String, Object>>of(
                Map.<String, Object>of("temperature", 26.5d),
                Map.<String, Object>of("temperature", 4294967322L),
                Map.<String, Object>of("temperature", "26"))) {
            assertThatThrownBy(() -> facade.parameters(PRINCIPAL, draft.draftId(), 1,
                    List.of(Map.of("actionId", "ac-1", "patch", patch))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).code())
                            .isEqualTo("UNSUPPORTED_ACTION_DRAFT"));
        }

        ObjectNode payload = basePayload("PENDING_CONFIRMATION");
        ArrayNode lightActions = payload.putArray("actions");
        lightActions.add(action("light-action", "light-1", "灯", 0));
        payload.putArray("selectedActionIds").add("light-action");

        // 显式 off 只能单独出现：off 混排非法/合法字段都不再被静默丢弃成纯关机。
        for (Map<String, Object> patch : List.<Map<String, Object>>of(
                Map.<String, Object>of("power", "off", "temperature", 999),
                Map.<String, Object>of("power", "off", "humidity", 40))) {
            assertThatThrownBy(() -> facade.parameters(PRINCIPAL, draft.draftId(), 1,
                    List.of(Map.of("actionId", "ac-1", "patch", patch))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).code())
                            .isEqualTo("INVALID_PARAMETERS"));
        }
        assertThat(commandOf(facade.parameters(PRINCIPAL, draft.draftId(), 1,
                List.of(Map.of("actionId", "ac-1", "patch", Map.of("power", "off")))), "ac-1"))
                .isEqualTo(Map.of("power", "off"));

        ActionDraftFact lightDraft = fact("draft-strict-light", 1, payload);
        InMemoryActionDraftStore lightStore = new InMemoryActionDraftStore(lightDraft);
        ActionDraftFacade lightFacade = facade(lightStore, new TestIotAdapter(List.of(), identity -> Map.of()));
        for (Object value : List.of(1.9d, 4294967297L, "1")) {
            assertThatThrownBy(() -> lightFacade.parameters(PRINCIPAL, lightDraft.draftId(), 1,
                    List.of(Map.of("actionId", "light-action", "patch", Map.of("closeOrOpen", value)))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(error -> assertThat(((BusinessException) error).code())
                            .isEqualTo("INVALID_PARAMETERS"));
        }
        assertThat(commandOf(lightStore.find(lightDraft.draftId()).orElseThrow(), "light-1"))
                .isEqualTo(Map.of("closeOrOpen", 0));
        assertThat(store.find(draft.draftId()).orElseThrow().revision())
                .as("失败编辑不落库；ac 草案仅在显式 off 成功编辑后前移一次").isEqualTo(2);
    }

    @Test
    void replayedExpiryKeepsRevisionUsableAfterStateReadAndRevalidatesIntoANewDraft() throws Exception {
        ObjectNode payload = basePayload("PENDING_CONFIRMATION", "2026-10-30T08:00:00Z");
        ArrayNode actions = payload.putArray("actions");
        actions.add(action("old-light-1", "light-1", "灯1", 1));
        actions.add(action("old-light-2", "light-2", "灯2", 1));
        payload.putArray("selectedActionIds").add("old-light-1").add("old-light-2");
        payload.set("inventory", MAPPER.valueToTree(List.of(
                Map.of("deviceId", "light-1", "kind", "lighting", "state", true,
                        "command", Map.of("closeOrOpen", 1), "disposition", "operate", "satisfied", false),
                Map.of("deviceId", "light-2", "kind", "lighting", "state", false,
                        "command", Map.of("closeOrOpen", 1), "disposition", "operate", "satisfied", false))));
        ActionDraftFact old = fact("draft-old", 1, payload);
        InMemoryActionDraftStore store = new InMemoryActionDraftStore(old);
        AuthorizedDevice one = light("light-1", "灯1");
        AuthorizedDevice two = light("light-2", "灯2");
        TestIotAdapter iot = new TestIotAdapter(List.of(one, two),
                identity -> Map.of("light-1", true, "light-2", false));
        ActionDraftFacade facade = facade(store, iot);

        Map<String,Object> replayed = facade.conversationDrafts(PRINCIPAL, CONVERSATION, "office").get(old.draftId());
        assertThat(replayed).containsEntry("status", "EXPIRED");
        long replayedRevision = ((Number)replayed.get("revision")).longValue();
        facade.deviceStates(PRINCIPAL, old.draftId());
        assertThat(store.find(old.draftId()).orElseThrow().revision()).isEqualTo(replayedRevision);
        ActionDraftFact fresh = facade.revalidate(PRINCIPAL, old.draftId(), replayedRevision);

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

    @Test
    void expiredOtherOfficeDraftRevalidatesItsPinnedTargetsNotTheSavedFocus() throws Exception {
        ObjectNode payload = basePayload("EXPIRED", "2026-10-30T08:00:00Z");
        payload.put("focusRegionId", "region-1");
        payload.putArray("actions").add(action("old-other-light", "other-light", "测试办公室乙照明", 1));
        payload.putArray("selectedActionIds").add("old-other-light");
        payload.set("inventory", MAPPER.valueToTree(List.of(Map.of("deviceId", "other-light", "kind", "lighting",
                "state", false, "command", Map.of("closeOrOpen", 1), "disposition", "operate", "satisfied", false))));
        var old = fact("expired-other-office", 1, payload);
        var device = new AuthorizedDevice("other-light", "lighting", "测试办公室乙照明", "测试办公室乙", false,
                "control-other", "other-light", "hub-other", null, List.of(), List.of("region-2"), Map.of());
        var iot = new TestIotAdapter(List.of(device), identity -> Map.of("other-light", false),
                List.of(new com.ehe.iotagent.iot.OfficeRegion("region-1", "测试办公室甲"),
                        new com.ehe.iotagent.iot.OfficeRegion("region-2", "测试办公室乙")));
        var facade = facade(new InMemoryActionDraftStore(old), iot);
        var refreshed = facade.revalidate(PRINCIPAL, old.draftId(), old.revision());
        assertThat(refreshed.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(MAPPER.readTree(refreshed.payloadJson()).path("actions")).singleElement()
                .satisfies(row -> assertThat(row.path("deviceId").asText()).isEqualTo("other-light"));
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
                org.mockito.Mockito.mock(com.ehe.iotagent.readmodel.ReadSnapshots.class),
                CLOCK, Duration.ofSeconds(2), Duration.ofMinutes(10), null);
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
