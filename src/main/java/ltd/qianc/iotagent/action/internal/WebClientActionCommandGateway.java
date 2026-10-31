package ltd.qianc.iotagent.action.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDispatchException;
import ltd.qianc.iotagent.action.ActionDispatchResult;
import ltd.qianc.iotagent.action.ActionReceiptOutcome;
import ltd.qianc.iotagent.action.ActionDraftStatus;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Runtime-derived customer control adapter. It is deliberately below the
 * ActionDraft confirmation seam: callers cannot reach it before CAS and scope
 * revalidation have completed.
 */
public final class WebClientActionCommandGateway implements ActionCommandGateway {
    private final ObjectMapper mapper;
    private final WebClient client;
    private final IotAdapter iot;
    private final Duration timeout;
    private final String lightingPath;
    private final String accessPath;
    private final String acPath;

    public WebClientActionCommandGateway(ObjectMapper mapper, WebClient client, IotAdapter iot,
                                         Duration timeout, String lightingPath, String accessPath, String acPath) {
        this.mapper = mapper;
        this.client = client;
        this.iot = iot;
        this.timeout = timeout;
        this.lightingPath = lightingPath;
        this.accessPath = accessPath;
        this.acPath = acPath;
    }

    @Override
    public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope, String selectedActionsJson) {
        ArrayNode actions = readActions(selectedActionsJson);
        validateActions(scope, actions);
        ArrayNode receipts = mapper.createArrayNode();
        List<JsonNode> accepted = new ArrayList<>();
        int rejected = 0;
        for (JsonNode action : actions) {
            String actionId = action.path("id").asText("");
            ObjectNode receipt = mapper.createObjectNode();
            receipt.put("actionId", actionId);
            try {
                JsonNode response = dispatchOne(identity, scope, action);
                boolean acceptedResult = accepted(response);
                receipt.put("outcome", acceptedResult ? ActionReceiptOutcome.ACCEPTED.name() : ActionReceiptOutcome.REJECTED.name());
                receipt.set("response", response == null ? mapper.createObjectNode() : response);
                if (acceptedResult) accepted.add(action); else rejected++;
            } catch (RuntimeException error) {
                if (error instanceof GatewayAuthorizationException authorization) throw authorization;
                if (error instanceof ActionDispatchException dispatchError) throw dispatchError;
                throw new ActionDispatchException("设备指令下发结果未知", error, true);
            }
            receipts.add(receipt);
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("gateway", "customer");
        result.put("outcome", rejected == actions.size() ? "REJECTED" : "ACCEPTED");
        result.set("receipts", receipts);
        result.set("readbacks", readbacks(identity, accepted));
        ActionDraftStatus status = rejected == actions.size() ? ActionDraftStatus.REJECTED
                : rejected > 0 ? ActionDraftStatus.PARTIALLY_ACCEPTED : ActionDraftStatus.ACCEPTED;
        ActionReceiptOutcome overall = rejected == actions.size() ? ActionReceiptOutcome.REJECTED
                : rejected > 0 ? ActionReceiptOutcome.UNKNOWN : ActionReceiptOutcome.ACCEPTED;
        return new ActionDispatchResult(status, result.toString(), true, overall);
    }

    private void validateActions(AuthorizedDeviceScope scope, ArrayNode actions) {
        for (JsonNode action : actions) {
            String deviceId = action.path("deviceId").asText("");
            AuthorizedDevice device = scope.device(deviceId).orElseThrow(() -> new IllegalArgumentException("设备不在授权范围"));
            if (device.observationOnly()) throw new IllegalArgumentException("设备不可控");
            requireBinding(device);
        }
    }

    private JsonNode dispatchOne(RequestIdentity identity, AuthorizedDeviceScope scope, JsonNode action) {
        String kind = action.path("kind").asText("");
        String deviceId = action.path("deviceId").asText("");
        AuthorizedDevice device = scope.device(deviceId).orElseThrow(() -> new IllegalArgumentException("设备不在授权范围"));
        ObjectNode command = action.path("command").deepCopy();
        Map<String, Object> body = new LinkedHashMap<>();
        String path;
        if ("lighting".equals(kind)) {
            path = lightingPath;
            body.put("lightingAreaDeviceId", requireBinding(device));
            body.put("closeOrOpen", command.has("closeOrOpen") ? command.path("closeOrOpen").asInt()
                    : "on".equals(command.path("power").asText()) ? 1 : 0);
        } else if ("access".equals(kind)) {
            path = accessPath;
            body.put("accessAreaDeviceId", requireBinding(device));
            body.put("controlType", command.path("controlType").asInt());
        } else if ("ac".equals(kind)) {
            if (identity.source() == IotSource.COMPANY && device.metadata().get("companyAcActionId") instanceof String actionId) {
                return post("/micro-api/api/iot-center/interactive/" + encode(device.hubDeviceId()) + "/" + encode(actionId) + "/down",
                        companyAcBody(command));
            }
            path = acPath;
            body.put("acAreaDeviceId", requireBinding(device));
            String field = command.has("temperature") ? "temperature" : command.has("mode") ? "mode"
                    : command.has("fanSpeed") ? "fanSpeed" : "power";
            String value = command.path(field).asText("off");
            body.put("acDeviceParamTemplate", acTemplate(field, value));
            if ("temperature".equals(field)) body.put("temperature", Math.round(command.path(field).asDouble() * 10));
        } else {
            throw new IllegalArgumentException("设备类型不可控");
        }
        return post(path, body);
    }

    private JsonNode post(String path, Map<String, ?> body) {
        return client.post().uri(path).bodyValue(body).exchangeToMono(this::decode).block(timeout);
    }

    private reactor.core.publisher.Mono<JsonNode> decode(ClientResponse response) {
        int status = response.statusCode().value();
        if (status == 401 || status == 403) return response.releaseBody().then(reactor.core.publisher.Mono.error(new GatewayAuthorizationException(status)));
        if (!response.statusCode().is2xxSuccessful()) return response.releaseBody().then(reactor.core.publisher.Mono.error(new IllegalStateException("上游控制请求失败")));
        return response.bodyToMono(JsonNode.class).switchIfEmpty(reactor.core.publisher.Mono.error(new IllegalStateException("上游控制响应为空")))
                .flatMap(body -> {
                    int code = body.path("code").asInt(-1);
                    if (code == 401 || code == 403) return reactor.core.publisher.Mono.error(new GatewayAuthorizationException(code));
                    if (code != 0 && code != 200) return reactor.core.publisher.Mono.error(new IllegalStateException("上游控制返回失败"));
                    JsonNode content = body.get("content");
                    if (content != null && content.isTextual()) {
                        try { content = mapper.readTree(content.asText()); } catch (Exception ignored) { content = null; }
                    }
                    int nested = content == null ? 0 : content.path("code").asInt(0);
                    if (nested == 401 || nested == 403) return reactor.core.publisher.Mono.error(new GatewayAuthorizationException(nested));
                    if (nested != 0 && nested != 200) return reactor.core.publisher.Mono.error(new IllegalStateException("上游控制返回失败"));
                    return reactor.core.publisher.Mono.just(body);
                });
    }

    private ArrayNode readbacks(RequestIdentity identity, List<JsonNode> actions) {
        ArrayNode result = mapper.createArrayNode();
        if (actions.isEmpty()) return result;
        List<String> ids = actions.stream().map(action -> action.path("deviceId").asText("")).filter(id -> !id.isBlank()).distinct().toList();
        try {
            DeviceStateReadResult states = iot.readDeviceStates(identity, ids).block(timeout);
            if (states == null) return result;
            for (JsonNode action : actions) {
                String deviceId = action.path("deviceId").asText("");
                ObjectNode readback = mapper.createObjectNode();
                readback.put("actionId", action.path("id").asText(""));
                Object state = states.states().get(deviceId);
                readback.put("outcome", state == null ? "unconfirmed" : "confirmed");
                readback.set("observedState", mapper.valueToTree(state == null ? Map.of() : state));
                readback.put("observedAt", states.observedAt());
                result.add(readback);
            }
        } catch (RuntimeException ignored) {
            // The write was accepted; preserve an explicit unknown readback.
            for (JsonNode action : actions) {
                ObjectNode readback = mapper.createObjectNode();
                readback.put("actionId", action.path("id").asText(""));
                readback.put("outcome", "unconfirmed");
                readback.set("observedState", mapper.createObjectNode());
                result.add(readback);
            }
        }
        return result;
    }

    private static boolean accepted(JsonNode body) {
        JsonNode content = body == null ? null : body.get("content");
        if (content != null && content.isTextual()) {
            try { content = new ObjectMapper().readTree(content.asText()); } catch (Exception ignored) { return false; }
        }
        return body != null && (body.path("code").asInt(-1) == 0 || body.path("code").asInt(-1) == 200)
                && (content == null || !content.isObject() || content.path("code").asInt(0) == 0);
    }

    private static ArrayNode readActions(String json) {
        try {
            JsonNode value = new ObjectMapper().readTree(json);
            if (!value.isArray() || value.isEmpty()) throw new IllegalArgumentException("动作列表为空");
            return (ArrayNode) value;
        } catch (Exception error) { throw new IllegalArgumentException("动作列表无效", error); }
    }

    private static String requireBinding(AuthorizedDevice device) {
        if (device.controlRef() == null || device.controlRef().isBlank()) throw new IllegalArgumentException("设备控制绑定缺失");
        return device.controlRef();
    }

    private static Map<String, Object> companyAcBody(JsonNode command) {
        String field = command.has("power") ? "power" : command.has("temperature") ? "temperature" : command.has("mode") ? "mode" : "fanSpeed";
        String value = command.path(field).asText("");
        int val = switch (field) {
            case "power" -> "on".equals(value) ? 1 : 0;
            case "temperature" -> command.path(field).asInt() * 10;
            case "mode" -> Map.of("fan", 2, "cool", 3, "heat", 4, "dry", 5, "auto", 7).getOrDefault(value, -1);
            default -> Map.of("low", 15, "high", 75, "auto", 101).getOrDefault(value, -1);
        };
        if (val < 0) throw new IllegalArgumentException("空调参数未核验");
        int type = switch (field) { case "power" -> "on".equals(value) ? 129 : 128; case "temperature" -> 136; default -> 206; };
        String idx = switch (field) { case "power" -> "O"; case "temperature" -> "tT"; case "mode" -> "MODE"; default -> "F"; };
        return Map.of("idx", idx, "type", type, "val", val, "v", val);
    }

    private static String acTemplate(String field, String value) {
        return switch (field) {
            case "temperature" -> "AC_TEMPERATURE";
            case "mode" -> "AC_MODE_" + value.toUpperCase(Locale.ROOT);
            case "fanSpeed" -> "AC_FAN_SPEED_" + value.toUpperCase(Locale.ROOT);
            default -> "AC_" + ("on".equals(value) ? "OPEN" : "CLOSE");
        };
    }

    private static String encode(String value) { return java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8); }
}
