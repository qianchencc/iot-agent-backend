package com.ehe.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import com.ehe.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class AdminSeedAuthorityTest {
    @TempDir Path directory;

    @Test
    void persistedFileRevisionDoesNotEvaluateBootstrapSeed() {
        Path file = directory.resolve("config.bin");
        try (var first = AdminConfigurationFactory.encryptedFile(new ObjectMapper(), file,
                new byte[32], AdminSeedAuthorityTest::settings)) {
            assertThat(first.save(1, Map.of("modelId", "saved-model"), "fixture-operator").status()).isEqualTo(200);
        }
        try (var reopened = AdminConfigurationFactory.encryptedFile(new ObjectMapper(), file,
                new byte[32], () -> { throw new AssertionError("Seed must not be evaluated"); })) {
            assertThat(reopened.saved()).containsEntry("modelId", "saved-model");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AGENT_SCHEMA_TEST_MYSQL_URL", matches = ".+")
    void existingMysqlRevisionIsAuthoritativeEvenWhenItCannotBeDecrypted() throws Exception {
        String url = System.getenv("AGENT_SCHEMA_TEST_MYSQL_URL");
        String username = System.getenv("AGENT_MYSQL_USERNAME");
        String password = System.getenv("AGENT_MYSQL_PASSWORD");
        String schema = "seed_authority_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(url, username, password); var statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + schema);
            try {
                var source = new DriverManagerDataSource(
                        url.replaceFirst("(jdbc:mysql://[^/]+/)[^?]*", "$1" + schema), username, password);
                var jdbc = new JdbcTemplate(source);
                try (var first = mysql(jdbc, new byte[32], AdminSeedAuthorityTest::settings)) {
                    assertThat(first.save(1, Map.of("modelId", "saved-model"), "fixture-operator").status()).isEqualTo(200);
                }
                Supplier<Map<String, Object>> forbiddenSeed = () -> { throw new AssertionError("Existing data must not trigger seeding"); };
                try (var reopened = mysql(jdbc, new byte[32], forbiddenSeed)) {
                    assertThat(reopened.saved()).containsEntry("modelId", "saved-model");
                }
                byte[] wrongKey = new byte[32];
                wrongKey[0] = 1;
                assertThatThrownBy(() -> mysql(jdbc, wrongKey, forbiddenSeed)).isInstanceOf(BusinessException.class);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_configuration_revisions", Integer.class)).isEqualTo(2);
                try (var reopened = mysql(jdbc, new byte[32], forbiddenSeed)) {
                    assertThat(reopened.saved()).containsEntry("modelId", "saved-model");
                }
            } finally {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }

    private static AdminConfigurationStore mysql(JdbcTemplate jdbc, byte[] key, Supplier<Map<String, Object>> seed) {
        return AdminConfigurationFactory.mysql(jdbc, new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource())), new ObjectMapper(), key, seed);
    }

    private static Map<String, Object> settings() {
        Map<String, Object> initial = new LinkedHashMap<>();
        for (var field : AdminSettingCatalog.fields()) {
            if (field.defaultValue() != null) initial.put(field.name(), field.defaultValue());
        }
        initial.put("modelBaseUrl", "https://model.example.invalid/v1");
        initial.put("modelId", "initial-model");
        initial.put("modelApiKey", "synthetic-key");
        initial.put("draftTtlMs", 600000);
        initial.put("readbackWaitMs", 6000);
        initial.put("controlTimeoutMs", 25000);
        return initial;
    }
}
