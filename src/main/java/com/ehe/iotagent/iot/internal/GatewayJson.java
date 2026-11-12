package com.ehe.iotagent.iot.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ehe.iotagent.iot.UpstreamUnavailableException;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/** Parse once from source bytes: a response's default floating decoder must not round facts before persistence. */
final class GatewayJson {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private GatewayJson() { }

    static Object value(JsonNode node) { return JSON.convertValue(node,Object.class); }

    static Mono<JsonNode> read(ClientResponse response) {
        return response.bodyToMono(byte[].class).flatMap(bytes -> {
            try {
                JsonNode body = JSON.readTree(bytes);
                return body == null ? Mono.empty() : Mono.just(body);
            } catch (java.io.IOException malformed) {
                return Mono.error(new UpstreamUnavailableException("客户JSON响应无效"));
            }
        });
    }
}
