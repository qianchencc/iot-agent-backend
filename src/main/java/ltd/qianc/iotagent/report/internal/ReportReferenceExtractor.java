package ltd.qianc.iotagent.report.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Extracts all device and meter references that can affect a saved report. */
public final class ReportReferenceExtractor {
    private static final Set<String> ARRAY_KEYS = Set.of(
            "meterids", "authorizationmeterids", "deviceids", "authorizationdeviceids");

    private ReportReferenceExtractor() { }

    public static List<Reference> extract(JsonNode root) {
        if (root == null || !root.isObject()) throw new IllegalArgumentException("report payload is not an object");
        JsonNode report = root.has("report") && root.get("report").isObject() ? root.get("report") : root;
        List<Reference> references = new ArrayList<>();
        visit(report, references);
        Set<Reference> unique = new LinkedHashSet<>(references);
        return List.copyOf(unique);
    }

    private static void visit(JsonNode node, List<Reference> references) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> visitField(entry.getKey(), entry.getValue(), references));
            return;
        }
        if (node.isArray()) {
            for (JsonNode value : node) visit(value, references);
        }
    }

    private static void visitField(String key, JsonNode value, List<Reference> references) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (ARRAY_KEYS.contains(normalized)) {
            if (!value.isArray()) throw new IllegalArgumentException("report reference list is invalid");
            boolean meter = normalized.contains("meter") || normalized.equals("deviceids");
            for (JsonNode item : value) addText(item, meter, references);
            return;
        }
        if (normalized.equals("deviceid") || normalized.equals("meterid")) {
            addText(value, normalized.equals("meterid"), references);
            return;
        }
        if (normalized.equals("meters") || normalized.equals("devices")) {
            if (!value.isArray()) throw new IllegalArgumentException("report reference collection is invalid");
            boolean meter = normalized.equals("meters");
            for (JsonNode item : value) {
                if (!item.isObject()) throw new IllegalArgumentException("report reference item is invalid");
                JsonNode id = item.get("id");
                if (id == null) id = item.get(meter ? "meterId" : "deviceId");
                if (id != null) addText(id, meter, references);
                visit(item, references);
            }
            return;
        }
        if ((normalized.equals("meter") || normalized.equals("device")) && value.isObject()) {
            JsonNode id = value.get("id");
            if (id == null) id = value.get(normalized + "Id");
            if (id != null) addText(id, normalized.equals("meter"), references);
        }
        visit(value, references);
    }

    private static void addText(JsonNode value, boolean meter, List<Reference> references) {
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("report reference is invalid");
        references.add(new Reference(value.asText(), meter));
    }

    public record Reference(String id, boolean meter) { }
}
