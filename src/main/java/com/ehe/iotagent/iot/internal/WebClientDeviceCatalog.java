package com.ehe.iotagent.iot.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ehe.iotagent.iot.DeviceCatalogSource;
import com.ehe.iotagent.iot.GatewayAuthorizationException;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.iot.UpstreamUnavailableException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** One full hardware list; no role-scoped directory, state or action lookup. */
public final class WebClientDeviceCatalog implements DeviceCatalogSource {
    private static final Map<String, String> KINDS = Map.ofEntries(
            Map.entry("SL-SW-NS3", "lighting"), Map.entry("YQ-WFS", "safety"),
            Map.entry("ENERGY-376.1", "energy"), Map.entry("YQ-PSD", "safety"),
            Map.entry("HIK-ISC-VIDEO", "video"), Map.entry("YQ_AIR_CONDITION", "ac"),
            Map.entry("YQ-ENV-SENSOR", "environment"), Map.entry("EHE-ACCESS-V2", "access"),
            Map.entry("MG-AC-CONTROLLER", "ac"), Map.entry("YQ-GAS-INDUCTOR", "safety"),
            Map.entry("DS-K1T673M", "access"));
    private final WebClient client;
    private final String path;
    private final Duration timeout;

    public WebClientDeviceCatalog(WebClient client, String path, Duration timeout) {
        this.client = client;
        this.path = path;
        this.timeout = timeout;
    }

    @Override public List<Entry> collect(RequestIdentity identity) {
        JsonNode body = client.get().uri(path)
                .headers(headers -> identity.gatewayHeaders().forEach(headers::set))
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 401 || status == 403)
                        return response.releaseBody().then(Mono.error(new GatewayAuthorizationException(status)));
                    if (!response.statusCode().is2xxSuccessful())
                        return response.releaseBody().then(Mono.error(unavailable()));
                    return GatewayJson.read(response);
                }).block(timeout);
        if (body == null || !body.path("code").isNumber()) throw unavailable();
        int code = body.path("code").intValue();
        if (code == 401 || code == 403) throw new GatewayAuthorizationException(code);
        if ((code != 0 && code != 200) || !body.path("content").isArray()) throw unavailable();
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (JsonNode row : body.path("content")) {
            if (!row.isObject()) throw unavailable();
            String kind = KINDS.get(row.path("modelCode").asText());
            // Non-terminal service registrations (including ISC event service) are not devices.
            if (kind == null || "TRUE".equals(row.path("isDelete").asText())) continue;
            String id = requiredText(row, "id"), name = requiredText(row, "name");
            JsonNode address = row.get("installAddress");
            if (address != null && !address.isNull() && !address.isTextual()) throw unavailable();
            Entry entry = new Entry(id, name, kind, address == null || address.isNull() ? "" : address.textValue());
            if (unique.putIfAbsent(kind + "\u0000" + id, entry) != null) throw unavailable();
        }
        return List.copyOf(unique.values());
    }

    private static String requiredText(JsonNode row, String key) {
        JsonNode value = row.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw unavailable();
        return value.textValue();
    }
    private static UpstreamUnavailableException unavailable() {
        return new UpstreamUnavailableException("读取完整设备目录响应");
    }
}
