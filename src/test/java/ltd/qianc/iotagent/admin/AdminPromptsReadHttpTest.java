package ltd.qianc.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;


/**
 * Consumer-visible prompt read path: the console reads the actually composed
 * run instructions and edits the ten sections through the existing config API.
 */
@SpringBootTest(properties = {
        "agent.storage.mysql.enabled=false", "agent.storage.redis.enabled=false",
        "admin.password=prompts-contract-password", "admin.origin=http://127.0.0.1:5185"
})
@AutoConfigureMockMvc
class AdminPromptsReadHttpTest {
    private static final String ORIGIN = "http://127.0.0.1:5185";
    private static final Path DIRECTORY;

    static {
        try {
            DIRECTORY = Files.createTempDirectory("admin-prompts-");
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;
    @Autowired AdminConfigurationStore settings;

    @AfterAll
    static void cleanup() throws Exception {
        Files.deleteIfExists(DIRECTORY.resolve("config.bin"));
        Files.deleteIfExists(DIRECTORY);
    }


    @Test
    void instructionSectionsAreEditedThroughTheExistingConfigApi() throws Exception {
        Session session = login();
        long before = settings.view(deployment()).revision();
        String changes = mapper.writeValueAsString(Map.of("expectedRevision", before, "changes", Map.of(
                "officeBaseInstructions", "http-office-base",
                "officeInstructions", "http-office-addition",
                "companyBaseInstructions", "http-company-base",
                "companyTaskInstructions", "http-company-tasks",
                "sharedInstructions", "http-shared",
                "checkpointInstructions", "http-checkpoint",
                "finalizationInstructions", "http-finalization",
                "compactionInstructions", "http-compaction")));
        http.perform(patch("/api/admin/config").header("Cookie", session.cookie())
                        .header("Origin", ORIGIN).header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(changes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved.officeBaseInstructions").value("http-office-base"))
                .andExpect(jsonPath("$.active.officeBaseInstructions").value("http-office-base"))
                .andExpect(jsonPath("$.active.officeInstructions").value("http-office-addition"));

        MvcResult composed = http.perform(get("/api/admin/prompts").header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.office").value("http-office-base\n\nhttp-office-addition\n\nhttp-shared\n\n"))
                .andExpect(jsonPath("$.company").value("http-company-base\n\nhttp-company-tasks\n\nhttp-shared\n\n"))
                .andExpect(jsonPath("$.checkpoint").value("http-checkpoint"))
                .andExpect(jsonPath("$.finalization").value("http-finalization"))
                .andExpect(jsonPath("$.compaction").value("http-compaction")).andReturn();
        JsonNode body = mapper.readTree(composed.getResponse().getContentAsString());
        assertThat(body.has("saved")).isFalse();
        assertThat(body.has("modelApiKey")).isFalse();
        assertThat(composed.getResponse().getContentAsString()).doesNotContain("http-model-secret");

        http.perform(patch("/api/admin/config").header("Cookie", session.cookie())
                        .header("Origin", ORIGIN).header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedRevision", before + 1,
                                "changes", Map.of("officeBaseInstructions", " ")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.issues[0]").value("officeBaseInstructions"));
        assertThat(settings.view(deployment()).revision()).isEqualTo(before + 1);
    }

    private static AdminConfigurationStore.DeploymentInfo deployment() {
        return new AdminConfigurationStore.DeploymentInfo("127.0.0.1", 3200, "test", "test", "UTC");
    }

    private Session login() throws Exception {
        MvcResult result = http.perform(post("/api/admin/login").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"prompts-contract-password\"}"))
                .andExpect(status().isOk()).andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie").split(";", 2)[0];
        String csrf = mapper.readTree(result.getResponse().getContentAsString()).path("csrfToken").asText();
        return new Session(cookie, csrf);
    }

    private record Session(String cookie, String csrf) { }

    @TestConfiguration
    static class Settings {
        @Bean @Primary
        AdminConfigurationStore promptSettings() {
            Map<String, Object> initial = new LinkedHashMap<>();
            for (AdminSettingField field : AdminSettingCatalog.fields()) {
                if (field.defaultValue() != null) initial.put(field.name(), field.defaultValue());
            }
            initial.put("modelBaseUrl", "https://model.example.test/v1");
            initial.put("modelId", "test-model");
            initial.put("modelApiKey", "http-model-secret");
            initial.put("draftTtlMs", 600000);
            initial.put("readbackWaitMs", 6000);
            initial.put("controlTimeoutMs", 25000);
            return AdminConfigurationFactory.encryptedFile(new ObjectMapper(), DIRECTORY.resolve("config.bin"), new byte[32], () -> initial);
        }
    }
}
