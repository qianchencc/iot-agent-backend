package ltd.qianc.iotagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;

class ConversationFacadeContractTest {

    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer test-token", "TENANT-1");

    @Test
    void bridgeEventsArePersistedWithLocalMonotonicSequenceAndVerifiedSource() {
        MemoryConversationStore conversations = new MemoryConversationStore();
        MemoryEventStore events = new MemoryEventStore();
        RecordingBridge bridge = new RecordingBridge(false);
        ConversationFacade facade = new ConversationFacade(new ObjectMapper(), conversations, events, bridge, Runnable::run, mock(ConversationDrafts.class), mock(ConversationTaskCards.class));
        Map<String, Object> created = facade.create(PRINCIPAL, "company");

        facade.message(PRINCIPAL, (String) created.get("id"), "查询状态", "idem-1", "company");

        assertThat(bridge.request.get().source()).isEqualTo("company");
        assertThat(events.values).extracting(ConversationEventFact::sequence).containsExactly(1L, 2L, 3L);
        assertThat(events.values).extracting(ConversationEventFact::type)
                .containsExactly("run.started", "message.delta", "run.completed");
        assertThat(events.values.get(1).payloadJson()).doesNotContain("\"sequence\":99");
    }

    @Test
    void actionEventsDoNotCollideWithBridgeEventSequence() {
        MemoryConversationStore conversations = new MemoryConversationStore();
        MemoryEventStore events = new MemoryEventStore();
        RecordingBridge bridge = new RecordingBridge(false, () -> events.append(new ConversationEventFact(
                "conversation-1", "turn-1", 2L, "action.proposed", "{}", "action-event-1")));
        ConversationFacade facade = new ConversationFacade(new ObjectMapper(), conversations, events, bridge, Runnable::run, mock(ConversationDrafts.class), mock(ConversationTaskCards.class));
        String id = (String) facade.create(PRINCIPAL, "office").get("id");

        facade.message(PRINCIPAL, id, "生成控制草案", "idem-action-sequence");

        assertThat(events.values).extracting(ConversationEventFact::sequence).containsExactly(1L, 2L, 3L, 4L);
        assertThat(events.values).extracting(ConversationEventFact::type)
                .containsExactly("run.started", "action.proposed", "message.delta", "run.completed");
    }

    @Test
    void explicitCancelEmitsAbortedAndCallsEngineCancelWithoutDisconnectCancellation() {
        MemoryConversationStore conversations = new MemoryConversationStore();
        MemoryEventStore events = new MemoryEventStore();
        RecordingBridge bridge = new RecordingBridge(true);
        ConversationFacade facade = new ConversationFacade(new ObjectMapper(), conversations, events, bridge, Runnable::run, mock(ConversationDrafts.class), mock(ConversationTaskCards.class));
        String id = (String) facade.create(PRINCIPAL, "office").get("id");

        facade.message(PRINCIPAL, id, "停止前运行", "idem-cancel");
        facade.cancel(PRINCIPAL, id);

        assertThat(bridge.cancelledRunId.get()).isEqualTo(bridge.request.get().runId());
        assertThat(events.values).extracting(ConversationEventFact::type).contains("run.aborted");
    }

    @Test
    void sameIdempotencyKeyReplaysExistingTurnWithoutStartingSecondEngineRun() {
        MemoryConversationStore conversations = new MemoryConversationStore();
        MemoryEventStore events = new MemoryEventStore();
        RecordingBridge bridge = new RecordingBridge(false);
        ConversationFacade facade = new ConversationFacade(new ObjectMapper(), conversations, events, bridge, Runnable::run, mock(ConversationDrafts.class), mock(ConversationTaskCards.class));
        String id = (String) facade.create(PRINCIPAL, "office").get("id");

        facade.message(PRINCIPAL, id, "重复安全", "idem-replay");
        facade.message(PRINCIPAL, id, "重复安全", "idem-replay");

        assertThat(bridge.startCount.get()).isEqualTo(1);
        assertThat(conversations.turns(id)).hasSize(1);
    }

    @Test
    void callbacksRequireTheExactActiveRunAndAreRevokedOnCancel() {
        RecordingBridge bridge = new RecordingBridge(true);
        ConversationFacade facade = new ConversationFacade(new ObjectMapper(), new MemoryConversationStore(),
                new MemoryEventStore(), bridge, Runnable::run, mock(ConversationDrafts.class), mock(ConversationTaskCards.class));
        String id = (String) facade.create(PRINCIPAL, "company").get("id");
        facade.message(PRINCIPAL, id, "查询", "bound-run", "company", "region-1", null);
        AgentRunRequest run = bridge.request.get();
        facade.toolContext(run.toolContextId(), id, run.turnId(), run.runId());

        assertContextRejected(() -> facade.toolContext("unknown-context", id, run.turnId(), run.runId()));
        assertContextRejected(() -> facade.toolContext(run.toolContextId(), "other-conversation", run.turnId(), run.runId()));
        assertContextRejected(() -> facade.toolContext(run.toolContextId(), id, "other-turn", run.runId()));
        assertContextRejected(() -> facade.toolContext(run.toolContextId(), id, run.turnId(), "other-run"));

        facade.cancel(PRINCIPAL, id, "company");
        assertContextRejected(() -> facade.toolContext(run.toolContextId(), id, run.turnId(), run.runId()));
    }

    private static void assertContextRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable request) {
        assertThatThrownBy(request).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.code()).isEqualTo("AGENT_TOOL_RUN_UNAVAILABLE"));
    }

    private static final class RecordingBridge implements AgentEngineBridge {
        private final boolean holdOpen;
        private final Runnable beforeMessage;
        private final AtomicInteger startCount = new AtomicInteger();
        private final AtomicReference<AgentRunRequest> request = new AtomicReference<>();
        private final AtomicReference<String> cancelledRunId = new AtomicReference<>();

        private RecordingBridge(boolean holdOpen) {
            this(holdOpen, () -> {});
        }

        private RecordingBridge(boolean holdOpen, Runnable beforeMessage) {
            this.holdOpen = holdOpen;
            this.beforeMessage = beforeMessage;
        }

        @Override
        public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> events) {
            startCount.incrementAndGet();
            this.request.set(request);
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                    "run.started", Map.of("createdAt", Instant.now().toString())));
            beforeMessage.run();
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                    "message.delta", Map.of("sequence", 99, "content", "状态")));
            if (holdOpen) return new CompletableFuture<>();
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                    "run.completed", Map.of("outcome", "message")));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> cancel(String runId) {
            cancelledRunId.set(runId);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class MemoryConversationStore implements ConversationStore {
        private ConversationRecord conversation;
        private final List<ConversationTurn> values = new ArrayList<>();

        @Override
        public ConversationRecord create(String tenantCode, String ownerKey, String source, Instant createdAt) {
            conversation = new ConversationRecord("conversation-1", tenantCode, ownerKey, source, createdAt, createdAt, false);
            return conversation;
        }

        @Override
        public Optional<ConversationRecord> find(String conversationId, String tenantCode, String ownerKey) {
            return conversation != null && conversation.id().equals(conversationId)
                    && conversation.tenantCode().equals(tenantCode) && conversation.ownerKey().equals(ownerKey)
                    ? Optional.of(conversation) : Optional.empty();
        }

        @Override
        public Optional<ConversationRecord> current(String tenantCode, String ownerKey, String source, Instant activeAfter) {
            return conversation == null ? Optional.empty() : Optional.of(conversation);
        }

        @Override
        public ConversationTurn startTurn(String conversationId, String content, Instant startedAt, String idempotencyKey) {
            ConversationTurn turn = new ConversationTurn("turn-" + (values.size() + 1), conversationId, content, startedAt, idempotencyKey);
            values.add(turn);
            return turn;
        }

        @Override
        public Optional<ConversationTurn> findTurnByIdempotencyKey(String conversationId, String idempotencyKey) {
            return values.stream().filter(turn -> turn.conversationId().equals(conversationId)
                    && turn.idempotencyKey().equals(idempotencyKey)).findFirst();
        }

        @Override
        public Optional<ConversationTurn> latestTurn(String conversationId) {
            return values.stream().filter(turn -> turn.conversationId().equals(conversationId)).reduce((first, second) -> second);
        }

        @Override
        public List<ConversationTurn> turns(String conversationId) {
            return values.stream().filter(turn -> turn.conversationId().equals(conversationId)).toList();
        }

        @Override
        public void clear(String conversationId) {
            values.clear();
        }

        @Override
        public void discardTurn(String conversationId, String turnId) {
            values.removeIf(turn -> turn.id().equals(turnId));
        }
    }

    private static final class MemoryEventStore implements ConversationEventStore {
        private final List<ConversationEventFact> values = new ArrayList<>();

        @Override
        public ConversationEventFact append(ConversationEventFact event) {
            values.add(event);
            return event;
        }

        @Override
        public List<ConversationEventFact> read(String conversationId) {
            return values.stream().filter(event -> event.conversationId().equals(conversationId)).toList();
        }

        @Override
        public Optional<ConversationEventFact> findByIdempotencyKey(String conversationId, String idempotencyKey) {
            return values.stream().filter(event -> event.conversationId().equals(conversationId)
                    && event.idempotencyKey().equals(idempotencyKey)).findFirst();
        }
    }
}
