package com.ehe.iotagent.instructions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single authority for the Java-owned business instruction sections: the
 * program defaults shipped with the build, the exact runtime prompt
 * composition and the instruction field validation. The admin console and
 * every run definition read this class so a section is never described,
 * previewed and executed differently. Resource defaults load once; nothing
 * here mutates caller state.
 */
public final class AgentInstructions {

    /** Engine bounds: full run instructions and the two auxiliary sections. */
    public static final int MAX_LENGTH = 65536;
    public static final int AUXILIARY_MAX_LENGTH = 16000;

    private static final String OFFICE_BASE = load("office-instructions.txt");
    private static final String COMPANY_BASE = load("company-instructions.txt");
    private static final String SHARED = load("shared-instructions.txt");
    private static final String COMPANY_TASKS = load("company-task-instructions.txt");
    private static final String CHECKPOINT = load("checkpoint-instructions.txt");
    private static final String FINALIZATION = load("finalization-instructions.txt");
    private static final String COMPACTION = load("compaction-instructions.txt");

    /** Required base sections first, then the three optional additions. */
    private static final List<String> SECTIONS = List.of(
            "officeBaseInstructions", "companyBaseInstructions", "sharedInstructions",
            "companyTaskInstructions", "checkpointInstructions", "finalizationInstructions",
            "compactionInstructions",
            "officeInstructions", "companyInstructions", "replyInstructions");
    private static final int BASE_COUNT = 7;

    private static final Set<String> NAMES = Collections.unmodifiableSet(new LinkedHashSet<>(SECTIONS));
    private static final Set<String> BASE_NAMES = Set.copyOf(SECTIONS.subList(0, BASE_COUNT));
    private static final Map<String, Object> DEFAULTS = createDefaults();

    private AgentInstructions() {
    }

    /** Current program defaults; additions default to empty. */
    public static Map<String, Object> defaults() {
        return DEFAULTS;
    }

    private static Map<String, Object> createDefaults() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String name : SECTIONS) values.put(name, defaultValue(name));
        return Collections.unmodifiableMap(values);
    }

    /**
     * Every setting preserved as-is; only missing instruction values are added from
     * the program defaults. Older revisions without the base sections therefore stay
     * readable without overwriting an explicit section.
     */
    public static Map<String, Object> withDefaults(Map<String, Object> settings) {
        Map<String, Object> result = settings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(settings);
        for (String name : SECTIONS) {
            if (!result.containsKey(name)) result.put(name, defaultValue(name));
        }
        return result;
    }

    /** Exactly the ten instruction setting keys, base sections first. */
    public static Set<String> names() {
        return NAMES;
    }

    /**
     * Instruction field problems only. Missing legacy values are resolved from the
     * defaults first, so an older revision validates until an explicit value breaks
     * a rule: the seven bases must be nonblank strings within the engine bounds;
     * the three additions must be strings, empty allowed.
     */
    public static List<String> validate(Map<String, Object> settings) {
        List<String> issues = new ArrayList<>();
        if (settings == null) return List.of();
        for (int index = 0; index < SECTIONS.size(); index++) {
            String name = SECTIONS.get(index);
            Object effective = settings.containsKey(name) ? settings.get(name) : defaultValue(name);
            boolean base = index < BASE_COUNT;
            if (!(effective instanceof String text)) {
                issues.add(name);
            } else if (text.length() > sectionMaxLength(name) || (base && text.isBlank())) {
                issues.add(name);
            }
        }
        if (issues.isEmpty()) {
            if (composedLength(true, settings) > MAX_LENGTH) issues.add("officeBaseInstructions");
            if (composedLength(false, settings) > MAX_LENGTH) issues.add("companyBaseInstructions");
        }
        return List.copyOf(issues);
    }

    /** Preview of all five composed run outputs for the given settings. */
    public static Map<String, String> preview(Map<String, Object> settings) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("office", compose("office", settings));
        result.put("company", compose("company", settings));
        result.put("checkpoint", section("checkpointInstructions", settings));
        result.put("finalization", section("finalizationInstructions", settings));
        result.put("compaction", section("compactionInstructions", settings));
        return result;
    }

    /**
     * Full source prompt for one run source, joined exactly as the runtime
     * definition: base section, then the company task section (company only),
     * the optional source addition, the optional reply addition and the shared
     * section, each present part followed by a blank line.
     */
    public static String compose(String source, Map<String, Object> settings) {
        boolean office = "office".equals(source);
        if (!office && !"company".equals(source)) {
            throw new IllegalArgumentException("Unknown instruction source: " + source);
        }
        int length = composedLength(office, settings);
        if (length > MAX_LENGTH) throw new IllegalArgumentException("Instruction total exceeds engine limit: " + source);
        StringBuilder text = new StringBuilder(length);
        text.append(section(office ? "officeBaseInstructions" : "companyBaseInstructions", settings)).append("\n\n");
        if (!office) text.append(section("companyTaskInstructions", settings)).append("\n\n");
        String addition = additionText(settings, office ? "officeInstructions" : "companyInstructions");
        if (addition != null) text.append(addition).append("\n\n");
        String reply = additionText(settings, "replyInstructions");
        if (reply != null) text.append(reply).append("\n\n");
        text.append(section("sharedInstructions", settings)).append("\n\n");
        return text.toString();
    }

    /**
     * One configured raw base section; only missing legacy values use defaults.
     */
    public static String section(String key, Map<String, Object> settings) {
        if (!BASE_NAMES.contains(key)) throw new IllegalArgumentException("Unknown instruction section: " + key);
        if (settings == null || !settings.containsKey(key)) return defaultValue(key);
        Object value = settings.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > sectionMaxLength(key)) {
            throw new IllegalArgumentException("Invalid instruction section: " + key);
        }
        return text;
    }

    private static String additionText(Map<String, Object> settings, String key) {
        Object value = settings == null ? null : settings.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid instruction section: " + key);
        }
        return text.isBlank() ? null : text;
    }

    private static int sectionMaxLength(String key) {
        return "finalizationInstructions".equals(key) || "compactionInstructions".equals(key)
                ? AUXILIARY_MAX_LENGTH : MAX_LENGTH;
    }

    private static int composedLength(boolean office, Map<String, Object> settings) {
        int length = section(office ? "officeBaseInstructions" : "companyBaseInstructions", settings).length() + 2;
        if (!office) length += section("companyTaskInstructions", settings).length() + 2;
        String addition = additionText(settings, office ? "officeInstructions" : "companyInstructions");
        if (addition != null) length += addition.length() + 2;
        String reply = additionText(settings, "replyInstructions");
        if (reply != null) length += reply.length() + 2;
        return length + section("sharedInstructions", settings).length() + 2;
    }

    private static String defaultValue(String name) {
        return switch (name) {
            case "officeBaseInstructions" -> OFFICE_BASE;
            case "companyBaseInstructions" -> COMPANY_BASE;
            case "sharedInstructions" -> SHARED;
            case "companyTaskInstructions" -> COMPANY_TASKS;
            case "checkpointInstructions" -> CHECKPOINT;
            case "finalizationInstructions" -> FINALIZATION;
            case "compactionInstructions" -> COMPACTION;
            case "officeInstructions", "companyInstructions", "replyInstructions" -> "";
            default -> null;
        };
    }

    private static String load(String resource) {
        try (InputStream stream = AgentInstructions.class.getResourceAsStream("/agent/" + resource)) {
            if (stream == null) throw new IllegalStateException("Missing business instructions: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load business instructions: " + resource, failure);
        }
    }
}
