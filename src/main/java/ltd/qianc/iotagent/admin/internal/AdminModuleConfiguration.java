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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class AdminModuleConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AdminConfigurationStore adminConfigurationStore(ObjectMapper mapper, Environment environment,
            @Value("${admin.config-file:var/admin-config.bin}") String configFile,
            @Value("${admin.config-secret-key:}") String secretKey,
            ObjectProvider<JdbcTemplate> jdbcProvider, ObjectProvider<TransactionTemplate> transactionProvider) {
        boolean mysqlEnabled = Boolean.parseBoolean(environment.getProperty("agent.storage.mysql.enabled", "false"));
        if (!secretKey.matches("[a-fA-F0-9]{64}")) return new FailClosedAdminConfigurationStore();
        // admin.initial.* seeds only the very first revision; existing MySQL
        // revisions stay authoritative across restarts.
        if (mysqlEnabled) {
            JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
            TransactionTemplate transactions = transactionProvider.getIfAvailable();
            if (jdbc == null || transactions == null) return new FailClosedAdminConfigurationStore();
            try {
                return AdminConfigurationFactory.mysql(jdbc, transactions, mapper, hex(secretKey), () -> initial(environment));
            } catch (RuntimeException e) {
                return new FailClosedAdminConfigurationStore();
            }
        }
        try {
            return AdminConfigurationFactory.encryptedFile(mapper, Path.of(configFile), hex(secretKey), () -> initial(environment));
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
    AdminObservabilityStatus adminObservabilityStatus(AdminConfigurationStore store,
            ObjectProvider<WebClient.Builder> builders,
            @Value("${agent.bridge.base-url:}") String baseUrl,
            @Value("${agent.bridge.token:}") String token) {
        WebClient client = baseUrl.isBlank() || token.isBlank() ? null
                : builders.getIfAvailable(WebClient::builder).clone().baseUrl(baseUrl).build();
        return new AdminObservabilityStatus(store, client, token);
    }

    @Bean
    @ConditionalOnMissingBean
    AdminSessionManager adminSessionManager(
            @Value("${admin.password-minimum:6}") int minimum,
            @Value("${admin.password:}") String configured,
            @Value("${admin.sessions-file:var/admin-sessions.json}") String sessionsFile) {
        // Deployment-only credential: the native admin.password property; empty fails closed.
        // Empty sessions-file keeps sessions memory-only (tests); otherwise they survive restarts.
        Path file = sessionsFile == null || sessionsFile.isBlank() ? null : Path.of(sessionsFile);
        return new AdminSessionManager(configured, Math.max(1, Math.min(128, minimum)), file);
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
            @Value("${admin.origin:http://127.0.0.1:5185}") String origin) {
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

    /**
     * First-install managed defaults (admin.initial.*). They seed revision 1 only
     * when the store has no existing revision; afterwards saved revisions win.
     */
    static Map<String, Object> initial(Environment environment) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("modelBaseUrl", environment.getProperty("admin.initial.model-base-url", ""));
        values.put("modelId", environment.getProperty("admin.initial.model-id", ""));
        values.put("modelApiKey", environment.getProperty("admin.initial.model-api-key", ""));
        values.put("contextWindow", integer(environment, "admin.initial.context-window", 128000));
        values.put("maxOutputTokens", integer(environment, "admin.initial.max-output-tokens", 16384));
        values.put("reasoning", bool(environment, "admin.initial.reasoning", true));
        values.put("thinkingLevel", environment.getProperty("admin.initial.thinking-level", "low"));
        values.put("supportsDeveloperRole", bool(environment, "admin.initial.supports-developer-role", false));
        values.put("supportsReasoningEffort", bool(environment, "admin.initial.supports-reasoning-effort", true));
        values.put("supportsUsageInStreaming", bool(environment, "admin.initial.supports-usage-in-streaming", true));
        values.put("maxTokensField", environment.getProperty("admin.initial.max-tokens-field", "max_completion_tokens"));
        values.put("maxAgentTurns", integer(environment, "admin.initial.max-agent-turns", 6));
        values.put("maxToolCalls", integer(environment, "admin.initial.max-tool-calls", 10));
        values.put("officeInstructions", environment.getProperty("admin.initial.office-instructions", ""));
        values.put("companyInstructions", environment.getProperty("admin.initial.company-instructions", ""));
        values.put("replyInstructions", environment.getProperty("admin.initial.reply-instructions", ""));
        values.put("runTimeoutMs", integer(environment, "admin.initial.run-timeout-ms", 300000));
        values.put("draftTtlMs", integer(environment, "admin.initial.draft-ttl-ms", 600000));
        values.put("heartbeatMs", integer(environment, "admin.initial.heartbeat-ms", 15000));
        values.put("readbackWaitMs", integer(environment, "admin.initial.readback-wait-ms", 6000));
        values.put("controlTimeoutMs", integer(environment, "admin.initial.control-timeout-ms", 25000));
        values.put("scanIntervalMs", integer(environment, "admin.initial.scan-interval-ms", 1000));
        values.put("langfuseEnabled", bool(environment, "admin.initial.langfuse-enabled", false));
        values.put("langfuseBaseUrl", environment.getProperty("admin.initial.langfuse-base-url", ""));
        values.put("langfusePublicKey", environment.getProperty("admin.initial.langfuse-public-key", ""));
        values.put("langfuseSecretKey", environment.getProperty("admin.initial.langfuse-secret-key", ""));
        return values;
    }

    private static int integer(Environment environment, String name, int fallback) {
        try { return Integer.parseInt(environment.getProperty(name, Integer.toString(fallback))); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("配置无效：" + name); }
    }
    private static boolean bool(Environment environment, String name, boolean fallback) {
        String value = environment.getProperty(name, Boolean.toString(fallback));
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("配置无效：" + name);
    }
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
}
