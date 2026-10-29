package ltd.qianc.iotagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import ltd.qianc.iotagent.conversation.AgentEngineBridge;
import ltd.qianc.iotagent.conversation.AgentEngineEvent;
import ltd.qianc.iotagent.conversation.AgentRunRequest;
import ltd.qianc.iotagent.conversation.ConversationEventFact;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.conversation.ConversationRecord;
import ltd.qianc.iotagent.conversation.ConversationStore;
import ltd.qianc.iotagent.conversation.ConversationTurn;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Local HTTP contract fixture. It exercises the Java controller and facade with
 * in-memory adapters; it never starts or contacts the Runtime service.
 */
@SpringBootTest(properties = {
        "iot.identity.verify=false",
        "agent.storage.mysql.enabled=false",
        "agent.heartbeat-ms=0",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@Import(RuntimeContractGateTest.FixtureConfiguration.class)
class RuntimeContractGateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FixtureBridge bridge;

    @Test
    void requiresPublicBoundaryHeaders() throws Exception {
        mockMvc.perform(get("/api/agent/conversations/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void createsConversationWithSourceAndRunsNdjsonToTerminal() throws Exception {
        var create = mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", "Bearer fixture")
                        .header("Tenantcode", "FIXTURE")
                        .contentType("application/json")
                        .content("{\"source\":\"company\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("company"))
                .andReturn();
        String id = new ObjectMapper().readTree(create.getResponse().getContentAsString()).get("id").asText();

        var initial = mockMvc.perform(post("/api/agent/conversations/" + id + "/messages")
                        .queryParam("source", "company")
                        .header("Authorization", "Bearer fixture")
                        .header("Tenantcode", "FIXTURE")
                        .header("Idempotency-Key", "fixture-1")
                        .contentType("application/json")
                        .accept("application/x-ndjson")
                        .content("{\"content\":\"查询状态\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        var result = mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn();

        List<String> lines = result.getResponse().getContentAsString().lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines).allMatch(line -> line.startsWith("{") && line.endsWith("}"));
        assertThat(lines).extracting(line -> parse(line).get("sequence").asLong())
                .containsExactly(1L, 2L, 3L);
        assertThat(lines).extracting(line -> parse(line).get("type").asText())
                .containsExactly("run.started", "message.delta", "run.completed");
        assertThat(parse(lines.get(2)).get("type").asText()).isIn("run.completed", "run.awaiting_confirmation", "run.failed", "run.aborted");
        assertThat(bridge.request.get().source()).isEqualTo("company");
    }

    @Test
    void rejectsInvalidSourceAndCompanyOnlyFocusContext() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .queryParam("source", "unknown")
                        .header("Authorization", "Bearer fixture")
                        .header("Tenantcode", "FIXTURE")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_SOURCE"));

        String id = new ObjectMapper().readTree(mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", "Bearer fixture").header("Tenantcode", "FIXTURE")
                        .contentType("application/json").content("{\"source\":\"company\"}"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(post("/api/agent/conversations/" + id + "/messages")
                        .queryParam("source", "company")
                        .header("Authorization", "Bearer fixture").header("Tenantcode", "FIXTURE")
                        .contentType("application/json").content("{\"content\":\"x\",\"focusRegionId\":\"r1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_CONTEXT_UNAVAILABLE"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean @Primary FixtureStore fixtureStore() { return new FixtureStore(); }
        @Bean ConversationStore fixtureConversationStore(FixtureStore store) { return store; }
        @Bean ConversationEventStore fixtureConversationEventStore(FixtureStore store) { return store; }
        @Bean FixtureBridge fixtureBridge() { return new FixtureBridge(); }
        @Bean @Primary AgentEngineBridge agentEngineBridge(FixtureBridge bridge) { return bridge; }
    }

    static final class FixtureBridge implements AgentEngineBridge {
        final AtomicReference<AgentRunRequest> request = new AtomicReference<>();
        @Override public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> events) {
            this.request.set(request);
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(), "message.delta", Map.of("content", "状态")));
            events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(), "run.completed", Map.of("outcome", "message")));
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<Void> cancel(String runId) { return CompletableFuture.completedFuture(null); }
    }

    static final class FixtureStore implements ConversationStore, ConversationEventStore {
        private ConversationRecord conversation;
        private final List<ConversationTurn> turns = new ArrayList<>();
        private final List<ConversationEventFact> events = new ArrayList<>();
        @Override public ConversationRecord create(String tenant, String owner, String source, Instant at) {
            conversation = new ConversationRecord("fixture-conversation", tenant, owner, source, at, false); return conversation;
        }
        @Override public Optional<ConversationRecord> find(String id, String tenant, String owner) {
            return conversation != null && conversation.id().equals(id) && conversation.tenantCode().equals(tenant) && conversation.ownerKey().equals(owner) ? Optional.of(conversation) : Optional.empty();
        }
        @Override public Optional<ConversationRecord> current(String tenant, String owner, String source, Instant after) { return Optional.ofNullable(conversation); }
        @Override public ConversationTurn startTurn(String id, String content, Instant at, String key) { ConversationTurn turn = new ConversationTurn("fixture-turn-" + (turns.size() + 1), id, content, at, key); turns.add(turn); return turn; }
        @Override public Optional<ConversationTurn> findTurnByIdempotencyKey(String id, String key) { return turns.stream().filter(t -> t.conversationId().equals(id) && t.idempotencyKey().equals(key)).findFirst(); }
        @Override public Optional<ConversationTurn> latestTurn(String id) { return turns.stream().filter(t -> t.conversationId().equals(id)).reduce((a, b) -> b); }
        @Override public List<ConversationTurn> turns(String id) { return turns.stream().filter(t -> t.conversationId().equals(id)).toList(); }
        @Override public void clear(String id) { turns.clear(); events.clear(); }
        @Override public void discardTurn(String id, String turnId) { turns.removeIf(t -> t.id().equals(turnId)); }
        @Override public ConversationEventFact append(ConversationEventFact event) { events.add(event); return event; }
        @Override public List<ConversationEventFact> read(String id) { return events.stream().filter(e -> e.conversationId().equals(id)).toList(); }
        @Override public Optional<ConversationEventFact> findByIdempotencyKey(String id, String key) { return Optional.empty(); }
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String line) {
        try { return new ObjectMapper().readTree(line); } catch (Exception e) { throw new AssertionError(e); }
    }
}
