package ltd.qianc.iotagent.admin.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.error.BusinessException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** AES-GCM encrypted revision file for local development and isolated tests. */
public final class EncryptedFileAdminConfigurationStore implements AdminConfigurationStore {
    private static final Set<String> SECRET_KEYS = Set.of("modelApiKey", "langfusePublicKey", "langfuseSecretKey");
    private static final Set<String> KNOWN_KEYS = Set.of(
            "modelBaseUrl", "modelId", "modelApiKey", "contextWindow", "maxOutputTokens", "reasoning",
            "thinkingLevel", "supportsDeveloperRole", "supportsReasoningEffort", "supportsUsageInStreaming",
            "maxTokensField", "maxAgentTurns", "maxToolCalls", "officeInstructions", "companyInstructions",
            "replyInstructions", "runTimeoutMs", "draftTtlMs", "heartbeatMs", "readbackWaitMs", "controlTimeoutMs",
            "scanIntervalMs", "catalogTtlMs", "authoritativeStateTtlMs", "actionIdTtlMs", "energyReadTtlMs",
            "langfuseEnabled", "langfuseBaseUrl", "langfusePublicKey", "langfuseSecretKey");
    private final ObjectMapper mapper;
    private final Path path;
    private final byte[] key;
    private final Set<String> locked;
    private final SecureRandom random = new SecureRandom();
    private State state;

    public EncryptedFileAdminConfigurationStore(ObjectMapper mapper, Path path, byte[] key,
            Map<String, Object> initial, Set<String> locked) {
        this.mapper = Objects.requireNonNull(mapper);
        this.path = Objects.requireNonNull(path);
        this.key = key.clone();
        if (this.key.length != 32) throw new IllegalArgumentException("CONFIG_SECRET_KEY must be 32 bytes");
        this.locked = Set.copyOf(locked);
        this.state = load(initial);
    }

    @Override
    public synchronized ConfigurationView view(DeploymentInfo deployment) {
        Map<String, Object> saved = effective(state.revisions.get(state.revisions.size() - 1).settings);
        Map<String, Object> active = effective(state.revisions.get(state.activeIndex).settings);
        List<String> changed = changedFields(saved, active);
        Map<String, Map<String, Boolean>> secrets = new LinkedHashMap<>();
        for (String secret : SECRET_KEYS) {
            secrets.put(secret, Map.of("configured", Boolean.TRUE.equals(hasValue(saved.get(secret))),
                    "activeConfigured", Boolean.TRUE.equals(hasValue(active.get(secret)))));
        }
        return new ConfigurationView(state.latestRevision(), state.revisions.get(state.activeIndex).revision,
                publicSettings(saved), publicSettings(active), secrets, List.copyOf(locked), !changed.isEmpty(),
                changed, false, deployment);
    }

    @Override
    public synchronized SaveResult save(long expectedRevision, Map<String, Object> changes, String actor) {
        if (state.latestRevision() != expectedRevision) return new SaveResult(409, "CONFIG_VERSION_CONFLICT", List.of());
        if (changes == null || changes.keySet().stream().anyMatch(name -> locked.contains(name))) {
            return new SaveResult(409, "CONFIG_ENV_LOCKED", List.of());
        }
        List<String> unsupported = changes.keySet().stream().filter(RetiredCacheSettings.NAMES::contains).toList();
        if (!unsupported.isEmpty()) return new SaveResult(400, "CONFIG_UNSUPPORTED", unsupported);
        Map<String, Object> previous = state.revisions.get(state.revisions.size() - 1).settings;
        Map<String, Object> next = new LinkedHashMap<>(previous);
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            if (!KNOWN_KEYS.contains(change.getKey())) return new SaveResult(400, "CONFIG_INVALID", List.of(change.getKey()));
            if (change.getValue() == null) return new SaveResult(400, "CONFIG_INVALID", List.of(change.getKey()));
            next.put(change.getKey(), change.getValue());
        }
        List<String> issues = validate(next);
        if (!issues.isEmpty()) return new SaveResult(400, "CONFIG_INVALID", issues);
        List<String> changed = changedFields(previous, next);
        if (changed.isEmpty()) return SaveResult.ok();
        state.revisions.add(new Revision(state.latestRevision() + 1, next, changed, actor, Instant.now().toString()));
        persist();
        return SaveResult.ok();
    }

    @Override
    public synchronized SaveResult restore(long expectedRevision, long revision, String actor) {
        if (state.latestRevision() != expectedRevision) return new SaveResult(409, "CONFIG_VERSION_CONFLICT", List.of());
        Revision target = state.revisions.stream().filter(item -> item.revision == revision).findFirst().orElse(null);
        if (target == null) return new SaveResult(404, "CONFIG_REVISION_NOT_FOUND", List.of());
        Map<String, Object> changes = new LinkedHashMap<>(target.settings);
        RetiredCacheSettings.NAMES.forEach(changes::remove);
        locked.forEach(changes::remove);
        return save(expectedRevision, changes, actor);
    }

    @Override
    public synchronized List<ChangeRecord> changes() {
        List<ChangeRecord> result = new ArrayList<>();
        for (int i = state.revisions.size() - 1; i >= Math.max(0, state.revisions.size() - 50); i--) {
            Revision item = state.revisions.get(i);
            result.add(new ChangeRecord(item.revision, item.at, item.actor, item.changedFields));
        }
        return result;
    }

    @Override
    public synchronized Map<String, Object> saved() {
        return effective(state.revisions.get(state.revisions.size() - 1).settings);
    }

    @Override
    public synchronized Map<String, Object> active() {
        return effective(state.revisions.get(state.activeIndex).settings);
    }

    @Override
    public synchronized boolean pendingRestart() {
        return !changedFields(saved(), active()).isEmpty();
    }

    private State load(Map<String, Object> initial) {
        try {
            if (!Files.exists(path)) {
                if (initial == null || !validate(initial).isEmpty()) throw unavailable();
                State created = new State(new ArrayList<>(List.of(new Revision(1, new LinkedHashMap<>(initial), List.of(), "bootstrap", Instant.now().toString()))), 0);
                persist(created);
                return created;
            }
            byte[] encrypted = Files.readAllBytes(path);
            byte[] plain = decrypt(encrypted);
            State loaded = mapper.readValue(plain, State.class);
            if (loaded.revisions == null || loaded.revisions.isEmpty()) throw unavailable();
            // A process restart activates the latest saved revision; save itself never mutates active state.
            loaded.activeIndex = loaded.revisions.size() - 1;
            return loaded;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private void persist() {
        persist(state);
    }

    private void persist(State value) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temporary, encrypt(mapper.writeValueAsBytes(value)));
            try {
                Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Development filesystems without POSIX permissions are allowed.
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private byte[] encrypt(byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return concat(iv, cipher.doFinal(plain));
    }

    private byte[] decrypt(byte[] encrypted) throws Exception {
        if (encrypted.length < 29) throw new IOException("invalid encrypted configuration");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, encrypted, 0, 12));
        return cipher.doFinal(encrypted, 12, encrypted.length - 12);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private Map<String, Object> effective(Map<String, Object> value) {
        return new LinkedHashMap<>(value);
    }

    private static Map<String, Object> publicSettings(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        SECRET_KEYS.forEach(result::remove);
        RetiredCacheSettings.NAMES.forEach(result::remove);
        return result;
    }

    private static boolean hasValue(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        KNOWN_KEYS.forEach(key -> {
            if (!Objects.equals(before.get(key), after.get(key))) fields.add(key);
        });
        return List.copyOf(fields);
    }

    private static List<String> validate(Map<String, Object> settings) {
        List<String> issues = new ArrayList<>();
        for (String key : settings.keySet()) if (!KNOWN_KEYS.contains(key)) issues.add(key);
        requireString(settings, issues, "modelBaseUrl", 1, 4096);
        requireString(settings, issues, "modelId", 1, 200);
        requireString(settings, issues, "modelApiKey", 1, 4096);
        String baseUrl = string(settings.get("modelBaseUrl"));
        try {
            java.net.URI uri = baseUrl == null ? null : java.net.URI.create(baseUrl);
            if (uri == null || uri.getScheme() == null || !uri.getScheme().matches("https?")
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) issues.add("modelBaseUrl");
        } catch (IllegalArgumentException e) {
            issues.add("modelBaseUrl");
        }
        integer(settings, issues, "contextWindow", 8192, 2_000_000);
        integer(settings, issues, "maxOutputTokens", 256, 128_000);
        integer(settings, issues, "maxAgentTurns", 1, 30);
        integer(settings, issues, "maxToolCalls", 1, 100);
        integer(settings, issues, "runTimeoutMs", 10_000, 1_800_000);
        integer(settings, issues, "draftTtlMs", 30_000, 3_600_000);
        integer(settings, issues, "heartbeatMs", 0, 60_000);
        integer(settings, issues, "readbackWaitMs", 0, 120_000);
        integer(settings, issues, "controlTimeoutMs", 1_000, 120_000);
        integer(settings, issues, "scanIntervalMs", 1_000, 60_000);
        for (String key : List.of("reasoning", "supportsDeveloperRole", "supportsReasoningEffort", "supportsUsageInStreaming", "langfuseEnabled")) if (!(settings.get(key) instanceof Boolean)) issues.add(key);
        String thinking = string(settings.get("thinkingLevel"));
        if (thinking != null && !Set.of("off", "low", "medium", "high").contains(thinking)) issues.add("thinkingLevel");
        String maxTokens = string(settings.get("maxTokensField"));
        if (maxTokens != null && !Set.of("max_completion_tokens", "max_tokens").contains(maxTokens)) issues.add("maxTokensField");
        if (number(settings.get("maxOutputTokens")) != null && number(settings.get("contextWindow")) != null && number(settings.get("maxOutputTokens")) >= number(settings.get("contextWindow"))) issues.add("maxOutputTokens");
        if (Boolean.FALSE.equals(settings.get("reasoning")) && !"off".equals(thinking)) issues.add("thinkingLevel");
        if (Boolean.TRUE.equals(settings.get("langfuseEnabled")) && (blank(settings.get("langfuseBaseUrl")) || blank(settings.get("langfusePublicKey")) || blank(settings.get("langfuseSecretKey")))) issues.add("langfuseEnabled");
        return List.copyOf(new LinkedHashSet<>(issues));
    }

    private static void requireString(Map<String, Object> values, List<String> issues, String key, int min, int max) {
        String value = string(values.get(key));
        if (value == null || value.trim().isEmpty() || value.length() < min || value.length() > max) issues.add(key);
    }

    private static void integer(Map<String, Object> values, List<String> issues, String key, int min, int max) {
        Integer value = number(values.get(key));
        if (value == null || value < min || value > max) issues.add(key);
    }

    private static Integer number(Object value) {
        if (value instanceof Number number && number.intValue() == number.doubleValue()) return number.intValue();
        return null;
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean blank(Object value) {
        return !(value instanceof String text) || text.isBlank();
    }

    private static BusinessException unavailable() {
        return new BusinessException("ADMIN_CONFIG_UNAVAILABLE", "管理配置存储暂不可用，请稍后重试", 503);
    }

    public static final class State {
        public List<Revision> revisions;
        public int activeIndex;
        public State() {
        }
        State(List<Revision> revisions, int activeIndex) {
            this.revisions = revisions;
            this.activeIndex = activeIndex;
        }
        long latestRevision() { return revisions.get(revisions.size() - 1).revision; }
    }

    public static final class Revision {
        public long revision;
        public Map<String, Object> settings;
        public List<String> changedFields;
        public String actor;
        public String at;
        public Revision() {
        }
        Revision(long revision, Map<String, Object> settings, List<String> changedFields, String actor, String at) {
            this.revision = revision;
            this.settings = settings;
            this.changedFields = changedFields;
            this.actor = actor;
            this.at = at;
        }
    }
}
