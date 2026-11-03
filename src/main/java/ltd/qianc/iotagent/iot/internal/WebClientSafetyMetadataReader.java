package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.internal.SafetyLedgerComposer.MetadataSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Current safety device metadata read from the iot-center device detail behind the
 * authorized safety scope's hub binding. Mirrors the Runtime reader: one bounded
 * retry on transient upstream failure, authorization failures stay typed, and any
 * other miss reports an honestly unverified device instead of failing the ledger.
 */
public final class WebClientSafetyMetadataReader implements MetadataSource {
    private static final long RETRY_DELAY_MILLIS = 500;

    private final WebClient webClient;
    private final String detailPath;
    private final Duration timeout;

    public WebClientSafetyMetadataReader(WebClient webClient, String detailPath, Duration timeout) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.detailPath = Objects.requireNonNull(detailPath, "detailPath");
        if (detailPath.isBlank() || !detailPath.startsWith("/")) {
            throw new IllegalArgumentException("safety detail path must be absolute");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    @Override
    public Mono<Map<String, Object>> currentDevice(RequestIdentity identity, String hubDeviceId) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(hubDeviceId, "hubDeviceId");
        return Mono.defer(() -> webClient.get()
                        .uri(builder -> builder.path(detailPath + "/{hubDeviceId}").build(hubDeviceId))
                        .headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                        .exchangeToMono(response -> decode(response, hubDeviceId)))
                .timeout(timeout)
                .retryWhen(Retry.backoff(1, Duration.ofMillis(RETRY_DELAY_MILLIS))
                        .filter(error -> !(error instanceof GatewayAuthorizationException))
                        .maxBackoff(Duration.ofMillis(RETRY_DELAY_MILLIS)))
                .onErrorResume(error -> error instanceof GatewayAuthorizationException
                        ? Mono.error(error)
                        : Mono.just(unknown()));
    }

    private Mono<Map<String, Object>> decode(ClientResponse response, String hubDeviceId) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) {
            return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status.value())));
        }
        if (!status.is2xxSuccessful()) {
            return response.releaseBody().then(Mono.error(
                    new IllegalStateException("safety metadata upstream status " + status.value())));
        }
        return response.bodyToMono(JsonNode.class)
                .map(body -> parse(body, hubDeviceId))
                .defaultIfEmpty(unknown());
    }

    private static Map<String, Object> parse(JsonNode body, String hubDeviceId) {
        if (!body.isObject()) return unknown();
        JsonNode code = body.get("code");
        if (code != null && code.isNumber() && (code.asInt() == 401 || code.asInt() == 403)) {
            throw new GatewayAuthorizationException(code.asInt());
        }
        JsonNode content = body.get("content");
        if (code == null || !code.isNumber() || (code.asInt() != 0 && code.asInt() != 200)
                || content == null || !content.isObject()) {
            return unknown();
        }
        JsonNode id = content.get("id");
        if (!id.isTextual() || !hubDeviceId.equals(id.asText())) return unknown();
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("name", blankToNull(text(content.get("name"))));
        device.put("location", blankToNull(text(content.get("installAddress"))));
        return device;
    }

    private static Map<String, Object> unknown() {
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("name", null);
        unknown.put("location", null);
        return unknown;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
