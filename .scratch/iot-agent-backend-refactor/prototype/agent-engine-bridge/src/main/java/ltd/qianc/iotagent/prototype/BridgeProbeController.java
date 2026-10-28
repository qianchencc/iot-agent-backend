package ltd.qianc.iotagent.prototype;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/prototype/bridge")
public class BridgeProbeController {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final URI nodeBaseUrl;

    public BridgeProbeController(
            ObjectMapper objectMapper,
            @Value("${bridge.node-base-url:http://127.0.0.1:3211}") String nodeBaseUrl) {
        this.objectMapper = objectMapper;
        this.nodeBaseUrl = URI.create(nodeBaseUrl);
    }

    @GetMapping("/healthz")
    public Map<String, String> healthz() {
        return Map.of("service", "agent-engine-bridge-probe", "status", "ok");
    }

    @PostMapping(value = "/run", produces = "application/x-ndjson")
    public void run(@RequestBody RunRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/x-ndjson;charset=UTF-8");
        HttpRequest nodeRequest = jsonRequest("/run", request);
        try {
            HttpResponse<java.io.InputStream> nodeResponse = httpClient.send(
                    nodeRequest,
                    HttpResponse.BodyHandlers.ofInputStream());
            response.setStatus(nodeResponse.statusCode());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    nodeResponse.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.getWriter().write(line);
                    response.getWriter().write('\n');
                    response.getWriter().flush();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            response.sendError(503, "bridge interrupted");
        }
    }

    @PostMapping("/cancel/{runId}")
    public Map<String, Object> cancel(@PathVariable String runId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(nodeBaseUrl.resolve("/cancel/" + runId))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return Map.of("runId", runId, "nodeStatus", response.statusCode(), "body", response.body());
    }

    @PostMapping(value = "/tool", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ToolResponse tool(@RequestBody ToolRequest request) throws InterruptedException {
        if (!"echo_tool".equals(request.toolName())) {
            return new ToolResponse(false, "unknown tool", Map.of());
        }
        Object text = request.arguments().getOrDefault("text", "");
        Object rawDelay = request.arguments().getOrDefault("delayMs", 0);
        long delayMs = rawDelay instanceof Number number ? number.longValue() : 0L;
        if (delayMs > 0) {
            Thread.sleep(Math.min(delayMs, 10_000L));
        }
        return new ToolResponse(true,
                "Java tool bridge received: " + text,
                Map.of("tool", request.toolName(), "runId", request.runId(), "delayMs", delayMs));
    }

    private HttpRequest jsonRequest(String path, Object body) throws IOException {
        return HttpRequest.newBuilder(nodeBaseUrl.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
    }

    public record RunRequest(String runId, String content, String toolBridgeUrl, boolean slow) {
    }

    public record ToolRequest(String runId, String toolName, Map<String, Object> arguments) {
    }

    public record ToolResponse(boolean ok, String text, Map<String, Object> details) {
    }
}
