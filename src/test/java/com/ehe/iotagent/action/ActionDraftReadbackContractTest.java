package com.ehe.iotagent.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

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
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.DeviceStateReadResult;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.Test;

class ActionDraftReadbackContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer fixture", "LOCAL");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC);
    private final List<Runnable> snapshotJobs = new java.util.ArrayList<>();
    private boolean scopeUnavailable;

    @Test
    void summaryRetainsDurableReadbackFactsInsteadOfCurrentStates() throws Exception {
        ActionDraftFact draft = draft("draft-1", "ACCEPTED", action("action-1", "device-1"), action("action-2", "device-2"));
        ActionReadbackStore readbacks = readbacks(
                new ActionReadbackFact("draft-1", 2, "action-1", "confirmed", "{\"power\":\"on\"}", "2026-10-30T09:00:01Z"),
                new ActionReadbackFact("draft-1", 2, "action-2", "unconfirmed", "{}", "2026-10-30T09:00:02Z"));

        Map<String, Object> response = facade(draft, readbacks).summary(PRINCIPAL, "draft-1");

        assertThat(response.get("readbacks")).isEqualTo(List.of(
                Map.of("actionId", "action-1", "outcome", "confirmed", "observedAt", "2026-10-30T09:00:01Z", "observedState", Map.of("power", "on")),
                Map.of("actionId", "action-2", "outcome", "unconfirmed", "observedAt", "2026-10-30T09:00:02Z", "observedState", Map.of())));
    }

    @Test
    void pendingDraftDisplaysCurrentDeviceStateWithoutAnyDispatchReadback() throws Exception {
        ActionDraftFacade facade = facade(draft("draft-2", "PENDING_CONFIRMATION",
                action("action-1", "device-1"), MAPPER.writeValueAsString(Map.of(
                        "id", "action-2", "deviceId", "device-2", "kind", "ac",
                        "command", Map.of("temperature", 24)))), readbacks());
        Map<String, Object> response = facade.deviceStates(PRINCIPAL, "draft-2");

        assertThat(response.get("states")).isEqualTo(Map.of(
                "device-1", Map.of("power", "on"),
                "device-2", Map.of("power", "off", "temperature", 24)));
        assertThat(response.get("observedAt")).isEqualTo("2026-10-30T09:00:00Z");
    }

    @Test
    void heldDraftRemainsReadableDuringScopeOutageButConfirmFailsClosed() throws Exception {
        scopeUnavailable = true;
        ActionDraftFacade facade = facade(draft("draft-held", "PENDING_CONFIRMATION",
                action("action-1", "device-1")), readbacks());

        assertThat(facade.current(PRINCIPAL, "draft-held").status()).isEqualTo("PENDING_CONFIRMATION");
        assertThatThrownBy(() -> facade.confirm(PRINCIPAL, "draft-held", 2L))
                .isInstanceOf(ActionDraftUnavailableException.class);
    }

    @Test
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

    private ActionDraftFacade facade(ActionDraftFact draft, ActionReadbackStore readbacks) {
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
        IotAdapter iot = mock(IotAdapter.class);
        when(iot.loadAuthorizedDeviceScope(any())).thenAnswer(call -> {
            if (scopeUnavailable) return Mono.error(new com.ehe.iotagent.iot.UpstreamUnavailableException("scope unavailable"));
            com.ehe.iotagent.iot.RequestIdentity identity = call.getArgument(0);
            return Mono.just(new com.ehe.iotagent.iot.AuthorizedDeviceScope() {
                @Override public com.ehe.iotagent.iot.RequestIdentity identity() { return identity; }
                @Override public com.ehe.iotagent.iot.IotSourceEnum source() { return identity.source(); }
                @Override public List<com.ehe.iotagent.iot.AuthorizedDevice> devices() {
                    return List.of(new com.ehe.iotagent.iot.AuthorizedDevice(
                            "device-1", "lighting", "灯", "办公室", false));
                }
            });
        });
        when(iot.readDeviceStates(any(), anyList())).thenReturn(Mono.just(new DeviceStateReadResult(
                Map.of("device-1", Map.of("power", "on"),
                        "device-2", Map.of("power", "off", "temperature", 24)), "2026-10-30T09:00:00Z")));
        com.ehe.iotagent.readmodel.ReadSnapshots snapshots = com.ehe.iotagent.readmodel.ReadSnapshots.create(
                CLOCK, snapshotJobs::add, snapshotJobs::add, null, null, MAPPER,
                com.ehe.iotagent.readmodel.ReadSnapshots.Settings.defaults());
        return new ActionDraftFacade(MAPPER, drafts, iot, mock(ActionCommandGateway.class),
                null, null, null, readbacks, snapshots, CLOCK, Duration.ofSeconds(1), Duration.ofMinutes(5), null);
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
        payload.put("source", "office");
        ArrayNode actionList = payload.putArray("actions");
        for (String action : actions) actionList.add(MAPPER.readTree(action));
        return new ActionDraftFact(id, 2, status, MAPPER.writeValueAsString(payload), "fixture:" + id);
    }

    private static String action(String id, String deviceId) throws Exception {
        return MAPPER.writeValueAsString(Map.of("id", id, "deviceId", deviceId, "kind", "lighting",
                "command", Map.of("power", "on")));
    }
}
