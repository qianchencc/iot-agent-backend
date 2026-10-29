package ltd.qianc.iotagent.action.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDraftStatus;
import ltd.qianc.iotagent.action.ActionDispatchResult;
import ltd.qianc.iotagent.action.ActionReceiptOutcome;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.RequestIdentity;

/**
 * Explicit local-only gateway. It mirrors the Runtime control envelopes but never
 * constructs a WebClient or sends a request to a customer upstream.
 */
public final class FixtureActionCommandGateway implements ActionCommandGateway {
    private final ObjectMapper mapper;
    private final String configuredOutcome;
    private final String configuredReadback;

    public FixtureActionCommandGateway(ObjectMapper mapper, String outcome, String readback) {
        this.mapper = mapper;
        this.configuredOutcome = normalize(outcome, "accepted");
        this.configuredReadback = normalize(readback, "confirmed");
    }

    @Override
    public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope, String selectedActionsJson) {
        JsonNode raw;
        try {
            raw = mapper.readTree(selectedActionsJson);
        } catch (Exception error) {
            throw new IllegalArgumentException("Fixture action payload is invalid", error);
        }
        if (raw == null || !raw.isArray() || raw.isEmpty()) throw new IllegalArgumentException("Fixture action payload is empty");
        ArrayNode receipts = mapper.createArrayNode();
        ArrayNode readbacks = mapper.createArrayNode();
        int accepted = 0;
        int rejected = 0;
        for (JsonNode action : raw) {
            String actionId = action.path("id").asText("");
            String kind = action.path("kind").asText("");
            ObjectNode receipt = mapper.createObjectNode();
            receipt.put("actionId", actionId);
            ActionReceiptOutcome receiptOutcome = receiptOutcome(configuredOutcome);
            receipt.put("outcome", receiptOutcome.name());
            receipt.set("protocol", protocol(kind, action));
            receipts.add(receipt);
            if (receiptOutcome == ActionReceiptOutcome.REJECTED) rejected++; else accepted++;
            if (receiptOutcome == ActionReceiptOutcome.ACCEPTED) {
                ObjectNode readback = mapper.createObjectNode();
                readback.put("actionId", actionId);
                readback.put("outcome", "confirmed".equals(configuredReadback) ? "confirmed" : "unconfirmed");
                if ("confirmed".equals(configuredReadback)) readback.set("observedState", action.path("command").deepCopy());
                else readback.set("observedState", mapper.createObjectNode());
                readback.put("observedAt", java.time.Instant.now().toString());
                readbacks.add(readback);
            }
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("gateway", "fixture");
        result.put("outcome", configuredOutcome.toUpperCase(Locale.ROOT));
        result.set("receipts", receipts);
        result.set("readbacks", readbacks);
        ActionDraftStatus status = rejected == raw.size() ? ActionDraftStatus.REJECTED
                : rejected > 0 ? ActionDraftStatus.PARTIALLY_ACCEPTED : ActionDraftStatus.ACCEPTED;
        ActionReceiptOutcome overall = configuredOutcome.equals("rejected") ? ActionReceiptOutcome.REJECTED
                : receiptOutcome(configuredOutcome);
        return new ActionDispatchResult(status, result.toString(), true, overall);
    }

    private ObjectNode protocol(String kind, JsonNode action) {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode body = mapper.createObjectNode();
        String deviceId = action.path("deviceId").asText("");
        JsonNode command = action.path("command");
        if ("lighting".equals(kind)) {
            request.put("path", "/micro-api/api/office/app/device/lighting/down");
            body.put("lightingAreaDeviceId", deviceId);
            body.put("closeOrOpen", command.path("closeOrOpen").asInt(command.path("power").asText("on").equals("on") ? 1 : 0));
        } else if ("access".equals(kind)) {
            request.put("path", "/micro-api/api/office/app/device/access/down");
            body.put("accessAreaDeviceId", deviceId);
            body.put("controlType", command.path("controlType").asInt(2));
        } else if ("ac".equals(kind)) {
            request.put("path", "/micro-api/api/office/app/device/ac/down");
            body.put("acAreaDeviceId", deviceId);
            String field = command.has("temperature") ? "temperature" : command.has("mode") ? "mode" : command.has("fanSpeed") ? "fanSpeed" : "power";
            String value = command.path(field).asText("off");
            String template = switch (field) {
                case "temperature" -> "AC_TEMPERATURE";
                case "mode" -> "AC_MODE_" + value.toUpperCase(Locale.ROOT);
                case "fanSpeed" -> "AC_FAN_SPEED_" + value.toUpperCase(Locale.ROOT);
                default -> "AC_" + ("on".equals(value) ? "OPEN" : "CLOSE");
            };
            body.put("acDeviceParamTemplate", template);
            if ("temperature".equals(field)) body.put("temperature", Math.round(command.path(field).asDouble() * 10));
        }
        request.set("body", body);
        return request;
    }

    private static ActionReceiptOutcome receiptOutcome(String value) {
        return switch (value) {
            case "rejected" -> ActionReceiptOutcome.REJECTED;
            case "timeout" -> ActionReceiptOutcome.TIMEOUT;
            case "unknown" -> ActionReceiptOutcome.UNKNOWN;
            default -> ActionReceiptOutcome.ACCEPTED;
        };
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "accepted", "rejected", "timeout", "unknown", "confirmed", "unconfirmed" -> normalized;
            default -> fallback;
        };
    }
}
