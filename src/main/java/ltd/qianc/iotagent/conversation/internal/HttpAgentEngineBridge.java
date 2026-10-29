package ltd.qianc.iotagent.conversation.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import ltd.qianc.iotagent.conversation.AgentEngineBridge;
import ltd.qianc.iotagent.conversation.AgentEngineEvent;
import ltd.qianc.iotagent.conversation.AgentRunRequest;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;

/**
 * HTTP/NDJSON adapter for the private Node Agent Engine. The adapter forwards
 * only the stable public event union; pi.* implementation events never escape.
 */
public final class HttpAgentEngineBridge implements AgentEngineBridge {

    private static final String[] PUBLIC_PREFIXES = {"run.", "message.", "tool.", "action.", "task.", "observation."};

    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final Executor executor;
    private final HttpClient client;
    private final Duration runTimeout;
    private final ScheduledExecutorService scheduler;
    private final String bridgeToken;

    public HttpAgentEngineBridge(
            ObjectMapper objectMapper,
            @Value("${agent.bridge.base-url:}") String baseUrl,
            Executor executor) {
        this(objectMapper, baseUrl, "", executor, Duration.ofMinutes(2));
    }

    public HttpAgentEngineBridge(
            ObjectMapper objectMapper,
            String baseUrl,
            Executor executor,
            Duration runTimeout) {
        this(objectMapper, baseUrl, "", executor, runTimeout);
    }

    public HttpAgentEngineBridge(
            ObjectMapper objectMapper,
            String baseUrl,
            Executor executor,
            Duration runTimeout,
            ScheduledExecutorService scheduler) {
        this(objectMapper, baseUrl, "", executor, runTimeout, scheduler);
    }

    public HttpAgentEngineBridge(
            ObjectMapper objectMapper,
            String baseUrl,
            String bridgeToken,
            Executor executor,
            Duration runTimeout) {
        this(objectMapper, baseUrl, bridgeToken, executor, runTimeout,
                new ScheduledThreadPoolExecutor(1, runnable -> {
                    Thread thread = new Thread(runnable, "agent-bridge-timeout");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    public HttpAgentEngineBridge(
            ObjectMapper objectMapper,
            String baseUrl,
            String bridgeToken,
            Executor executor,
            Duration runTimeout,
            ScheduledExecutorService scheduler) {
        this.objectMapper = objectMapper;
        this.baseUri = baseUrl == null || baseUrl.isBlank() ? null
                : URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.bridgeToken = bridgeToken == null ? "" : bridgeToken.trim();
        this.executor = executor;
        this.runTimeout = requireTimeout(runTimeout);
        this.scheduler = scheduler;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).executor(executor).build();
    }


    @Override
    public CompletionStage<Void> start(AgentRunRequest request, Consumer<AgentEngineEvent> events) {
        if (baseUri == null || bridgeToken.isBlank()) return CompletableFuture.failedFuture(unavailable());
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("runId", request.runId());
            requestBody.put("conversationId", request.conversationId());
            requestBody.put("turnId", request.turnId());
            requestBody.put("source", request.source());
            requestBody.put("content", request.content());
            requestBody.put("tenantCode", request.tenantCode());
            if (request.focusRegionId() != null) requestBody.put("focusRegionId", request.focusRegionId());
            if (request.reference() != null) requestBody.put("reference", request.reference());
            String body = objectMapper.writeValueAsString(requestBody);
            HttpRequest httpRequest = HttpRequest.newBuilder(baseUri.resolve("run"))
                    .timeout(runTimeout)
                    .header("content-type", "application/json")
                    .header("accept", "application/x-ndjson")
                    .header("authorization", "Bearer " + bridgeToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            CompletableFuture<Void> result = new CompletableFuture<>();
            AtomicReference<InputStream> bodyRef = new AtomicReference<>();
            AtomicBoolean timedOut = new AtomicBoolean();
            ScheduledFuture<?> watchdog = scheduler.schedule(() -> {
                timedOut.set(true);
                InputStream bodyStream = bodyRef.get();
                if (bodyStream != null) {
                    try { bodyStream.close(); } catch (Exception ignored) { }
                }
                result.completeExceptionally(timeout());
            }, runTimeout.toMillis(), TimeUnit.MILLISECONDS);
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .whenCompleteAsync((response, failure) -> {
                        if (failure != null) {
                            watchdog.cancel(false);
                            result.completeExceptionally(unavailable());
                            return;
                        }
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            watchdog.cancel(false);
                            try { response.body().close(); } catch (Exception ignored) { }
                            result.completeExceptionally(new BusinessException("AGENT_ENGINE_UNAVAILABLE", "Agent Engine 暂不可用，请稍后重试", 503));
                            return;
                        }
                        bodyRef.set(response.body());
                        if (timedOut.get()) {
                            try { response.body().close(); } catch (Exception ignored) { }
                            return;
                        }
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                JsonNode node = objectMapper.readTree(line);
                                String type = text(node, "type");
                                if ("heartbeat".equals(type)) {
                                    events.accept(new AgentEngineEvent(request.runId(), request.conversationId(), request.turnId(), type, Map.of()));
                                    continue;
                                }
                                if (type == null || !isPublic(type)) continue;
                                Map<String, Object> fields = objectMapper.convertValue(node, new TypeReference<>() {});
                                fields.keySet().removeAll(java.util.Set.of("type", "runId", "conversationId", "turnId",
                                        "sequence", "timestamp"));
                                events.accept(new AgentEngineEvent(
                                        textOr(node, "runId", request.runId()),
                                        textOr(node, "conversationId", request.conversationId()),
                                        textOr(node, "turnId", request.turnId()),
                                        type,
                                        fields));
                            }
                            result.complete(null);
                        } catch (Exception error) {
                            result.completeExceptionally(timedOut.get() ? timeout() : unavailable());
                        } finally {
                            watchdog.cancel(false);
                        }
                    }, executor);
            return result;
        } catch (Exception error) {
            return CompletableFuture.failedFuture(unavailable());
        }
    }

    @Override
    public CompletionStage<Void> cancel(String runId) {
        if (baseUri == null || bridgeToken.isBlank()) return CompletableFuture.failedFuture(unavailable());
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("cancel/" + encodePathSegment(runId)))
                .timeout(Duration.ofSeconds(2))
                .header("accept", "application/json")
                .header("authorization", "Bearer " + bridgeToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() >= 500) throw unavailable();
                });
    }

    private static boolean isPublic(String type) {
        for (String prefix : PUBLIC_PREFIXES) if (type.startsWith(prefix)) return true;
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Duration requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("runTimeout must be positive");
        }
        return timeout;
    }

    private static BusinessException timeout() {
        return new BusinessException("AGENT_ENGINE_TIMEOUT", "Agent Engine 运行超时，请稍后重试", 504);
    }
    private static BusinessException unavailable() {
        return new BusinessException("AGENT_ENGINE_UNAVAILABLE", "Agent Engine 暂不可用，请稍后重试", 503);
    }

}
