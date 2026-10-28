package ltd.qianc.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AdminConfigurationContractTest {

    @Test
    void encryptedRevisionsRedactSecretsAndActivateLatestRevisionAfterReopen() throws Exception {
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
        assertThat(first.pendingRestart()).isTrue();
        assertThat(first.view(deployment).activeRevision()).isEqualTo(1);
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
