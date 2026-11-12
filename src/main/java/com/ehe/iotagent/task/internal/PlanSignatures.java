package com.ehe.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runtime tasks/plan-signature.ts: two recurring arrangements are the same when
 * their repeat rule and every phase's local checkpoint time, control targets
 * and read scopes (including the AC inspection target) match. Labels and
 * report wording are expressions of the same arrangement and never compared.
 */
public final class PlanSignatures {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private PlanSignatures() { }

    public static String shape(JsonNode repeat, JsonNode phases) {
        ObjectNode root = JsonNodes.object();
        root.set("repeat", canonical(repeat == null || repeat.isNull() ? null : repeat));
        ArrayNode phaseShapes = root.putArray("phases");
        if (phases != null && phases.isArray()) {
            for (JsonNode phase : phases) phaseShapes.add(phaseShape(phase, repeat));
        }
        return root.toString();
    }

    private static ObjectNode phaseShape(JsonNode phase, JsonNode repeat) {
        ObjectNode shape = JsonNodes.object();
        shape.put("time", planClock(phase.path("at").asText(null), repeat != null && repeat.isObject()));
        ArrayNode actions = shape.putArray("actions");
        List<String> deviceIds = new ArrayList<>();
        if (phase.path("actions").isArray()) {
            for (JsonNode action : phase.path("actions")) deviceIds.add(action.path("deviceId").asText(""));
        }
        deviceIds.sort(Comparator.naturalOrder());
        deviceIds.forEach(actions::add);
        ArrayNode reads = shape.putArray("reads");
        readIds(phase.path("read")).forEach(reads::add);
        ObjectNode ac = acShape(phase.path("read").path("ac"));
        if (ac != null) shape.set("ac", ac);
        return shape;
    }

    private static String planClock(String at, boolean recurring) {
        if (at == null) return "";
        try {
            Instant instant = Instant.parse(at);
            return recurring
                    ? LOCAL_TIME.format(instant.atZone(SHANGHAI))
                    : instant.toString();
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    private static List<String> readIds(JsonNode read) {
        List<String> ids = new ArrayList<>();
        if (read != null && read.isObject()) {
            if (read.path("deviceIds").isArray()) {
                for (JsonNode id : read.path("deviceIds")) {
                    if (id.isTextual()) ids.add(id.asText());
                }
            } else if (read.path("devices").isArray()) {
                for (JsonNode device : read.path("devices")) {
                    if (device.isObject() && device.path("id").isTextual()) ids.add(device.path("id").asText());
                }
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    /** The AC target belongs to the arrangement's semantics: same cadence and scope with a different target is another plan. */
    private static ObjectNode acShape(JsonNode ac) {
        if (ac == null || !ac.isObject() || ac.isEmpty()) return null;
        JsonNode target = ac.path("condition").path("setpoint").path("value");
        if (!target.isNumber()) target = ac.path("action").path("setpoint");
        if (!target.isNumber()) target = ac.path("target");
        ObjectNode shape = JsonNodes.object();
        shape.set("mode", canonical(ac.path("mode")));
        shape.put("target", target.asInt(26));
        return shape;
    }

    private static JsonNode canonical(JsonNode value) {
        if (value == null || value.isNull()) return JsonNodes.mapper().nullNode();
        if (value.isObject()) {
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            ObjectNode sorted = JsonNodes.object();
            for (String field : fields) sorted.set(field, canonical(value.get(field)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode ordered = JsonNodes.mapper().createArrayNode();
            for (JsonNode item : value) ordered.add(canonical(item));
            return ordered;
        }
        return value;
    }

    private static final class JsonNodes {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private static com.fasterxml.jackson.databind.ObjectMapper mapper() {
            return MAPPER;
        }

        private static ObjectNode object() {
            return MAPPER.createObjectNode();
        }
    }
}
