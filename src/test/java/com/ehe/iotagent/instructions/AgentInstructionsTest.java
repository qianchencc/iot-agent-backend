package com.ehe.iotagent.instructions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Instruction authority behavior: resource defaults, hydration, composition and validation. */
class AgentInstructionsTest {

    @Test
    void validateResolvesMissingBasesButRejectsBrokenExplicitValues() {
        assertThat(AgentInstructions.validate(Map.of())).isEmpty();
        Map<String, Object> legacyOnly = Map.of("officeInstructions", "x", "companyInstructions", "", "replyInstructions", "");
        assertThat(AgentInstructions.validate(legacyOnly)).isEmpty();

        assertThat(AgentInstructions.validate(Map.of("officeBaseInstructions", " ")))
                .containsExactly("officeBaseInstructions");
        assertThat(AgentInstructions.validate(Map.of("checkpointInstructions", 7)))
                .containsExactly("checkpointInstructions");
        assertThat(AgentInstructions.validate(Map.of("compactionInstructions", "x".repeat(AgentInstructions.AUXILIARY_MAX_LENGTH + 1))))
                .containsExactly("compactionInstructions");
        assertThat(AgentInstructions.validate(Map.of("replyInstructions", "x".repeat(AgentInstructions.MAX_LENGTH + 1))))
                .containsExactly("replyInstructions");
        assertThat(AgentInstructions.validate(Map.of("companyInstructions", false)))
                .containsExactly("companyInstructions");
        Map<String, Object> blankAdditions = new HashMap<>();
        blankAdditions.put("officeInstructions", "");
        blankAdditions.put("companyInstructions", "  ");
        assertThat(AgentInstructions.validate(blankAdditions)).isEmpty();
    }

    @Test
    void composeJoinsConfiguredSectionsInRuntimeOrderWithBlankLineSeparators() {
        Map<String, Object> settings = configured("O-BASE", "C-BASE", "SHARED", "CT", "CK", "FIN", "COMP");
        settings.put("officeInstructions", "O-ADD");
        settings.put("companyInstructions", "C-ADD");
        settings.put("replyInstructions", "REPLY");

        assertThat(AgentInstructions.compose("office", settings))
                .isEqualTo("O-BASE\n\nO-ADD\n\nREPLY\n\nSHARED\n\n");
        assertThat(AgentInstructions.compose("company", settings))
                .isEqualTo("C-BASE\n\nCT\n\nC-ADD\n\nREPLY\n\nSHARED\n\n");

        settings.put("officeInstructions", "");
        settings.put("companyInstructions", " ");
        settings.put("replyInstructions", "");
        assertThat(AgentInstructions.compose("office", settings)).isEqualTo("O-BASE\n\nSHARED\n\n");
        assertThat(AgentInstructions.compose("company", settings)).isEqualTo("C-BASE\n\nCT\n\nSHARED\n\n");

        assertThatThrownBy(() -> AgentInstructions.compose("unknown", settings))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sectionsThatFitIndividuallyCannotSaveAnUnrunnableCombinedPrompt() {
        Map<String, Object> settings = configured("O", "C", "S", "T", "K", "F", "P");
        settings.put("officeBaseInstructions", "x".repeat(AgentInstructions.MAX_LENGTH - 5));
        assertThat(AgentInstructions.validate(settings)).isEmpty();
        assertThat(AgentInstructions.compose("office", settings)).hasSize(AgentInstructions.MAX_LENGTH);
        settings.put("replyInstructions", "R");
        assertThat(AgentInstructions.validate(settings)).containsExactly("officeBaseInstructions");
        assertThatThrownBy(() -> AgentInstructions.compose("office", settings))
                .isInstanceOf(IllegalArgumentException.class);
        settings.put("officeBaseInstructions", "O");
        settings.put("companyTaskInstructions", "x".repeat(AgentInstructions.MAX_LENGTH - 11));
        assertThat(AgentInstructions.validate(settings)).isEmpty();
        settings.put("companyInstructions", "A");
        assertThat(AgentInstructions.validate(settings)).containsExactly("companyBaseInstructions");
        settings.put("finalizationInstructions", "x".repeat(AgentInstructions.AUXILIARY_MAX_LENGTH + 1));
        assertThat(AgentInstructions.validate(settings)).contains("finalizationInstructions");
    }

    @Test
    void invalidExplicitInstructionsNeverSilentlyFallBackToDifferentText() {
        assertThatThrownBy(() -> AgentInstructions.section("compactionInstructions",
                Map.of("compactionInstructions", " "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentInstructions.section("officeBaseInstructions",
                Map.of("officeBaseInstructions", 42))).isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("sharedInstructions", null);
        assertThat(AgentInstructions.validate(nullValue)).containsExactly("sharedInstructions");
        assertThat(AgentInstructions.withDefaults(nullValue)).containsEntry("sharedInstructions", null);
        assertThatThrownBy(() -> AgentInstructions.section("sharedInstructions", nullValue))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentInstructions.section("officeInstructions", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentInstructions.section("nope", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }


    private static Map<String, Object> configured(String office, String company, String shared, String companyTasks,
            String checkpoint, String finalization, String compaction) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("officeBaseInstructions", office);
        settings.put("companyBaseInstructions", company);
        settings.put("sharedInstructions", shared);
        settings.put("companyTaskInstructions", companyTasks);
        settings.put("checkpointInstructions", checkpoint);
        settings.put("finalizationInstructions", finalization);
        settings.put("compactionInstructions", compaction);
        return settings;
    }

}
