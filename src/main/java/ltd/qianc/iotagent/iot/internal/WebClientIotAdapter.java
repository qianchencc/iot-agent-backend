package ltd.qianc.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotCallPolicy;
import ltd.qianc.iotagent.iot.IotGatewayRoutes;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Protocol-neutral WebClient adapter. Route paths are supplied by configuration so
 * an unknown customer endpoint cannot be mistaken for a successful device scope.
 */
public final class WebClientIotAdapter implements IotAdapter {

    private final WebClient webClient;
    private final IotGatewayRoutes routes;
    private final Duration callTimeout;
    private final IotCallPolicy callPolicy;

    public WebClientIotAdapter(
            WebClient webClient,
            IotGatewayRoutes routes,
            Duration callTimeout,
            IotCallPolicy callPolicy) {
        this.webClient = Objects.requireNonNull(webClient, "webClient");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.callTimeout = requireTimeout(callTimeout);
        this.callPolicy = Objects.requireNonNull(callPolicy, "callPolicy");
    }

    @Override
    public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Mono<AuthorizedDeviceScope> request = webClient.get()
                .uri(routes.path(identity.source()))
                .headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                .exchangeToMono(response -> decode(response, identity));

        return callPolicy.execute("load authorized " + identity.source().wireValue() + " device scope", request)
                .timeout(callTimeout)
                .onErrorMap(
                        error -> error instanceof TimeoutException,
                        error -> new UpstreamUnavailableException("读取物联授权设备范围超时"))
                .onErrorMap(
                        error -> !(error instanceof BusinessException)
                                && !(error instanceof CancellationException),
                        error -> new UpstreamUnavailableException("读取物联授权设备范围失败"));
    }

    private Mono<AuthorizedDeviceScope> decode(ClientResponse response, RequestIdentity identity) {
        HttpStatusCode status = response.statusCode();
        if (status.value() == 401 || status.value() == 403) {
            return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status.value())));
        }
        if (!status.is2xxSuccessful()) {
            return response.releaseBody().then(Mono.error(new UpstreamUnavailableException("读取物联授权设备范围")));
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应")))
                .flatMap(body -> parseBody(body, identity));
    }

    private Mono<AuthorizedDeviceScope> parseBody(JsonNode root, RequestIdentity identity) {
        if (root == null || !root.isObject() || !root.path("code").isNumber()) {
            return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
        }
        int code = root.path("code").asInt();
        if (code == 401 || code == 403) {
            return Mono.error(new GatewayAuthorizationException(code));
        }
        if (code != 0 && code != 200) {
            return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围"));
        }

        JsonNode content = root.get("content");
        if (content != null && content.isObject()) {
            content = content.get("content");
        }
        if (content == null || !content.isArray()) {
            return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
        }
        try {
            Map<String, AuthorizedDevice> unique = new LinkedHashMap<>();
            for (JsonNode record : content) {
                AuthorizedDevice device = parseDevice(record);
                AuthorizedDevice previous = unique.putIfAbsent(device.id(), device);
                if (previous != null && !previous.equals(device)) {
                    return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
                }
            }
            return Mono.just(new AuthorizedDeviceScopeSnapshot(identity, new ArrayList<>(unique.values())));
        } catch (IllegalArgumentException malformed) {
            return Mono.error(new UpstreamUnavailableException("读取物联授权设备范围响应"));
        }
    }

    private AuthorizedDevice parseDevice(JsonNode record) {
        if (record == null || !record.isObject()) {
            throw new IllegalArgumentException("device record is not an object");
        }
        String id = requiredText(record, "id");
        String kind = firstText(record, "kind", "deviceKind");
        String name = firstText(record, "name", "deviceName");
        String address = firstText(record, "installAddress", "installAddr");
        if (kind == null || name == null) {
            throw new IllegalArgumentException("device identity is incomplete");
        }
        return new AuthorizedDevice(id, kind, name, address == null ? "" : address,
                record.path("observationOnly").asBoolean(false));
    }

    private String requiredText(JsonNode record, String field) {
        String value = firstText(record, field);
        if (value == null) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value;
    }

    private String firstText(JsonNode record, String... fields) {
        for (String field : fields) {
            JsonNode value = record.get(field);
            if (value != null && !value.isNull() && value.isValueNode()) {
                String text = value.asText().trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static Duration requireTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("callTimeout must be positive");
        }
        return timeout;
    }
}
