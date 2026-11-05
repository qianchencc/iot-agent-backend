package ltd.qianc.iotagent.agent.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;

/** Runtime iot/adapter.ts createDeviceCatalogSnapshot's model-facing vocabulary. */
final class AgentDeviceContext {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> KINDS = List.of(
            "lighting", "ac", "access", "video", "environment", "energy", "safety", "unknown");
    private static final Pattern FLOOR = Pattern.compile("(?:^|[^\\d])(\\d{1,2})楼");
    private static final Pattern OFFICE = Pattern.compile("^(.{2,12}?办公室)");
    private static final Pattern NON_VOCABULARY = Pattern.compile("[^\\p{L}\\p{N}（）()]+");
    private static final Pattern LIGHT_PREFIX = Pattern.compile("^\\s*照明开关\\s*-\\s*");
    private static final int VOCABULARY_LIMIT = 8;

    private AgentDeviceContext() { }

    static String describe(AuthorizedDeviceScope scope) {
        var collation = Collator.getInstance(Locale.CHINA);
        Comparator<Map.Entry<String, Integer>> rank = Comparator
                .<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey, collation);
        List<Map<String, Object>> vocabularies = new ArrayList<>();
        for (String kind : KINDS) {
            List<AuthorizedDevice> devices = scope.devices().stream().filter(device -> kind.equals(device.kind())).toList();
            if (devices.isEmpty()) continue;
            List<String> locations = new ArrayList<>(), names = new ArrayList<>();
            for (AuthorizedDevice device : devices) {
                var deviceLocations = new LinkedHashSet<String>();
                if (device.locationPaths().isEmpty()) deviceLocations.add(highLevelLocation(device.installAddress()));
                else for (List<String> path : device.locationPaths()) {
                    deviceLocations.add(highLevelLocation(String.join(" / ", path)));
                }
                locations.addAll(deviceLocations);
                names.add(sanitizeVocabulary(basicName(kind, device.name())));
            }
            List<Map<String, Object>> locationCounts = groups(locations, rank);
            Map<String, Object> vocabulary = new LinkedHashMap<>();
            vocabulary.put("kind", kind);
            vocabulary.put("label", label(kind));
            vocabulary.put("count", devices.size());
            vocabulary.put("locations", locationCounts.stream().map(group -> group.get("label")).toList());
            vocabulary.put("locationCounts", locationCounts);
            vocabulary.put("baseNames", groups(names, rank).stream().map(group -> group.get("label")).toList());
            vocabularies.add(vocabulary);
        }
        try {
            return JSON.writeValueAsString(Map.of("complete", true, "deviceKinds", vocabularies));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Cannot encode verified device context", impossible);
        }
    }

    private static List<Map<String, Object>> groups(List<String> values,
            Comparator<Map.Entry<String, Integer>> rank) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) if (!value.isEmpty()) counts.merge(value, 1, Integer::sum);
        return counts.entrySet().stream().sorted(rank).limit(VOCABULARY_LIMIT)
                .map(entry -> Map.<String, Object>of("label", entry.getKey(), "count", entry.getValue())).toList();
    }

    private static String highLevelLocation(String value) {
        String normalized = normalize(value);
        var floor = FLOOR.matcher(normalized);
        if (floor.find()) return floor.group(1) + "楼";
        if (normalized.matches("^(?:\\d|空调|照明).*$")) return "";
        var office = OFFICE.matcher(normalized);
        return office.find() ? office.group(1) : "";
    }

    private static String basicName(String kind, String value) {
        if ("lighting".equals(kind)) {
            String withoutKind = LIGHT_PREFIX.matcher(normalize(value)).replaceFirst("");
            return withoutKind.substring(withoutKind.lastIndexOf('-') + 1);
        }
        if ("ac".equals(kind)) return "中央空调";
        if ("access".equals(kind)) return value != null && value.contains("人脸") ? "人脸门禁" : "门禁";
        return label(kind);
    }

    private static String label(String kind) {
        return switch (kind) {
            case "lighting" -> "照明";
            case "ac" -> "中央空调";
            case "access" -> "门禁";
            case "video" -> "视频监控";
            case "environment" -> "环境设备";
            case "energy" -> "能源设备";
            case "safety" -> "安全设备";
            default -> "其他设备";
        };
    }

    private static String sanitizeVocabulary(String value) {
        String normalized = NON_VOCABULARY.matcher(normalize(value)).replaceAll(" ").strip();
        return normalized.substring(0, Math.min(80, normalized.length()));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
    }
}
