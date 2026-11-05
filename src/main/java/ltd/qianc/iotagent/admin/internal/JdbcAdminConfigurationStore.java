package ltd.qianc.iotagent.admin.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.instructions.AgentInstructions;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * MySQL-backed encrypted configuration revisions. The database stores ciphertext only;
 * the key remains an explicitly supplied local/deployment secret.
 */
public final class JdbcAdminConfigurationStore implements AdminConfigurationStore {
    private static final Set<String> SECRET_KEYS = Set.of("modelApiKey", "langfusePublicKey", "langfuseSecretKey");
    private static final Set<String> KNOWN_KEYS = Set.of(
            "modelBaseUrl", "modelId", "modelApiKey", "contextWindow", "maxOutputTokens", "reasoning",
            "thinkingLevel", "supportsDeveloperRole", "supportsReasoningEffort", "supportsUsageInStreaming",
            "maxTokensField", "maxAgentTurns", "maxToolCalls", "officeBaseInstructions", "companyBaseInstructions",
            "sharedInstructions", "companyTaskInstructions", "checkpointInstructions", "finalizationInstructions",
            "compactionInstructions", "officeInstructions", "companyInstructions", "replyInstructions",
            "runTimeoutMs", "draftTtlMs", "heartbeatMs", "readbackWaitMs", "controlTimeoutMs",
            "scanIntervalMs", "catalogTtlMs", "authoritativeStateTtlMs", "actionIdTtlMs", "energyReadTtlMs",
            "langfuseEnabled", "langfuseBaseUrl", "langfusePublicKey", "langfuseSecretKey");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final byte[] key;
    private final Set<String> locked;
    private final SecureRandom random = new SecureRandom();
    private long activeRevision;

    public JdbcAdminConfigurationStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            byte[] key, Map<String, Object> initial, Set<String> locked) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.mapper = Objects.requireNonNull(mapper);
        this.key = Objects.requireNonNull(key).clone();
        if (this.key.length != 32) throw new IllegalArgumentException("CONFIG_SECRET_KEY must be 32 bytes");
        this.locked = Set.copyOf(locked);
        new ltd.qianc.iotagent.schema.SchemaMigrations(
                Objects.requireNonNull(jdbc.getDataSource(), "jdbc dataSource")).ensureAdminConfiguration();
        this.activeRevision = initialize(initial);
    }

    @Override
    public synchronized ConfigurationView view(DeploymentInfo deployment) {
        Revision latest = latest();
        Revision active = revision(activeRevision);
        Map<String, Object> saved = latest.settings;
        Map<String, Object> running = LiveAgentSettings.effective(active.settings, saved);
        List<String> changed = changedFields(saved, running);
        Map<String, Map<String, Boolean>> secrets = new LinkedHashMap<>();
        for (String secret : SECRET_KEYS) {
            secrets.put(secret, Map.of("configured", hasValue(saved.get(secret)),
                    "activeConfigured", hasValue(running.get(secret))));
        }
        return new ConfigurationView(latest.revision, changed.isEmpty() ? latest.revision : active.revision, publicSettings(saved), publicSettings(running),
                secrets, List.copyOf(locked), !changed.isEmpty(), changed, false, deployment);
    }

    @Override
    public synchronized SaveResult save(long expectedRevision, Map<String, Object> changes, String actor) {
        if (changes == null) return new SaveResult(400, "CONFIG_INVALID", List.of());
        List<String> unsupported = changes.keySet().stream().filter(RetiredCacheSettings.NAMES::contains).toList();
        if (!unsupported.isEmpty()) return new SaveResult(400, "CONFIG_UNSUPPORTED", unsupported);
        List<String> deployment = changes.keySet().stream().filter(DeploymentReportedSettings.NAMES::contains).toList();
        if (!deployment.isEmpty()) return new SaveResult(400, "CONFIG_READONLY_DEPLOYMENT", deployment);
        try {
            return transactions.execute(status -> {
                Revision previous = latestForUpdate();
                if (previous.revision != expectedRevision) return new SaveResult(409, "CONFIG_VERSION_CONFLICT", List.of());
                if (changes.keySet().stream().anyMatch(locked::contains)) {
                    return new SaveResult(409, "CONFIG_ENV_LOCKED", List.of());
                }
                Map<String, Object> next = new LinkedHashMap<>(previous.settings);
                for (Map.Entry<String, Object> change : changes.entrySet()) {
                    if (!KNOWN_KEYS.contains(change.getKey()) || change.getValue() == null) {
                        return new SaveResult(400, "CONFIG_INVALID", List.of(change.getKey()));
                    }
                    next.put(change.getKey(), change.getValue());
                }
                List<String> issues = validate(next);
                if (!issues.isEmpty()) return new SaveResult(400, "CONFIG_INVALID", issues);
                List<String> changed = changedFields(previous.settings, next);
                if (changed.isEmpty()) return SaveResult.ok();
                long revision = previous.revision + 1;
                insert(revision, next, changed, actor);
                return SaveResult.ok();
            });
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    @Override
    public synchronized SaveResult restore(long expectedRevision, long targetRevision, String actor) {
        if (targetRevision < 1) return new SaveResult(404, "CONFIG_REVISION_NOT_FOUND", List.of());
        try {
            return transactions.execute(status -> {
                Revision previous = latestForUpdate();
                if (previous.revision != expectedRevision) return new SaveResult(409, "CONFIG_VERSION_CONFLICT", List.of());
                Revision target;
                try {
                    target = revision(targetRevision);
                } catch (BusinessException e) {
                    return new SaveResult(404, "CONFIG_REVISION_NOT_FOUND", List.of());
                }
                Map<String, Object> changes = new LinkedHashMap<>(target.settings);
                RetiredCacheSettings.NAMES.forEach(changes::remove);
                DeploymentReportedSettings.NAMES.forEach(changes::remove);
                locked.forEach(changes::remove);
                Map<String, Object> next = new LinkedHashMap<>(previous.settings);
                next.putAll(changes);
                List<String> issues = validate(next);
                if (!issues.isEmpty()) return new SaveResult(400, "CONFIG_INVALID", issues);
                List<String> changed = changedFields(previous.settings, next);
                if (changed.isEmpty()) return SaveResult.ok();
                insert(previous.revision + 1, next, changed, actor);
                return SaveResult.ok();
            });
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    @Override
    public synchronized List<ChangeRecord> changes() {
        try {
            return jdbc.query("SELECT revision,changed_at,actor,changed_fields_json FROM admin_configuration_revisions ORDER BY revision DESC LIMIT 50",
                    (rs, row) -> new ChangeRecord(rs.getLong(1), rs.getTimestamp(2).toInstant().toString(),
                            rs.getString(3), readStringList(rs.getString(4))));
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    @Override
    public synchronized Map<String, Object> saved() {
        return new LinkedHashMap<>(latest().settings);
    }

    @Override
    public synchronized Map<String, Object> active() {
        return LiveAgentSettings.effective(revision(activeRevision).settings, latest().settings);
    }

    @Override
    public synchronized boolean pendingRestart() {
        Revision saved = latest();
        return !changedFields(saved.settings, LiveAgentSettings.effective(revision(activeRevision).settings, saved.settings)).isEmpty();
    }


    private long initialize(Map<String, Object> initial) {
        try {
            Revision existing = latest();
            return existing.revision;
        } catch (BusinessException missing) {
            if (initial == null || !seedIssues(initial).isEmpty()) throw unavailable();
            try {
                transactions.executeWithoutResult(status -> insert(1,
                        AgentInstructions.withDefaults(initial), List.of(), "bootstrap"));
                return 1;
            } catch (RuntimeException e) {
                throw unavailable();
            }
        }
    }

    /**
     * First-boot seeding may leave the model connection blank so an operator can
     * log in and complete it in the console; every other rule still applies.
     */
    private static List<String> seedIssues(Map<String, Object> initial) {
        List<String> issues = new ArrayList<>(validate(initial));
        for (String completable : List.of("modelBaseUrl", "modelId", "modelApiKey")) {
            if (!(initial.get(completable) instanceof String text) || text.isBlank()) issues.remove(completable);
        }
        return issues;
    }

    private Revision latestForUpdate() {
        return jdbc.queryForObject("SELECT revision,settings_ciphertext,changed_fields_json,actor,changed_at FROM admin_configuration_revisions ORDER BY revision DESC LIMIT 1 FOR UPDATE", this::mapRevision);
    }

    private Revision latest() {
        try {
            return jdbc.queryForObject("SELECT revision,settings_ciphertext,changed_fields_json,actor,changed_at FROM admin_configuration_revisions ORDER BY revision DESC LIMIT 1", this::mapRevision);
        } catch (EmptyResultDataAccessException e) {
            throw new BusinessException("ADMIN_CONFIG_UNAVAILABLE", "管理配置存储暂不可用，请稍后重试", 503);
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    private Revision revision(long value) {
        try {
            return jdbc.queryForObject("SELECT revision,settings_ciphertext,changed_fields_json,actor,changed_at FROM admin_configuration_revisions WHERE revision=?", this::mapRevision, value);
        } catch (EmptyResultDataAccessException e) {
            throw new BusinessException("CONFIG_REVISION_NOT_FOUND", "配置版本不存在", 404);
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    private Revision mapRevision(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        try {
            Map<String, Object> settings = AgentInstructions.withDefaults(
                    mapper.readValue(decrypt(rs.getBytes(2)), new TypeReference<>() { }));
            return new Revision(rs.getLong(1), settings, readStringList(rs.getString(3)), rs.getString(4), rs.getTimestamp(5).toInstant());
        } catch (Exception e) {
            throw new java.sql.SQLException("invalid encrypted admin configuration", e);
        }
    }

    private void insert(long revision, Map<String, Object> settings, List<String> changed, String actor) {
        try {
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("INSERT INTO admin_configuration_revisions(revision,settings_ciphertext,changed_fields_json,actor,changed_at,create_time,update_time) VALUES (?,?,?,?,?,?,?)",
                    revision, encrypt(mapper.writeValueAsBytes(settings)), mapper.writeValueAsString(changed), actor == null ? "operator" : actor,
                    now, now, now);
        } catch (Exception e) {
            throw new IllegalStateException("admin configuration write failed", e);
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
        if (encrypted == null || encrypted.length < 29) throw new IllegalStateException("invalid encrypted configuration");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, encrypted, 0, 12));
        return cipher.doFinal(encrypted, 12, encrypted.length - 12);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static List<String> readStringList(String json) {
        try {
            return new ObjectMapper().readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new IllegalStateException("invalid configuration change history", e);
        }
    }
    private static Map<String, Object> publicSettings(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        SECRET_KEYS.forEach(result::remove);
        RetiredCacheSettings.NAMES.forEach(result::remove);
        DeploymentReportedSettings.NAMES.forEach(result::remove);
        return result;
    }

    private static List<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        KNOWN_KEYS.forEach(key -> {
            if (DeploymentReportedSettings.NAMES.contains(key)) return;
            if (!Objects.equals(before.get(key), after.get(key))) fields.add(key);
        });
        return List.copyOf(fields);
    }

    private static List<String> validate(Map<String, Object> settings) {
        List<String> issues = new ArrayList<>();
        issues.addAll(AgentInstructions.validate(settings));
        for (String key : settings.keySet()) if (!KNOWN_KEYS.contains(key)) issues.add(key);
        requireString(settings, issues, "modelBaseUrl", 1, 4096);
        requireString(settings, issues, "modelId", 1, 200);
        requireString(settings, issues, "modelApiKey", 1, 4096);
        String baseUrl = string(settings.get("modelBaseUrl"));
        try {
            java.net.URI uri = baseUrl == null ? null : java.net.URI.create(baseUrl);
            if (uri == null || uri.getScheme() == null || !uri.getScheme().matches("https?")
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) issues.add("modelBaseUrl");
        } catch (IllegalArgumentException e) { issues.add("modelBaseUrl"); }
        integer(settings, issues, "contextWindow", 8192, 2_000_000);
        integer(settings, issues, "maxOutputTokens", 256, 128_000);
        integer(settings, issues, "maxAgentTurns", 1, 30);
        integer(settings, issues, "maxToolCalls", 1, 1000);
        integer(settings, issues, "runTimeoutMs", 1000, 600_000);
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

    private static String string(Object value) { return value instanceof String text ? text : null; }
    private static boolean hasValue(Object value) { return value instanceof String text && !text.isBlank(); }
    private static boolean blank(Object value) { return !(value instanceof String text) || text.isBlank(); }
    private static BusinessException unavailable() { return new BusinessException("ADMIN_CONFIG_UNAVAILABLE", "管理配置存储暂不可用，请稍后重试", 503); }

    private record Revision(long revision, Map<String, Object> settings, List<String> changedFields, String actor, Instant at) { }
}
