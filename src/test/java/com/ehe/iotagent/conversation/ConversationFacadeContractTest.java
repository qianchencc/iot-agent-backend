package com.ehe.iotagent.conversation;

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
import com.ehe.iotagent.auth.PrincipalInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ehe.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;

class ConversationFacadeContractTest {

    private static final PrincipalInfo PRINCIPAL = new PrincipalInfo("Bearer test-token", "TENANT-1");

    @Test
    void confirmationRecoveryKeepsOriginalTurnAndCannotDuplicateDecisionRun() {
        MemoryConversationStore store = new MemoryConversationStore();
        MemoryEventStore events = new MemoryEventStore();
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<ConversationFacade> owner = new AtomicReference<>();
        AgentEngineBridge bridge = new AgentEngineBridge() {
            public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> sink) {
                runs.incrementAndGet();
                boolean continuation = owner.get().isContinuation(request.toolContextId(),
                        request.conversationId(), request.turnId(), request.runId());
                assertThat(continuation).isEqualTo(runs.get() == 2);
                sink.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                        "run.completed", Map.of()));
                return CompletableFuture.completedFuture(null);
            }
            public CompletionStage<Void> cancel(String id) { return CompletableFuture.completedFuture(null); }
        };
        ConversationFacade facade = new ConversationFacade(new ObjectMapper(), store, events, bridge,
                Runnable::run, mock(ConversationDrafts.class), (p, id, source) -> Map.of("status", "EXPIRED"));
        owner.set(facade);
        String id = (String) facade.create(PRINCIPAL, "company").get("id");
        facade.message(PRINCIPAL, id, "三分钟后关闭公司的灯", "original", "company");
        assertThat(facade.continueTaskConfirmation(PRINCIPAL, id, "turn-1", "task-1", "company",
                "TASK_TIME_PASSED", "原定时间已过，需要新的执行时间")).isTrue();
        assertThat(facade.continueTaskConfirmation(PRINCIPAL, id, "turn-1", "task-1", "company",
                "TASK_TIME_PASSED", "原定时间已过，需要新的执行时间")).isFalse();
        assertThat(store.turns(id)).extracting(ConversationTurn::content).containsExactly("三分钟后关闭公司的灯");
        assertThat(runs.get()).isEqualTo(2);
        assertThat(events.read(id)).extracting(ConversationEventFact::sequence).containsExactly(1L, 2L, 3L);
    }

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
    void retryingUneditedTextAfterLocalPublicationStartsANewRunInsteadOfReplayingFailure() {
        try (var local = new com.ehe.iotagent.iot.EnergyHistoryFixture()) {
            MemoryConversationStore store = new MemoryConversationStore();
            MemoryEventStore events = new MemoryEventStore();
            AtomicInteger attempts = new AtomicInteger();
            AgentEngineBridge bridge = new AgentEngineBridge() {
                public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> sink) {
                    attempts.incrementAndGet();
                    try {
                        local.directory.loadScope(local.identity());
                        sink.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(),
                                "run.completed", Map.of()));
                        return CompletableFuture.completedFuture(null);
                    } catch (BusinessException pending) {
                        return CompletableFuture.failedFuture(pending);
                    }
                }
                public CompletionStage<Void> cancel(String id) { return CompletableFuture.completedFuture(null); }
            };
            ConversationFacade facade = new ConversationFacade(local.mapper, store, events, bridge,
                    Runnable::run, mock(ConversationDrafts.class), mock(ConversationTaskCards.class));
            String id = (String) facade.create(PRINCIPAL, "company").get("id");
            for (int attempt = 0; attempt < 2; attempt++) {
                facade.message(PRINCIPAL, id, "你好", null, "company");
                assertThat(events.values.getLast().payloadJson()).contains("IOT_SCOPE_PREPARING");
                facade.discard(PRINCIPAL, id, store.latestTurn(id).orElseThrow().id(), "company");
            }
            local.publish();
            int sourceCalls = local.directoryCalls.get();
            facade.message(PRINCIPAL, id, "你好", null, "company");
            assertThat(events.values.getLast().type()).isEqualTo("run.completed");
            assertThat(store.turns(id)).extracting(ConversationTurn::content).containsExactly("你好");
            assertThat(attempts.get()).isEqualTo(3);
            assertThat(local.directoryCalls.get()).isEqualTo(sourceCalls);
        }
    }

    @Test
    void restoresAllTurnsFromOneCoherentEventSnapshot() {
        MemoryConversationStore conversations = new MemoryConversationStore();
        AtomicInteger reads = new AtomicInteger();
        ConversationEventFact first = new ConversationEventFact("conversation-1", "turn-1", 1,
                "message.delta", "{\"sequence\":1,\"content\":\"第一轮\"}", "first");
        ConversationEventFact second = new ConversationEventFact("conversation-1", "turn-2", 1,
                "message.delta", "{\"sequence\":1,\"content\":\"第二轮\"}", "second");
        ConversationEventFact later = new ConversationEventFact("conversation-1", "turn-2", 2,
                "message.delta", "{\"sequence\":2,\"content\":\"随后到达\"}", "later");
        ConversationEventStore events = new ConversationEventStore() {
            public ConversationEventFact append(ConversationEventFact event) { throw new UnsupportedOperationException(); }
            public Optional<ConversationEventFact> findByIdempotencyKey(String id, String key) { return Optional.empty(); }
            public List<ConversationEventFact> read(String id) {
                return reads.getAndIncrement() == 0 ? List.of(second, first) : List.of(first, second, later);
            }
        };
        ConversationFacade facade = new ConversationFacade(new ObjectMapper(), conversations, events, null, Runnable::run,
                mock(ConversationDrafts.class), mock(ConversationTaskCards.class));
        String id = (String) facade.create(PRINCIPAL, "office").get("id");
        conversations.startTurn(id, "第一问", Instant.now(), "q1");
        conversations.startTurn(id, "第二问", Instant.now(), "q2");

        Map<?, ?> result = (Map<?, ?>) facade.current(PRINCIPAL, "office").get("conversation");
        List<?> turns = (List<?>) result.get("turns");
        assertThat(turns).hasSize(2);
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.valueToTree(((Map<?, ?>) turns.get(0)).get("events")).toString())
                .isEqualTo("[{\"sequence\":1,\"content\":\"第一轮\"}]");
        assertThat(mapper.valueToTree(((Map<?, ?>) turns.get(1)).get("events")).toString())
                .isEqualTo("[{\"sequence\":1,\"content\":\"第二轮\"}]");
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
        private int nextTurn;

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
            ConversationTurn turn = new ConversationTurn("turn-" + (++nextTurn), conversationId, content, startedAt, idempotencyKey);
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
