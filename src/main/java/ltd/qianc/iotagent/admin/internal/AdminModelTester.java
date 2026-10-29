package ltd.qianc.iotagent.admin.internal;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Performs a bounded, tool-free model connectivity probe without ever logging credentials. */
public final class AdminModelTester {
    private final ObjectMapper mapper;
    private final HttpClient client;

    public AdminModelTester(ObjectMapper mapper) {
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public void test(Map<String, Object> settings) throws Exception {
        Object rawBaseUrl = settings.get("modelBaseUrl");
        Object rawModelId = settings.get("modelId");
        Object rawApiKey = settings.get("modelApiKey");
        if (!(rawBaseUrl instanceof String baseUrl) || !(rawModelId instanceof String modelId)
                || !(rawApiKey instanceof String apiKey) || baseUrl.isBlank() || modelId.isBlank() || apiKey.isBlank()) {
            throw new IllegalArgumentException("invalid model configuration");
        }
        URI base = URI.create(baseUrl);
        if (!base.isAbsolute() || !base.getScheme().matches("https?") || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) throw new IllegalArgumentException("invalid model URL");
        URI endpoint = URI.create(base.toString().replaceAll("/$", "") + "/chat/completions");
        Map<String, Object> body = Map.of("model", modelId, "messages", List.of(Map.of("role", "user", "content", "Reply OK.")),
                "stream", false, "max_tokens", 1);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("model probe failed");
    }
}
