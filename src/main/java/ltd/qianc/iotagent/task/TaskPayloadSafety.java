package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;

/** Keeps credentials out of facts, events, HTTP projections and model-facing payloads. */
public final class TaskPayloadSafety {
    private TaskPayloadSafety() { }
    public static JsonNode withoutCredentials(ObjectMapper mapper, JsonNode value) {
        if (value == null || value.isNull()) return mapper.nullNode();
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            value.forEach(item -> result.add(withoutCredentials(mapper, item)));
            return result;
        }
        if (!value.isObject()) return value.deepCopy();
        ObjectNode result = mapper.createObjectNode();
        value.fields().forEachRemaining(entry -> {
            if (!sensitive(entry.getKey())) result.set(entry.getKey(), withoutCredentials(mapper, entry.getValue()));
        });
        return result;
    }
    public static String jsonWithoutCredentials(ObjectMapper mapper, String json) {
        try { return withoutCredentials(mapper, mapper.readTree(json)).toString(); }
        catch (Exception error) { throw new IllegalArgumentException("任务 payload 无效"); }
    }
    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("credential") || normalized.contains("password") || normalized.contains("secret")
                || normalized.equals("authorization") || normalized.equals("token") || normalized.endsWith("token")
                || normalized.contains("privatekey");
    }
}
