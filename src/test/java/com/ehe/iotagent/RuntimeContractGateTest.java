package com.ehe.iotagent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import com.ehe.iotagent.conversation.AgentEngineBridge;
import com.ehe.iotagent.conversation.AgentEngineEvent;
import com.ehe.iotagent.conversation.AgentRunRequest;
import com.ehe.iotagent.conversation.ConversationEventFact;
import com.ehe.iotagent.conversation.ConversationEventStore;
import com.ehe.iotagent.conversation.ConversationRecord;
import com.ehe.iotagent.conversation.ConversationStore;
import com.ehe.iotagent.conversation.ConversationTurn;
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

    @Test
    void requiresPublicBoundaryHeaders() throws Exception {
        mockMvc.perform(get("/api/agent/conversations/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }


    @Test
    void rejectsInvalidSource() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .queryParam("source", "unknown")
                        .header("Authorization", "Bearer fixture")
                        .header("Tenantcode", "FIXTURE")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_SOURCE"));
    }

    @Test
    void rejectsNonStringSourceInConversationBodyInsteadOfFailingInternally() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", "Bearer fixture")
                        .header("Tenantcode", "FIXTURE")
                        .contentType("application/json")
                        .content("{\"source\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_SOURCE"));
    }

    @Test
    void companySourceInBodyCanCreateConversationWithoutDraftAdmission() throws Exception {
        mockMvc.perform(post("/api/agent/conversations")
                        .contentType("application/json").content("{\"source\":\"company\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", "Bearer fixture")
                        .header("Tenantcode", "FIXTURE")
                        .contentType("application/json").content("{\"source\":\"company\"}"))
                .andExpect(status().isCreated());
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
        @Override public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> events) {
            throw new AssertionError("Boundary rejection must not start an agent run");
        }
        @Override public CompletionStage<Void> cancel(String runId) {
            throw new AssertionError("Boundary rejection must not cancel an agent run");
        }
    }

    static final class FixtureStore implements ConversationStore, ConversationEventStore {
        private ConversationRecord conversation;
        private final List<ConversationTurn> turns = new ArrayList<>();
        private final List<ConversationEventFact> events = new ArrayList<>();
        @Override public ConversationRecord create(String tenant, String owner, String source, Instant at) {
            conversation = new ConversationRecord("fixture-conversation", tenant, owner, source, at, at, false); return conversation;
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

}
