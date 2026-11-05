package ltd.qianc.iotagent.action;

import ltd.qianc.iotagent.conversation.AgentEngineBridge;
import ltd.qianc.iotagent.conversation.AgentEngineEvent;
import ltd.qianc.iotagent.conversation.AgentRunRequest;
import ltd.qianc.iotagent.conversation.ConversationEventFact;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.conversation.ConversationRecord;
import ltd.qianc.iotagent.conversation.ConversationStore;
import ltd.qianc.iotagent.conversation.ConversationTurn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDraftFact;
import ltd.qianc.iotagent.action.ActionDraftStore;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import org.junit.jupiter.api.Test;

/**
 * Public consumer regression for the actual persisted/streamed action event:
 * replay must expose the full draft card exactly once and survive a fresh facade.
 */
class ConversationReplayDraftContractTest {
    private static final PrincipalInfo PRINCIPAL =
            new PrincipalInfo("Bearer test-token", "TENANT-1", "owner-1", "fixture");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void retentionFollowsLastActivityAndExpiredCurrentStartsFreshConversation() {
        MemoryStore store = new MemoryStore();
        RecordingBridge bridge = new RecordingBridge();
        ConversationFacade facade = new ConversationFacade(MAPPER, store, store, bridge, Runnable::run, mock(ActionDraftFacade.class), mock(ltd.qianc.iotagent.conversation.ConversationTaskCards.class));
        Instant now = Instant.now();
        ConversationRecord activeOld = store.seed("aged-conversation", PRINCIPAL.tenantCode(),
                PRINCIPAL.ownerKey(), "company", now.minus(java.time.Duration.ofHours(7)),
                now.minus(java.time.Duration.ofHours(3)));

        Map<String, Object> restored = facade.current(PRINCIPAL, "company");
        assertThat(map(restored.get("conversation"))).containsEntry("id", "aged-conversation");
        facade.message(PRINCIPAL, activeOld.id(), "公司能耗问题", "recent-active-turn", "company");
        assertThat(bridge.lastConversationId.get()).isEqualTo(activeOld.id());

        store.activity(activeOld.id(), now.minus(java.time.Duration.ofHours(7)));
        assertThat(facade.current(PRINCIPAL, "company")).containsEntry("conversation", null);
        Map<String, Object> fresh = facade.create(PRINCIPAL, "company");
        facade.message(PRINCIPAL, (String) fresh.get("id"), "新的公司能耗问题", "fresh-turn", "company");
        assertThat(bridge.lastConversationId.get()).isEqualTo(fresh.get("id")).isNotEqualTo(activeOld.id());
    }

    @Test
    void persistedActionEventReplaysAsOneDraftCardAfterRestart() throws Exception {
        MemoryStore store = new MemoryStore();
        ActionEventBridge bridge = new ActionEventBridge(store);
        ConversationFacade facade = new ConversationFacade(MAPPER, store, store, bridge, Runnable::run, mock(ActionDraftFacade.class), mock(ltd.qianc.iotagent.conversation.ConversationTaskCards.class));
        String conversationId = (String) facade.create(PRINCIPAL, "office").get("id");

        facade.message(PRINCIPAL, conversationId, "生成四条照明回路草案", "idem-draft-card", "office");

        List<ConversationEventFact> actionEvents = store.read(conversationId).stream()
                .filter(event -> "action.proposed".equals(event.type())).toList();
        assertThat(actionEvents).hasSize(1);
        assertThat(actionEvents.get(0).payloadJson()).contains("\"inventory\"")
                .contains("\"draft-1\"").doesNotContain("\"turnId\"");

        ActionDraftStore draftStore = mock(ActionDraftStore.class);
        ObjectNode latest = (ObjectNode) MAPPER.readTree(actionEvents.get(0).payloadJson());
        latest.put("tenantCode", PRINCIPAL.tenantCode()).put("ownerKey", PRINCIPAL.ownerKey())
                .put("source", "office").put("conversationId", conversationId);
        ObjectNode foreign = latest.deepCopy().put("ownerKey", "different-owner");
        when(draftStore.findByConversation(conversationId)).thenReturn(List.of(
                new ActionDraftFact("draft-1", 4, "PENDING_CONFIRMATION", latest.toString(), "key"),
                new ActionDraftFact("foreign-draft", 5, "PENDING_CONFIRMATION", foreign.toString(), "foreign")));
        ActionDraftFacade drafts = new ActionDraftFacade(MAPPER, draftStore, mock(IotAdapter.class), null,
                mock(ActionCommandGateway.class), null, null, null, null,
                org.mockito.Mockito.mock(ltd.qianc.iotagent.readmodel.ReadSnapshots.class),
                java.time.Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), java.time.ZoneOffset.UTC),
                java.time.Duration.ofSeconds(1), java.time.Duration.ofMinutes(10), null);
        assertThat(drafts.conversationDrafts(PRINCIPAL, conversationId, "office")).containsOnlyKeys("draft-1");
        ConversationFacade restarted = new ConversationFacade(MAPPER, store, store,
                new NoopBridge(), Runnable::run, drafts, mock(ltd.qianc.iotagent.conversation.ConversationTaskCards.class));
        Map<String, Object> current = restarted.current(PRINCIPAL, "office");
        List<Map<String, Object>> turns = turns(current);
        assertThat(turns).hasSize(1);
        Map<String, Object> turn = turns.get(0);
        Map<String, Object> draft = map(turn.get("draft"));
        assertThat(draft).containsEntry("draftId", "draft-1")
                .containsEntry("status", "PENDING_CONFIRMATION")
                .containsEntry("summary", "打开 4 条照明回路");
        assertThat(((Number) draft.get("revision")).longValue()).isEqualTo(4L);
        assertThat(listOfMaps(draft.get("inventory"))).hasSize(1);
        assertThat(listOfMaps(turn.get("events")).stream()
                .filter(event -> "action.proposed".equals(event.get("type"))).toList()).hasSize(1);
    }

    private static final class ActionEventBridge implements AgentEngineBridge {
        private final MemoryStore store;

        private ActionEventBridge(MemoryStore store) {
            this.store = store;
        }

        @Override
        public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> events) {
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                    "run.started", Map.of("createdAt", Instant.now().toString())));
            long sequence = store.read(request.conversationId()).stream()
                    .filter(event -> event.turnId().equals(request.turnId()))
                    .mapToLong(ConversationEventFact::sequence).max().orElse(0) + 1;
            // Deliberately mirrors the old persisted action payload without turnId:
            // replay must use the event row's turn binding, not client/event text.
            store.append(new ConversationEventFact(request.conversationId(), request.turnId(), sequence,
                    "action.proposed", draftEvent(request).toString(), "draft-1:action.proposed:1"));
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                    "tool.started", Map.of("tool", "propose_action_draft", "toolCallId", "call-1",
                            "label", "生成操作草案")));
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                    "tool.completed", Map.of("tool", "propose_action_draft", "toolCallId", "call-1",
                            "label", "生成操作草案", "status", "completed")));
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                    "run.completed", Map.of("outcome", "message")));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancel(String runId) {
            return CompletableFuture.completedFuture(null);
        }

        private static ObjectNode draftEvent(AgentRunRequest request) {
            ObjectNode event = MAPPER.createObjectNode();
            event.put("type", "action.proposed");
            event.put("draftId", "draft-1");
            event.put("status", "PENDING_CONFIRMATION");
            event.put("revision", 1);
            event.put("expiresAt", "2026-11-01T22:47:08Z");
            event.put("summary", "打开 4 条照明回路");
            event.put("conversationId", request.conversationId());
            event.putArray("selectedActionIds").add("light-1");
            ObjectNode action = event.putArray("actions").addObject();
            action.put("id", "light-1");
            action.put("deviceId", "light-1");
            action.put("kind", "lighting");
            action.putObject("command").put("power", "on");
            ObjectNode inventory = event.putArray("inventory").addObject();
            inventory.put("deviceId", "light-1");
            inventory.put("kind", "lighting");
            inventory.put("name", "照明回路 1");
            inventory.put("location", "26楼办公区");
            inventory.put("disposition", "operate");
            ObjectNode counts = event.putObject("counts");
            counts.put("total", 1);
            counts.put("operate", 1);
            counts.put("excluded", 0);
            counts.put("satisfied", 0);
            counts.put("untouched", 0);
            return event;
        }
    }

    private static final class RecordingBridge implements AgentEngineBridge {
        private final java.util.concurrent.atomic.AtomicReference<String> lastConversationId =
                new java.util.concurrent.atomic.AtomicReference<>();

        @Override
        public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> events) {
            lastConversationId.set(request.conversationId());
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                    "run.completed", Map.of("outcome", "message")));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancel(String runId) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class NoopBridge implements AgentEngineBridge {
        @Override
        public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> events) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancel(String runId) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class MemoryStore implements ConversationStore, ConversationEventStore {
        private final Map<String, ConversationRecord> records = new LinkedHashMap<>();
        private final Map<String, List<ConversationTurn>> turns = new LinkedHashMap<>();
        private final List<ConversationEventFact> events = new ArrayList<>();

        @Override
        public ConversationRecord create(String tenantCode, String ownerKey, String source, Instant createdAt) {
            ConversationRecord record = new ConversationRecord(UUID.randomUUID().toString(), tenantCode,
                    ownerKey, source, createdAt, createdAt, false);
            records.put(record.id(), record);
            turns.put(record.id(), new ArrayList<>());
            return record;
        }

        private ConversationRecord seed(String id, String tenantCode, String ownerKey, String source,
                Instant createdAt, Instant lastActiveAt) {
            ConversationRecord record = new ConversationRecord(id, tenantCode, ownerKey, source,
                    createdAt, lastActiveAt, false);
            records.put(id, record);
            turns.put(id, new ArrayList<>());
            return record;
        }

        private void activity(String id, Instant lastActiveAt) {
            ConversationRecord existing = records.get(id);
            if (existing == null) return;
            records.put(id, new ConversationRecord(existing.id(), existing.tenantCode(), existing.ownerKey(),
                    existing.source(), existing.createdAt(), lastActiveAt, existing.cleared()));
        }

        @Override
        public Optional<ConversationRecord> find(String conversationId, String tenantCode, String ownerKey) {
            return Optional.ofNullable(records.get(conversationId))
                    .filter(record -> record.tenantCode().equals(tenantCode) && record.ownerKey().equals(ownerKey));
        }

        @Override
        public Optional<ConversationRecord> current(String tenantCode, String ownerKey, String source,
                Instant activeAfter) {
            return records.values().stream()
                    .filter(record -> record.tenantCode().equals(tenantCode)
                            && record.ownerKey().equals(ownerKey) && record.source().equals(source)
                            && !record.cleared() && record.lastActiveAt().isAfter(activeAfter))
                    .reduce((first, second) -> second);
        }

        @Override
        public ConversationTurn startTurn(String conversationId, String content, Instant startedAt,
                String idempotencyKey) {
            ConversationTurn turn = new ConversationTurn(UUID.randomUUID().toString(), conversationId,
                    content, startedAt, idempotencyKey);
            turns.computeIfAbsent(conversationId, ignored -> new ArrayList<>()).add(turn);
            ConversationRecord existing = records.get(conversationId);
            if (existing != null) records.put(conversationId, new ConversationRecord(existing.id(),
                    existing.tenantCode(), existing.ownerKey(), existing.source(), existing.createdAt(),
                    startedAt, existing.cleared()));
            return turn;
        }

        @Override
        public Optional<ConversationTurn> findTurnByIdempotencyKey(String conversationId, String idempotencyKey) {
            return turns.getOrDefault(conversationId, List.of()).stream()
                    .filter(turn -> idempotencyKey.equals(turn.idempotencyKey())).findFirst();
        }

        @Override
        public Optional<ConversationTurn> latestTurn(String conversationId) {
            List<ConversationTurn> list = turns.getOrDefault(conversationId, List.of());
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
        }

        @Override
        public List<ConversationTurn> turns(String conversationId) {
            return List.copyOf(turns.getOrDefault(conversationId, List.of()));
        }

        @Override
        public void clear(String conversationId) {
            turns.remove(conversationId);
            events.removeIf(event -> event.conversationId().equals(conversationId));
        }

        @Override
        public void discardTurn(String conversationId, String turnId) {
            turns.getOrDefault(conversationId, new ArrayList<>()).removeIf(turn -> turn.id().equals(turnId));
            events.removeIf(event -> event.conversationId().equals(conversationId)
                    && event.turnId().equals(turnId));
        }

        @Override
        public ConversationEventFact append(ConversationEventFact event) {
            Optional<ConversationEventFact> existing = findByIdempotencyKey(event.conversationId(),
                    event.idempotencyKey());
            if (existing.isPresent()) return existing.get();
            events.add(event);
            return event;
        }

        @Override
        public List<ConversationEventFact> read(String conversationId) {
            return events.stream().filter(event -> event.conversationId().equals(conversationId))
                    .sorted(java.util.Comparator.comparingLong(ConversationEventFact::sequence)).toList();
        }

        @Override
        public Optional<ConversationEventFact> findByIdempotencyKey(String conversationId, String idempotencyKey) {
            return events.stream().filter(event -> event.conversationId().equals(conversationId)
                    && event.idempotencyKey().equals(idempotencyKey)).findFirst();
        }
    }

    private static List<Map<String, Object>> turns(Map<String, Object> current) {
        return listOfMaps(map(current.get("conversation")).get("turns"));
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
