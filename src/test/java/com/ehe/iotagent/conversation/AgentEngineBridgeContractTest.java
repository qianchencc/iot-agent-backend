package com.ehe.iotagent.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.ehe.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;

class AgentEngineBridgeContractTest {
    @Test
    void aDifferentRunsStreamCannotPublishTextIntoTheCurrentConversation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/run", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"type\":\"message.delta\",\"runId\":\"another-run\","
                    + "\"conversationId\":\"conversation-1\",\"turnId\":\"turn-1\","
                    + "\"content\":\"FOREIGN_PRIVATE_ANSWER\"}\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (ExecutorService executor = new ThreadPoolExecutor(0, 4, 10, TimeUnit.SECONDS,
                     new SynchronousQueue<>(), Thread.ofVirtual().factory(), new ThreadPoolExecutor.AbortPolicy());
             ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1)) {
            List<AgentEngineEvent> events = new ArrayList<>();
            AgentRunPolicyProvider policies = request -> new AgentRunPolicy(Map.of(
                    "protocolVersion", 1, "runId", request.runId(), "conversationId", request.conversationId(),
                    "turnId", request.turnId(), "toolContextId", request.toolContextId(), "content", request.content(),
                    "sessionScope", "isolated-owner", "definition", Map.of("instructions", "Answer the request.",
                            "tools", List.of(), "maxTurns", 1)), event -> List.of(event));
            AgentEngineBridge bridge = AgentEngineBridgeFactory.http(new ObjectMapper(),
                    "http://127.0.0.1:" + server.getAddress().getPort(), "private-bridge-token", executor,
                    Duration.ofSeconds(5), scheduler, policies);
            var completion = bridge.start(new AgentRunRequest("run-1", "conversation-1", "turn-1",
                    "office", "query", "tenant-1", null, null, "context-1"), events::add).toCompletableFuture();

            assertThatThrownBy(completion::join).isInstanceOfSatisfying(CompletionException.class, failure ->
                    assertThat(failure.getCause()).isInstanceOfSatisfying(BusinessException.class, business ->
                            assertThat(business.code()).isEqualTo("AGENT_ENGINE_CONTEXT_MISMATCH")));
            assertThat(events).noneMatch(event -> "message.delta".equals(event.type()));
        } finally {
            server.stop(0);
        }
    }
}
