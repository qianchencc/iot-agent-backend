package ltd.qianc.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ltd.qianc.iotagent.instructions.AgentInstructions;

/**
 * Instruction sections through the existing encrypted revision store: legacy
 * revisions hydrate the base sections, saved sections reach the next run
 * without restart, and explicit values survive reopening.
 */
class AdminInstructionConfigurationContractTest {
    private static final AdminConfigurationStore.DeploymentInfo DEPLOYMENT =
            new AdminConfigurationStore.DeploymentInfo("127.0.0.1", 3200, "test", "test", "UTC");

    @TempDir
    Path directory;

    @Test
    void legacyAdditionsOnlyRevisionKeepsItsAdditionsAndGainsTheBaseSections() {
        Map<String, Object> legacy = validSettings();
        legacy.put("officeInstructions", "legacy-office-addition");
        legacy.put("companyInstructions", "legacy-company-addition");
        legacy.put("replyInstructions", "legacy-reply-addition");
        AdminConfigurationStore store = store(directory.resolve("legacy.bin"), legacy);

        AdminConfigurationStore.ConfigurationView view = store.view(DEPLOYMENT);
        for (String section : AgentInstructions.names()) {
            assertThat(view.saved()).containsKey(section);
            assertThat(view.active()).containsKey(section);
        }
        for (Map<String, Object> exposed : List.of(view.saved(), view.active())) {
            assertThat(exposed.get("officeBaseInstructions"))
                    .isEqualTo(AgentInstructions.defaults().get("officeBaseInstructions"));
            assertThat(exposed.get("companyTaskInstructions"))
                    .isEqualTo(AgentInstructions.defaults().get("companyTaskInstructions"));
            assertThat(exposed).containsEntry("officeInstructions", "legacy-office-addition")
                    .containsEntry("companyInstructions", "legacy-company-addition")
                    .containsEntry("replyInstructions", "legacy-reply-addition");
        }
        assertThat(view.pendingRestart()).isFalse();

        Map<String, Object> active = store.active();
        assertThat(AgentInstructions.compose("office", active)).isEqualTo(
                AgentInstructions.defaults().get("officeBaseInstructions") + "\n\n"
                        + "legacy-office-addition\n\n" + "legacy-reply-addition\n\n"
                        + AgentInstructions.defaults().get("sharedInstructions") + "\n\n");
        assertThat(AgentInstructions.compose("company", active)).contains("legacy-company-addition\n\n");
        store.close();
    }

    @Test
    void savedSectionsReachTheNextRunWithoutRestartAndSurviveReopen() throws Exception {
        Path file = directory.resolve("live.bin");
        AdminConfigurationStore store = store(file, validSettings());
        Map<String, Object> before = store.active();

        assertThat(store.save(1, Map.of("officeBaseInstructions", "office-base-override",
                "checkpointInstructions", "checkpoint-override", "officeInstructions", "new-addition"),
                "operator").status()).isEqualTo(200);

        Map<String, Object> active = store.active();
        assertThat(active).containsEntry("officeBaseInstructions", "office-base-override")
                .containsEntry("checkpointInstructions", "checkpoint-override")
                .containsEntry("officeInstructions", "new-addition");
        assertThat(before.get("officeBaseInstructions")).isEqualTo(
                AgentInstructions.defaults().get("officeBaseInstructions"));
        assertThat(store.pendingRestart()).isFalse();
        assertThat(AgentInstructions.compose("office", active))
                .startsWith("office-base-override\n\n").contains("new-addition\n\n");
        assertThat(AgentInstructions.section("checkpointInstructions", active)).isEqualTo("checkpoint-override");
        store.close();

        AdminConfigurationStore reopened = store(file, validSettings());
        assertThat(reopened.view(DEPLOYMENT).revision()).isEqualTo(2);
        assertThat(reopened.saved()).containsEntry("officeBaseInstructions", "office-base-override")
                .containsEntry("checkpointInstructions", "checkpoint-override")
                .containsEntry("officeInstructions", "new-addition");
        assertThat(reopened.active()).containsEntry("officeBaseInstructions", "office-base-override");
        assertThat(AgentInstructions.section("compactionInstructions", reopened.active()))
                .isEqualTo(AgentInstructions.defaults().get("compactionInstructions"));
        reopened.close();
    }

    @Test
    void brokenInstructionSectionsAreRejectedWithoutTouchingTheSavedRevision() {
        AdminConfigurationStore store = store(directory.resolve("invalid.bin"), validSettings());

        var blank = store.save(1, Map.of("officeBaseInstructions", "   "), "operator");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.code()).isEqualTo("CONFIG_INVALID");
        assertThat(blank.issues()).containsExactly("officeBaseInstructions");

        var wrongType = store.save(1, Map.of("sharedInstructions", 42), "operator");
        assertThat(wrongType.status()).isEqualTo(400);
        assertThat(wrongType.issues()).containsExactly("sharedInstructions");

        var tooLong = store.save(1, Map.of("replyInstructions", "x".repeat(AgentInstructions.MAX_LENGTH + 1)),
                "operator");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.issues()).containsExactly("replyInstructions");

        var mixed = store.save(1, Map.of("companyBaseInstructions", "kept-part", "finalizationInstructions", ""),
                "operator");
        assertThat(mixed.status()).isEqualTo(400);
        assertThat(mixed.issues()).containsExactly("finalizationInstructions");

        assertThat(store.view(DEPLOYMENT).revision()).isEqualTo(1);
        assertThat(store.saved()).containsEntry("officeBaseInstructions",
                AgentInstructions.defaults().get("officeBaseInstructions"));
        assertThat(store.changes()).hasSize(1);
        assertThat(store.save(1, Map.of("companyBaseInstructions", "accepted"), "operator").status()).isEqualTo(200);
        assertThat(store.saved()).containsEntry("companyBaseInstructions", "accepted");
        store.close();
    }


    private AdminConfigurationStore store(Path file, Map<String, Object> initial) {
        return AdminConfigurationFactory.encryptedFile(new ObjectMapper(), file, new byte[32], () -> initial);
    }

    private static Map<String, Object> seededFromCatalog(String apiKey) {
        Map<String, Object> initial = new LinkedHashMap<>();
        for (AdminSettingField field : AdminSettingCatalog.fields()) {
            if (field.defaultValue() != null) initial.put(field.name(), field.defaultValue());
        }
        initial.put("modelBaseUrl", "https://model.example.test/v1");
        initial.put("modelId", "test-model");
        initial.put("modelApiKey", apiKey);
        initial.put("draftTtlMs", 600000);
        initial.put("readbackWaitMs", 6000);
        initial.put("controlTimeoutMs", 25000);
        return initial;
    }

    /** Legacy-shaped settings: only the three additions exist, no base sections. */
    private static Map<String, Object> validSettings() {
        Map<String, Object> settings = new LinkedHashMap<>(seededFromCatalog("secret"));
        settings.put("officeInstructions", "");
        settings.put("companyInstructions", "");
        settings.put("replyInstructions", "");
        settings.remove("officeBaseInstructions");
        settings.remove("companyBaseInstructions");
        settings.remove("sharedInstructions");
        settings.remove("companyTaskInstructions");
        settings.remove("checkpointInstructions");
        settings.remove("finalizationInstructions");
        settings.remove("compactionInstructions");
        return settings;
    }
}
