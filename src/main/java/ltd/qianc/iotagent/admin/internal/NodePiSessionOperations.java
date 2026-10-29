package ltd.qianc.iotagent.admin.internal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ltd.qianc.iotagent.admin.PiSessionOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;

/** Calls only the private Node sidecar operations boundary; it never owns Pi facts. */
public final class NodePiSessionOperations implements PiSessionOperations {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private final WebClient client;
    private final String bridgeToken;

    public NodePiSessionOperations(WebClient client, String bridgeToken) {
        this.client = Objects.requireNonNull(client, "client");
        this.bridgeToken = bridgeToken == null ? "" : bridgeToken;
    }

    @Override
    public OperationResult capacity() {
        return call("/internal/pi-session/capacity", Map.of(), "PI_SESSION_CAPACITY_FAILED");
    }

    @Override
    public OperationResult integrityCheck() {
        return call("/internal/pi-session/integrity-check", Map.of(), "PI_SESSION_INTEGRITY_FAILED");
    }

    @Override
    public OperationResult recoverWriterLease() {
        return call("/internal/pi-session/writer-lease/recover", Map.of(), "PI_SESSION_WRITER_RECOVERY_FAILED");
    }

    @Override
    public OperationResult backup(Path destination, boolean force) {
        return call("/internal/pi-session/backup", Map.of("destination", destination.toString(), "force", force),
                "PI_SESSION_BACKUP_FAILED");
    }

    @Override
    public OperationResult restore(Path backup, boolean apply) {
        return call("/internal/pi-session/restore", Map.of("backup", backup.toString(), "apply", apply),
                "PI_SESSION_RESTORE_FAILED");
    }

    private OperationResult call(String path, Map<String, Object> body, String failureCode) {
        if (bridgeToken.isBlank()) return OperationResult.unavailable();
        try {
            var response = client.post().uri(path)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header("X-Agent-Bridge-Token", bridgeToken)
                    .bodyValue(body)
                    .exchangeToMono(result -> result.bodyToMono(Map.class)
                            .defaultIfEmpty(Map.of())
                            .map(payload -> new BridgeResult(result.statusCode(), payload)))
                    .block(TIMEOUT);
            if (response == null) return OperationResult.unavailable();
            if (!response.status().is2xxSuccessful()) {
                return new OperationResult(response.status().value(), failureCode, "Pi Session 运维操作未完成", Map.of());
            }
            return new OperationResult(200, text(response.body(), "code", "PI_SESSION_OK"),
                    "Pi Session 运维操作完成", safeDetails(response.body()));
        } catch (Exception ignored) {
            return new OperationResult(503, failureCode, "Pi Session sidecar 运维能力不可用", Map.of());
        }
    }

    private static Map<String, Object> safeDetails(Map<?, ?> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : new String[] {"status", "integrity", "freeBytes", "minimumFreeBytes", "applied", "recoveredLeases"}) {
            Object value = body.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private static String text(Map<?, ?> body, String key, String fallback) {
        Object value = body.get(key);
        return value instanceof String text && !text.isBlank() && text.matches("[A-Z0-9_]+") ? text : fallback;
    }

    private record BridgeResult(HttpStatusCode status, Map<?, ?> body) {
    }
}
