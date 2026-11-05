package ltd.qianc.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminConfigurationContractTest {

    @Test
    void encryptedModelRevisionIsAvailableToTheNextRunAndSurvivesReopen() throws Exception {
        Path file = Files.createTempFile("admin-config", ".bin");
        Files.delete(file);
        byte[] key = new byte[32];
        Map<String, Object> initial = validSettings("secret-before");
        AdminConfigurationStore.DeploymentInfo deployment =
                new AdminConfigurationStore.DeploymentInfo("127.0.0.1", 3200, "test", "test", "UTC");

        AdminConfigurationStore first = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), file, key, initial, Set.of());
        AdminConfigurationStore.ConfigurationView initialView = first.view(deployment);
        assertThat(initialView.saved()).doesNotContainKey("modelApiKey");
        assertThat(initialView.secrets().get("modelApiKey")).containsEntry("configured", true);

        assertThat(first.save(1, Map.of("modelApiKey", "secret-after"), "operator").status()).isEqualTo(200);
        assertThat(first.pendingRestart()).isFalse();
        assertThat(first.view(deployment).activeRevision()).isEqualTo(2);
        assertThat(first.active().get("modelApiKey")).isEqualTo("secret-after");
        first.close();

        AdminConfigurationStore reopened = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), file, key, initial, Set.of());
        AdminConfigurationStore.ConfigurationView reopenedView = reopened.view(deployment);
        assertThat(reopenedView.activeRevision()).isEqualTo(2);
        assertThat(reopenedView.pendingRestart()).isFalse();
        assertThat(reopenedView.active()).doesNotContainKey("modelApiKey");
    }

    @Test
    void environmentLockedFieldsCannotBeOverwritten() throws Exception {
        Path file = Files.createTempFile("admin-config", ".bin");
        Files.delete(file);
        AdminConfigurationStore store = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), file, new byte[32], validSettings("secret"), Set.of("modelApiKey"));

        AdminConfigurationStore.SaveResult result = store.save(1, Map.of("modelApiKey", "replacement"), "operator");

        assertThat(result.status()).isEqualTo(409);
        assertThat(result.code()).isEqualTo("CONFIG_ENV_LOCKED");
    }

    @Test
    void ineffectiveCacheSettingsCannotPretendToApplyAndDoNotBreakHistoricalRestore(
            @org.junit.jupiter.api.io.TempDir Path directory) {
        AdminConfigurationStore store = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), directory.resolve("config.bin"), new byte[32],
                validSettings("secret"), Set.of());
        var deployment = new AdminConfigurationStore.DeploymentInfo(
                "127.0.0.1", 3200, "test", "test", "UTC");
        Set<String> retired = Set.of(
                "catalogTtlMs", "authoritativeStateTtlMs", "actionIdTtlMs", "energyReadTtlMs");
        assertThat(store.view(deployment).saved()).doesNotContainKeys(retired.toArray(String[]::new));
        assertThat(store.view(deployment).active()).doesNotContainKeys(retired.toArray(String[]::new));
        for (String field : retired) {
            var rejected = store.save(1, Map.of(field, 1000), "operator");
            assertThat(rejected.status()).isEqualTo(400);
            assertThat(rejected.code()).isEqualTo("CONFIG_UNSUPPORTED");
            assertThat(rejected.issues()).containsExactly(field);
        }
        assertThat(store.save(1, Map.of("modelId", "changed-model"), "operator").status()).isEqualTo(200);
        assertThat(store.restore(2, 1, "operator").status()).isEqualTo(200);
        assertThat(store.view(deployment).saved()).containsEntry("modelId", "test-model");
        store.close();
    }

    @Test
    void liveFieldsApplyToNewSnapshotsWhileRestartFieldsWaitForProcessRestart() throws Exception {
        Path file = Files.createTempDirectory("admin-live-policy").resolve("config.bin");
        AdminConfigurationStore store = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), file, new byte[32], validSettings("retained-secret"), Set.of());
        Map<String, Object> running = store.active();

        assertThat(store.save(1, Map.of("maxToolCalls", 1000, "heartbeatMs", 0), "operator").status())
                .isEqualTo(200);
        assertThat(store.active()).containsEntry("maxToolCalls", 1000).containsEntry("heartbeatMs", 0);
        assertThat(running).containsEntry("maxToolCalls", 10).containsEntry("heartbeatMs", 15000);
        assertThat(store.pendingRestart()).isFalse();

        assertThat(store.save(2, Map.of("langfuseEnabled", true, "langfuseBaseUrl", "https://langfuse.example.test",
                "langfusePublicKey", "pk-lf-test", "langfuseSecretKey", "sk-lf-test"), "operator").status())
                .isEqualTo(200);
        assertThat(store.active()).containsEntry("langfuseEnabled", false);
        assertThat(store.pendingRestart()).isTrue();
        AdminConfigurationStore.ConfigurationView view = store.view(new AdminConfigurationStore.DeploymentInfo(
                "127.0.0.1", 3200, "test", "test", "UTC"));
        assertThat(view.changedFields()).contains("langfuseEnabled", "langfuseBaseUrl");
        assertThat(view.secrets().get("langfuseSecretKey")).containsEntry("configured", true)
                .containsEntry("activeConfigured", false);
        assertThat(view.saved()).doesNotContainKey("langfuseSecretKey").doesNotContainKey("modelApiKey");
        store.close();
    }

    @Test
    void deploymentReportedKnobsRejectSavesAndNeverAppearAsStoreValues() throws Exception {
        Path file = Files.createTempDirectory("admin-deployment").resolve("config.bin");
        AdminConfigurationStore store = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), file, new byte[32], validSettings("secret"), Set.of());
        var deployment = new AdminConfigurationStore.DeploymentInfo("127.0.0.1", 3200, "test", "test", "UTC");

        for (String field : new String[] {"draftTtlMs", "readbackWaitMs", "controlTimeoutMs"}) {
            var rejected = store.save(1, Map.of(field, 30000), "operator");
            assertThat(rejected.status()).isEqualTo(400);
            assertThat(rejected.code()).isEqualTo("CONFIG_READONLY_DEPLOYMENT");
            assertThat(rejected.issues()).containsExactly(field);
        }
        assertThat(store.save(1, Map.of("modelId", "changed-model"), "operator").status()).isEqualTo(200);
        assertThat(store.view(deployment).saved())
                .doesNotContainKeys("draftTtlMs", "readbackWaitMs", "controlTimeoutMs");
        assertThat(store.view(deployment).active())
                .doesNotContainKeys("draftTtlMs", "readbackWaitMs", "controlTimeoutMs");
        assertThat(store.pendingRestart()).isFalse();
        assertThat(store.restore(2, 1, "operator").status()).isEqualTo(200);
        assertThat(store.view(deployment).saved())
                .doesNotContainKeys("draftTtlMs", "readbackWaitMs", "controlTimeoutMs");
        store.close();
    }

    @Test
    void firstBootWithBlankModelConnectionSeedsACompletableRevision() throws Exception {
        Path file = Files.createTempDirectory("admin-blank-seed").resolve("config.bin");
        Map<String, Object> initial = validSettings("");
        initial.put("modelBaseUrl", "");
        initial.put("modelId", "");

        AdminConfigurationStore store = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), file, new byte[32], initial, Set.of());
        AdminConfigurationStore.ConfigurationView view = store.view(
                new AdminConfigurationStore.DeploymentInfo("127.0.0.1", 3200, "test", "test", "UTC"));
        assertThat(view.revision()).isEqualTo(1);
        assertThat(view.secrets().get("modelApiKey")).containsEntry("configured", false);

        assertThat(store.save(1, Map.of("modelBaseUrl", "https://model.example.test/v1", "modelId", "m",
                "modelApiKey", "completed-key"), "operator").status()).isEqualTo(200);
        assertThat(store.active()).containsEntry("modelId", "m");
        store.close();

        Map<String, Object> invalidSeed = validSettings("secret");
        invalidSeed.put("contextWindow", 5);
        assertThatThrownBy(() -> AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), Files.createTempDirectory("admin-invalid-seed").resolve("config.bin"),
                new byte[32], invalidSeed, Set.of()))
                .isInstanceOf(ltd.qianc.iotagent.error.BusinessException.class);
    }

    @Test
    void viewServesTheSingleFieldCatalogWithHonestEffectModes() throws Exception {
        Path file = Files.createTempDirectory("admin-catalog").resolve("config.bin");
        AdminConfigurationStore store = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), file, new byte[32], validSettings("secret"), Set.of());
        var view = store.view(new AdminConfigurationStore.DeploymentInfo("127.0.0.1", 3200, "test", "test", "UTC"));

        assertThat(view.fields()).extracting(AdminSettingField::name)
                .doesNotContain("catalogTtlMs", "authoritativeStateTtlMs", "actionIdTtlMs", "energyReadTtlMs")
                .contains("heartbeatMs", "scanIntervalMs", "langfuseEnabled");
        assertThat(view.fields().stream()
                .filter(field -> AdminSettingField.READONLY.equals(field.effect()))
                .map(AdminSettingField::name).toList())
                .containsExactlyInAnyOrder("draftTtlMs", "readbackWaitMs", "controlTimeoutMs");
        assertThat(view.fields()).filteredOn(AdminSettingField::secret)
                .extracting(AdminSettingField::name)
                .containsExactlyInAnyOrder("modelApiKey", "langfusePublicKey", "langfuseSecretKey");
        assertThat(view.fields()).filteredOn(field -> AdminSettingField.LIVE.equals(field.effect()))
                .allSatisfy(field -> assertThat(field.consumer()).isNotBlank());
        store.close();
    }

    @Test
    void persistenceFailureDoesNotPublishAnUncommittedConfiguration() throws Exception {
        Path parent = Files.createTempDirectory("admin-write-failure");
        Path file = parent.resolve("config.bin");
        AdminConfigurationStore store = AdminConfigurationFactory.encryptedFile(
                new ObjectMapper(), file, new byte[32], validSettings("old-secret"), Set.of());
        Files.delete(file);
        Files.delete(parent);
        Files.writeString(parent, "not a directory");

        assertThatThrownBy(() -> store.save(1, Map.of("modelId", "not-committed"), "operator"))
                .isInstanceOf(ltd.qianc.iotagent.error.BusinessException.class);
        assertThat(store.saved()).containsEntry("modelId", "test-model");
        assertThat(store.changes()).extracting(AdminConfigurationStore.ChangeRecord::revision).containsExactly(1L);
    }

    private static Map<String, Object> validSettings(String apiKey) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("modelBaseUrl", "https://model.example.test/v1");
        settings.put("modelId", "test-model");
        settings.put("modelApiKey", apiKey);
        settings.put("contextWindow", 128000);
        settings.put("maxOutputTokens", 16384);
        settings.put("reasoning", true);
        settings.put("thinkingLevel", "low");
        settings.put("supportsDeveloperRole", false);
        settings.put("supportsReasoningEffort", true);
        settings.put("supportsUsageInStreaming", true);
        settings.put("maxTokensField", "max_completion_tokens");
        settings.put("maxAgentTurns", 6);
        settings.put("maxToolCalls", 10);
        settings.put("officeInstructions", "");
        settings.put("companyInstructions", "");
        settings.put("replyInstructions", "");
        settings.put("runTimeoutMs", 300000);
        settings.put("draftTtlMs", 600000);
        settings.put("heartbeatMs", 15000);
        settings.put("readbackWaitMs", 6000);
        settings.put("controlTimeoutMs", 25000);
        settings.put("scanIntervalMs", 15000);
        settings.put("catalogTtlMs", 60000);
        settings.put("authoritativeStateTtlMs", 3000);
        settings.put("actionIdTtlMs", 600000);
        settings.put("energyReadTtlMs", 30000);
        settings.put("langfuseEnabled", false);
        settings.put("langfuseBaseUrl", "");
        settings.put("langfusePublicKey", "");
        settings.put("langfuseSecretKey", "");
        return settings;
    }
}
