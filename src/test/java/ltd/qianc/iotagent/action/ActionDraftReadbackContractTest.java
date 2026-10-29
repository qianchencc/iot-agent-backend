package ltd.qianc.iotagent.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.IotAdapter;
import org.junit.jupiter.api.Test;

class ActionDraftReadbackContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "LOCAL");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);

    @Test
    void multipleDevicesExposeDeviceKeyedProjectionAndRetainDurableFactList() throws Exception {
        ActionDraftFact draft = draft("draft-1", "ACCEPTED", action("action-1", "device-1"), action("action-2", "device-2"));
        ActionReadbackStore readbacks = readbacks(
                new ActionReadbackFact("draft-1", 2, "action-1", "confirmed", "{\"power\":\"on\"}", "2026-10-30T09:00:01Z"),
                new ActionReadbackFact("draft-1", 2, "action-2", "unconfirmed", "{}", "2026-10-30T09:00:02Z"));

        Map<String, Object> response = facade(draft, readbacks).deviceStates(PRINCIPAL, "draft-1");

        assertThat(response).containsEntry("draftId", "draft-1");
        assertThat(response.get("deviceStates")).isEqualTo(Map.of(
                "device-1", Map.of("power", "on"),
                "device-2", Map.of()));
        assertThat(response.get("states")).isEqualTo(List.of(
                Map.of("actionId", "action-1", "outcome", "confirmed", "observedAt", "2026-10-30T09:00:01Z", "observedState", Map.of("power", "on")),
                Map.of("actionId", "action-2", "outcome", "unconfirmed", "observedAt", "2026-10-30T09:00:02Z", "observedState", Map.of())));
    }

    @Test
    void emptyReadbackReturnsEmptyProjectionWithoutFabricatingState() throws Exception {
        Map<String, Object> response = facade(draft("draft-2", "NO_CHANGE", action("action-1", "device-1")), readbacks())
                .deviceStates(PRINCIPAL, "draft-2");

        assertThat(response).containsEntry("draftId", "draft-2");
        assertThat(response.get("deviceStates")).isEqualTo(Map.of());
        assertThat(response.get("states")).isEqualTo(List.of());
    }

    void authorizationAndMissingDraftErrorsDoNotRevealReadbackFacts() throws Exception {
        ActionDraftFact draft = draft("draft-3", "ACCEPTED", action("action-1", "device-1"));
        ActionDraftFacade facade = facade(draft, readbacks());

        assertThatThrownBy(() -> facade.deviceStates(new PrincipalInfo("Bearer other", "LOCAL"), "draft-3"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("ACTION_DRAFT_NOT_FOUND"));
        assertThatThrownBy(() -> facade.deviceStates(PRINCIPAL, "missing"))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("ACTION_DRAFT_NOT_FOUND"));
    }

    private static ActionDraftFacade facade(ActionDraftFact draft, ActionReadbackStore readbacks) {
        ActionDraftStore drafts = new ActionDraftStore() {
            @Override
            public ActionDraftFact create(ActionDraftFact value) { return value; }

            @Override
            public Optional<ActionDraftFact> find(String id) {
                return draft.draftId().equals(id) ? Optional.of(draft) : Optional.empty();
            }

            @Override
            public ActionDraftFact update(String id, long revision, ActionDraftFact replacement) { return replacement; }
        };
        return new ActionDraftFacade(MAPPER, drafts, mock(IotAdapter.class), mock(ActionCommandGateway.class),
                null, null, null, readbacks, CLOCK, Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    private static ActionReadbackStore readbacks(ActionReadbackFact... facts) {
        List<ActionReadbackFact> values = List.of(facts);
        return new ActionReadbackStore() {
            @Override
            public void append(ActionReadbackFact fact) { }

            @Override
            public List<ActionReadbackFact> findByDraft(String draftId) {
                return values.stream().filter(fact -> fact.draftId().equals(draftId)).toList();
            }
        };
    }

    private static ActionDraftFact draft(String id, String status, String... actions) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("tenantCode", PRINCIPAL.tenantCode());
        payload.put("ownerKey", PRINCIPAL.ownerKey());
        ArrayNode actionList = payload.putArray("actions");
        for (String action : actions) actionList.add(MAPPER.readTree(action));
        return new ActionDraftFact(id, 2, status, MAPPER.writeValueAsString(payload), "fixture:" + id);
    }

    private static String action(String id, String deviceId) throws Exception {
        return MAPPER.writeValueAsString(Map.of("id", id, "deviceId", deviceId, "kind", "lighting"));
    }
}
