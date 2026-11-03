package ltd.qianc.iotagent.admin.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.admin.AdminApplyCoordinator;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.admin.AdminPasswordPolicy;
import ltd.qianc.iotagent.admin.AdminWorkState;
import ltd.qianc.iotagent.admin.PiSessionOperations;
import ltd.qianc.iotagent.admin.PiSessionOperationsFactory;
import ltd.qianc.iotagent.pisession.PiSessionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import ltd.qianc.iotagent.admin.AdminConfigurationFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Configuration
public class AdminModuleConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AdminConfigurationStore adminConfigurationStore(ObjectMapper mapper, Environment environment,
            @Value("${admin.config-file:${ADMIN_CONFIG_FILE:var/admin-config.bin}}") String configFile,
            @Value("${admin.config-secret-key:${CONFIG_SECRET_KEY:}}") String secretKey,
            ObjectProvider<JdbcTemplate> jdbcProvider, ObjectProvider<TransactionTemplate> transactionProvider) {
        boolean mysqlEnabled = Boolean.parseBoolean(environment.getProperty("agent.storage.mysql.enabled", "false"));
        if (!secretKey.matches("[a-fA-F0-9]{64}")) return new FailClosedAdminConfigurationStore();
        Map<String, Object> initial = initial(environment);
        Set<String> locked = locked(environment);
        if (mysqlEnabled) {
            JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
            TransactionTemplate transactions = transactionProvider.getIfAvailable();
            if (jdbc == null || transactions == null) return new FailClosedAdminConfigurationStore();
            try {
                return AdminConfigurationFactory.mysql(jdbc, transactions, mapper, hex(secretKey), initial, locked);
            } catch (RuntimeException e) {
                return new FailClosedAdminConfigurationStore();
            }
        }
        try {
            return AdminConfigurationFactory.encryptedFile(mapper, Path.of(configFile), hex(secretKey), initial, locked);
        } catch (RuntimeException e) {
            return new FailClosedAdminConfigurationStore();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    AdminModelTester adminModelTester(ObjectMapper mapper) {
        return new AdminModelTester(mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    AdminSessionManager adminSessionManager(
            @Value("${admin.password-minimum:${ADMIN_PASSWORD_MIN_LENGTH:6}}") int minimum,
            @Value("${admin.password:${ADMIN_PASSWORD:}}") String configured,
            @Value("${admin.password-file:${ADMIN_PASSWORD_FILE:var/admin-password}}") String passwordFile) {
        String password = configured;
        if (password == null || password.isBlank()) {
            try {
                Path path = Path.of(passwordFile);
                if (Files.exists(path)) password = Files.readString(path, StandardCharsets.UTF_8).stripTrailing();
            } catch (Exception ignored) {
                password = null;
            }
        }
        if (password != null && !password.isBlank()) {
            try {
                AdminPasswordPolicy.validate(password, minimum);
            } catch (IllegalArgumentException e) {
                password = null;
            }
        }
        return new AdminSessionManager(password, Math.max(1, Math.min(128, minimum)));
    }

    @Bean
    @ConditionalOnMissingBean
    PiSessionOperations piSessionOperations(ObjectProvider<PiSessionService> sessions) {
        PiSessionService store = sessions.getIfAvailable();
        return store == null ? PiSessionOperationsFactory.unavailable() : PiSessionOperationsFactory.mysql(store);
    }

    @Bean
    @ConditionalOnMissingBean
    FilterRegistrationBean<AdminBoundaryFilter> adminBoundaryFilter(ObjectMapper mapper, AdminSessionManager sessions,
            @Value("${admin.origin:${ADMIN_ORIGIN:http://127.0.0.1:3200}}") String origin) {
        FilterRegistrationBean<AdminBoundaryFilter> registration = new FilterRegistrationBean<>(
                new AdminBoundaryFilter(mapper, sessions, origin));
        registration.addUrlPatterns("/api/admin/*");
        registration.setOrder(-100);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    AdminWorkState adminWorkState() {
        return new AdminWorkState() {
            @Override public boolean busy() { return false; }
            @Override public String reason() { return ""; }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    AdminApplyCoordinator adminApplyCoordinator(AdminConfigurationStore store, AdminWorkState workState,
            ObjectProvider<WebClient.Builder> builders,
            @Value("${admin.supervisor.url:}") String supervisorUrl,
            @Value("${admin.supervisor.token:}") String supervisorToken) {
        if (supervisorUrl != null && !supervisorUrl.isBlank() && supervisorToken != null && !supervisorToken.isBlank()) {
            try {
                WebClient.Builder builder = builders.getIfAvailable(WebClient::builder);
                if (builder != null) {
                    return new SupervisedAdminApplyCoordinator(store, workState,
                            builder.baseUrl(supervisorUrl).build(), supervisorToken);
                }
            } catch (RuntimeException ignored) {
                // Invalid supervisor configuration remains fail-closed.
            }
        }
        return new AdminApplyCoordinator() {
            @Override
            public AdminApplyCoordinator.ApplyResult apply() {
                if (!store.pendingRestart()) return new AdminApplyCoordinator.ApplyResult(409, "CONFIG_NO_PENDING", "当前没有待生效的配置");
                if (workState.busy()) return new AdminApplyCoordinator.ApplyResult(409, "CONFIG_APPLY_BUSY", "当前有在途工作，请完成后再立即生效");
                return new AdminApplyCoordinator.ApplyResult(503, "CONFIG_APPLY_UNAVAILABLE", "此部署未接入受监督的进程应用，请手动重启后端");
            }
        };
    }

    static Map<String, Object> initial(Environment environment) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("modelBaseUrl", environment.getProperty("MODEL_BASE_URL", ""));
        values.put("modelId", environment.getProperty("MODEL_ID", ""));
        values.put("modelApiKey", environment.getProperty("MODEL_API_KEY", ""));
        values.put("contextWindow", integer(environment, "CONTEXT_WINDOW", 128000));
        values.put("maxOutputTokens", integer(environment, "MAX_OUTPUT_TOKENS", 16384));
        values.put("reasoning", bool(environment, "AGENT_REASONING", true));
        values.put("thinkingLevel", environment.getProperty("AGENT_THINKING_LEVEL", "low"));
        values.put("supportsDeveloperRole", bool(environment, "MODEL_SUPPORTS_DEVELOPER_ROLE", false));
        values.put("supportsReasoningEffort", bool(environment, "MODEL_SUPPORTS_REASONING_EFFORT", true));
        values.put("supportsUsageInStreaming", bool(environment, "MODEL_SUPPORTS_USAGE_IN_STREAMING", true));
        values.put("maxTokensField", environment.getProperty("MODEL_MAX_TOKENS_FIELD", "max_completion_tokens"));
        values.put("maxAgentTurns", integer(environment, "MAX_AGENT_TURNS", 6));
        values.put("maxToolCalls", integer(environment, "MAX_TOOL_CALLS", 10));
        values.put("officeInstructions", environment.getProperty("OFFICE_INSTRUCTIONS", ""));
        values.put("companyInstructions", environment.getProperty("COMPANY_INSTRUCTIONS", ""));
        values.put("replyInstructions", environment.getProperty("REPLY_INSTRUCTIONS", ""));
        values.put("runTimeoutMs", integer(environment, "RUN_TIMEOUT_MS", 300000));
        values.put("draftTtlMs", integer(environment, "DRAFT_TTL_MS", 600000));
        values.put("heartbeatMs", integer(environment, "HEARTBEAT_MS", 15000));
        values.put("readbackWaitMs", integer(environment, "READBACK_WAIT_MS", 6000));
        values.put("controlTimeoutMs", integer(environment, "CONTROL_TIMEOUT_MS", 25000));
        values.put("scanIntervalMs", integer(environment, "SCAN_INTERVAL_MS", 15000));
        values.put("catalogTtlMs", integer(environment, "IOT_CATALOG_TTL_MS", 60000));
        values.put("authoritativeStateTtlMs", integer(environment, "AUTHORITATIVE_STATE_TTL_MS", 3000));
        values.put("actionIdTtlMs", integer(environment, "ACTION_ID_TTL_MS", 600000));
        values.put("energyReadTtlMs", integer(environment, "ENERGY_READ_TTL_MS", 30000));
        values.put("langfuseEnabled", bool(environment, "LANGFUSE_ENABLED", false));
        values.put("langfuseBaseUrl", environment.getProperty("LANGFUSE_BASE_URL", ""));
        values.put("langfusePublicKey", environment.getProperty("LANGFUSE_PUBLIC_KEY", ""));
        values.put("langfuseSecretKey", environment.getProperty("LANGFUSE_SECRET_KEY", ""));
        return values;
    }

    static Set<String> locked(Environment environment) {
        Set<String> result = new java.util.LinkedHashSet<>();
        Map<String, String> fields = Map.of("modelBaseUrl", "MODEL_BASE_URL", "modelId", "MODEL_ID", "modelApiKey", "MODEL_API_KEY", "thinkingLevel", "AGENT_THINKING_LEVEL", "catalogTtlMs", "IOT_CATALOG_TTL_MS", "heartbeatMs", "HEARTBEAT_MS", "langfuseBaseUrl", "LANGFUSE_BASE_URL", "langfusePublicKey", "LANGFUSE_PUBLIC_KEY", "langfuseSecretKey", "LANGFUSE_SECRET_KEY");
        fields.forEach((name, variable) -> {
            if (environment.getProperty(variable) != null && !LiveAgentSettings.contains(name)) result.add(name);
        });
        return result;
    }

    private static int integer(Environment environment, String name, int fallback) {
        try { return Integer.parseInt(environment.getProperty(name, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static boolean bool(Environment environment, String name, boolean fallback) {
        return Boolean.parseBoolean(environment.getProperty(name, Boolean.toString(fallback)));
    }
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
