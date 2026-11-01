package ltd.qianc.iotagent.iot.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;

/**
 * Runtime-compatible candidate search: exact scope matching, bounded presentation,
 * fuzzy/floor fallback with honest resolution metadata. Membership is the full
 * matching list used by server-side draft expansion.
 */
final class DeviceCandidateMatcher {
    static final int CANDIDATE_PRESENTATION_LIMIT = 50;
    private static final int GROUP_LIMIT = 100;
    private static final Set<String> GENERIC_SCOPE_TERMS = Set.of(
            "公司", "company", "企业", "当前公司", "公司授权范围", "公司授权范围内",
            "公司范围", "公司范围内", "授权范围", "授权区域", "不限位置",
            "全部", "全部设备", "所有", "所有设备", "all", "alldevices",
            "当前位置", "当前区域", "当前关注区域", "办公室", "本层", "这一层");

    private DeviceCandidateMatcher() {
    }

    record Result(
            List<AuthorizedDevice> members,
            List<AuthorizedDevice> visible,
            boolean complete,
            int totalMatches,
            DeviceCandidateQuery query,
            DeviceCandidateSet.Groups groups,
            List<DeviceCandidateSet.NameHintMatch> nameHintMatches,
            List<String> unmatchedNameHints,
            DeviceCandidateSet.Resolution resolution) {
    }

    static Result search(List<AuthorizedDevice> catalog, DeviceCandidateQuery query) {
        List<AuthorizedDevice> kindDevices = catalog.stream()
                .filter(device -> "unknown".equalsIgnoreCase(query.kind())
                        || device.kind().equalsIgnoreCase(query.kind()))
                .filter(device -> query.deviceIds().isEmpty() || query.deviceIds().contains(device.id()))
                .toList();
        if (!query.deviceIds().isEmpty()
                && (query.deviceIds().stream().distinct().count() != query.deviceIds().size()
                || query.deviceIds().stream().anyMatch(id -> kindDevices.stream().noneMatch(device -> device.id().equals(id))))) {
            throw new UpstreamUnavailableException("设备引用不在当前授权范围内");
        }

        List<String> submittedScopeTerms = query.scopeTerms().stream().map(DeviceCandidateMatcher::sanitize)
                .filter(value -> !value.isBlank()).toList();
        String scopeText = sanitize(query.scopeText() == null ? "" : query.scopeText());
        String normalizedScopeText = normalize(scopeText);
        // Generic phrases such as "公司" / "全部" describe the authorized scope itself,
        // not a location filter; keeping them as required terms would hide real results.
        List<String> effectiveTerms = submittedScopeTerms.stream().filter(value -> !genericScope(value)).toList();
        String effectiveScope = genericScope(scopeText) ? "" : scopeText;
        String normalizedEffectiveScope = normalize(effectiveScope);
        List<String> normalizedTerms = effectiveTerms.stream().map(DeviceCandidateMatcher::normalize).toList();
        List<String> nameHints = uniqueVocabulary(concat(query.nameHints(), query.deviceText() == null
                ? List.of() : List.of(query.deviceText())));
        List<String> normalizedHints = nameHints.stream().map(DeviceCandidateMatcher::normalize).toList();

        boolean browse = normalizedTerms.isEmpty() && normalizedEffectiveScope.isEmpty();
        if (browse && nameHints.isEmpty() && query.deviceIds().isEmpty()) {
            throw new UpstreamUnavailableException("至少需要设备范围或设备描述");
        }

        List<String> explicitEnergyFloors = "energy".equalsIgnoreCase(query.kind())
                ? uniqueVocabulary(concat(scopeText.isBlank() ? List.of() : List.of(scopeText), submittedScopeTerms))
                        .stream().map(DeviceCandidateMatcher::highLevelLocation)
                        .filter(value -> value.matches("^\\d{1,2}楼$")).toList()
                : List.of();
        List<AuthorizedDevice> discoveryPool = explicitEnergyFloors.isEmpty() ? kindDevices
                : kindDevices.stream().filter(device -> explicitEnergyFloors.contains(highLevelLocation(locationText(device)))).toList();

        List<AuthorizedDevice> exactMatching = browse ? List.of() : discoveryPool.stream().filter(device -> {
            String haystack = normalize(device.name() + " " + locationText(device));
            if (!normalizedTerms.isEmpty()) return normalizedTerms.stream().allMatch(haystack::contains);
            return haystack.contains(normalizedScopeText);
        }).toList();

        List<ScoredDevice> scored = exactMatching.isEmpty() && !browse && !normalizedScopeText.isEmpty() && !genericScope(scopeText)
                ? discoveryPool.stream()
                        .map(device -> new ScoredDevice(device, similarity(normalizedScopeText, normalize(locationText(device)))))
                        .sorted(Comparator.comparingDouble(ScoredDevice::score).reversed())
                        .toList()
                : List.of();
        double topScore = scored.isEmpty() ? 0 : scored.get(0).score();
        double fuzzyCutoff = Math.max(0.4, topScore * 0.8);
        List<AuthorizedDevice> fuzzyMatching = topScore >= 0.5
                ? scored.stream().filter(item -> item.score() >= fuzzyCutoff).map(ScoredDevice::device).toList()
                : List.of();

        String floorScope = exactMatching.isEmpty() && fuzzyMatching.isEmpty() && normalizedTerms.isEmpty()
                ? floorToken(scopeText) : "";
        List<AuthorizedDevice> floorMatching = floorScope.isEmpty() ? List.of()
                : discoveryPool.stream().filter(device -> floorScope.equals(highLevelLocation(locationText(device)))).toList();

        List<AuthorizedDevice> matching = !query.deviceIds().isEmpty() ? kindDevices
                : browse ? kindDevices
                : !exactMatching.isEmpty() ? exactMatching
                : !fuzzyMatching.isEmpty() ? fuzzyMatching
                : floorMatching;
        String resolutionMode = !query.deviceIds().isEmpty() ? "exact"
                : browse ? "browse"
                : !exactMatching.isEmpty() ? "exact"
                : !matching.isEmpty() ? "fuzzy" : "unresolved";
        List<DeviceCandidateSet.Alternative> alternatives = exactMatching.isEmpty() && !normalizedScopeText.isEmpty()
                && !genericScope(scopeText) ? alternatives(discoveryPool, normalizedScopeText) : List.of();

        List<ScoredDevice> ranked = matching.stream().map(device -> {
            String haystack = normalize(device.name() + " " + locationText(device));
            int score = 0;
            for (int index = 0; index < nameHints.size(); index++) {
                if (haystack.contains(normalizedHints.get(index))) score += nameHints.get(index).length();
            }
            return new ScoredDevice(device, score);
        }).sorted(Comparator.comparingDouble(ScoredDevice::score).reversed()).toList();
        List<AuthorizedDevice> ordered = ranked.stream().map(ScoredDevice::device).toList();
        List<AuthorizedDevice> visible = query.deviceIds().isEmpty()
                ? ordered.stream().limit(CANDIDATE_PRESENTATION_LIMIT).toList() : ordered;

        List<DeviceCandidateSet.Group> locations = groups(matching.stream().map(device -> sanitize(
                device.installAddress() == null ? "" : device.installAddress())).toList());
        List<DeviceCandidateSet.Group> baseNames = groups(matching.stream()
                .map(device -> sanitize(baseDeviceName(device.kind(), device.name()))).toList());
        List<DeviceCandidateSet.NameHintMatch> hintMatches = visible.stream().map(device -> {
            String haystack = normalize(device.name() + " " + locationText(device));
            List<String> matched = new ArrayList<>();
            for (int index = 0; index < nameHints.size(); index++) {
                if (haystack.contains(normalizedHints.get(index))) matched.add(nameHints.get(index));
            }
            return new DeviceCandidateSet.NameHintMatch(device.id(), matched);
        }).toList();
        Set<String> matchedHints = new LinkedHashSet<>();
        hintMatches.forEach(match -> matchedHints.addAll(match.matchedNameHints()));
        List<String> unmatchedHints = nameHints.stream().filter(hint -> !matchedHints.contains(hint)).toList();
        List<String> resolvedTerms = browse ? List.of() : matching.isEmpty() ? List.of()
                : !submittedScopeTerms.isEmpty() ? submittedScopeTerms
                : List.of(floorMatching.isEmpty() ? scopeText : floorScope);
        DeviceCandidateSet.Resolution resolution = new DeviceCandidateSet.Resolution(
                resolutionMode, query.scopeText(), resolvedTerms, alternatives);
        DeviceCandidateSet.Groups groups = new DeviceCandidateSet.Groups(
                locations.size() <= GROUP_LIMIT && baseNames.size() <= GROUP_LIMIT,
                locations.stream().limit(GROUP_LIMIT).toList(),
                baseNames.stream().limit(GROUP_LIMIT).toList());
        return new Result(List.copyOf(ordered), List.copyOf(visible), visible.size() == matching.size(),
                matching.size(), query, groups, hintMatches, unmatchedHints, resolution);
    }

    static boolean genericScope(String value) {
        String normalized = value == null ? "" : normalize(value);
        if (GENERIC_SCOPE_TERMS.contains(normalized)) return true;
        return normalized.matches("^(?:当前)?公司(?:授权)?(?:范围|目录|区域)(?:内)?(?:全部|所有)?(?:环境设备)?$")
                || normalized.matches("^(?:当前)?公司(?:全部|所有)(?:授权)?(?:范围|目录|区域)(?:内)?(?:环境设备)?$")
                || normalized.matches("^(?:全部|所有|不限位置)(?:公司|环境设备|公司区域)?$");
    }

    static String locationText(AuthorizedDevice device) {
        String self = (device.name() == null ? "" : device.name())
                + (device.installAddress() == null ? "" : device.installAddress());
        List<String> paths = device.locationPaths().stream().map(path -> String.join(" / ", path)).toList();
        if (self.matches(".*(?:^|[^\\d])\\d{1,2}楼.*")) return device.installAddress() == null ? "" : device.installAddress();
        List<String> parts = new ArrayList<>(paths);
        if (device.installAddress() != null && !device.installAddress().isBlank()) parts.add(device.installAddress());
        return String.join(" / ", parts);
    }

    static String highLevelLocation(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        java.util.regex.Matcher floor = java.util.regex.Pattern.compile("(?:^|[^\\d])(\\d{1,2})楼").matcher(normalized);
        if (floor.find()) return floor.group(1) + "楼";
        if (normalized.matches("^\\d.*") || normalized.startsWith("空调") || normalized.startsWith("照明")) return "";
        java.util.regex.Matcher office = java.util.regex.Pattern.compile("^(.{2,12}?办公室)").matcher(normalized);
        return office.find() ? office.group(1) : "";
    }

    private static String floorToken(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|[^\\d])(\\d{1,2})楼").matcher(value);
        return matcher.find() ? matcher.group(1) + "楼" : "";
    }

    static String baseDeviceName(String kind, String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC);
        return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) {
            case "lighting" -> {
                String withoutKind = normalized.replaceFirst("^\\s*照明开关\\s*-\\s*", "");
                int dash = withoutKind.lastIndexOf('-');
                yield dash >= 0 ? withoutKind.substring(dash + 1) : withoutKind;
            }
            case "ac" -> "中央空调";
            case "access" -> normalized.contains("人脸") ? "人脸门禁" : "门禁";
            default -> kindLabel(kind);
        };
    }

    private static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind.toLowerCase(Locale.ROOT)) {
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

    private static List<DeviceCandidateSet.Alternative> alternatives(List<AuthorizedDevice> devices, String query) {
        List<ScoredGroup> ranked = groupsWithCounts(devices.stream()
                .map(device -> sanitize(device.installAddress() == null ? "" : device.installAddress())).toList())
                .stream().map(group -> new ScoredGroup(group.label(), group.count(),
                        similarity(query, normalize(group.label()))))
                .filter(group -> group.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredGroup::score).reversed()
                        .thenComparing(Comparator.comparingInt(ScoredGroup::count).reversed())
                        .thenComparing(ScoredGroup::label))
                .toList();
        double minimum = Math.max(0.25, (ranked.isEmpty() ? 0 : ranked.get(0).score()) * 0.7);
        return ranked.stream().filter(group -> group.score() >= minimum).limit(5)
                .map(group -> new DeviceCandidateSet.Alternative(group.label(), group.count(),
                        Math.round(group.score() * 1000) / 1000.0))
                .toList();
    }

    private static List<DeviceCandidateSet.Group> groups(List<String> values) {
        return groupsWithCounts(values).stream().limit(GROUP_LIMIT)
                .map(group -> new DeviceCandidateSet.Group(group.label(), group.count())).toList();
    }

    private static List<CountedGroup> groupsWithCounts(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            counts.merge(value, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .map(entry -> new CountedGroup(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(CountedGroup::count).reversed()
                        .thenComparing(CountedGroup::label))
                .toList();
    }

    private static double similarity(String left, String right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) return 0;
        List<String> leftPairs = characterPairs(left);
        List<String> rightPairs = characterPairs(right);
        if (leftPairs.isEmpty() || rightPairs.isEmpty()) {
            if (left.equals(right)) return 1;
            long shared = left.chars().filter(ch -> right.indexOf(ch) >= 0).count();
            return (double) shared / Math.max(left.length(), right.length());
        }
        List<String> available = new ArrayList<>(rightPairs);
        int overlap = 0;
        for (String pair : leftPairs) {
            int index = available.indexOf(pair);
            if (index < 0) continue;
            overlap++;
            available.remove(index);
        }
        return (2.0 * overlap) / (leftPairs.size() + rightPairs.size());
    }

    private static List<String> characterPairs(String value) {
        int[] points = value.codePoints().toArray();
        List<String> pairs = new ArrayList<>();
        for (int index = 0; index + 1 < points.length; index++) {
            pairs.add(new String(points, index, 2));
        }
        return pairs;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\-_，。,、（）()]", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String sanitize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}（）()]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> uniqueVocabulary(List<String> values) {
        List<String> result = new ArrayList<>(values.stream().map(DeviceCandidateMatcher::sanitize)
                .filter(value -> !value.isBlank()).distinct().toList());
        java.text.Collator collator = java.text.Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        result.sort(collator::compare);
        return List.copyOf(result);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first == null ? List.of() : first);
        result.addAll(second == null ? List.of() : second);
        return result;
    }

    private record ScoredDevice(AuthorizedDevice device, double score) { }
    private record CountedGroup(String label, int count) { }
    private record ScoredGroup(String label, int count, double score) { }
}
