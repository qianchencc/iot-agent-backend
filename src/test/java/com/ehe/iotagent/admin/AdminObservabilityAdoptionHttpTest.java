package com.ehe.iotagent.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(properties = {
        "agent.storage.mysql.enabled=false", "agent.storage.redis.enabled=false",
        "agent.bridge.base-url=http://sidecar.example.invalid",
        "admin.password=observability-contract-password", "admin.origin=http://127.0.0.1:5185",
        "agent.bridge.token=observability-contract-token"
})
@AutoConfigureMockMvc
class AdminObservabilityAdoptionHttpTest {
    private static final String ORIGIN = "http://127.0.0.1:5185";
    private static final Path DIRECTORY;
    private static volatile String healthBody = "{\"observability\":{\"enabled\":false}}";
    static {
        try {
            DIRECTORY = Files.createTempDirectory("admin-adoption-");
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }


    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;

    @Test
    void newlySavedEnabledSettingsAreNotAdoptedByTheStillDisabledSidecar() throws Exception {
        healthBody = "{\"observability\":{\"enabled\":false}}";
        var login = http.perform(post("/api/admin/login").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"observability-contract-password\"}"))
                .andExpect(status().isOk()).andReturn();
        String cookie = login.getResponse().getHeader("Set-Cookie").split(";", 2)[0];
        String csrf = mapper.readTree(login.getResponse().getContentAsString()).path("csrfToken").asText();
        http.perform(get("/api/admin/config").header("Cookie", cookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.runtime.observability.adopted").value(true));
        String changes = mapper.writeValueAsString(Map.of("expectedRevision", 1, "changes", Map.of(
                "langfuseEnabled", true, "langfuseBaseUrl", "http://127.0.0.1:12345",
                "langfusePublicKey", "public-test", "langfuseSecretKey", "secret-test")));
        http.perform(patch("/api/admin/config").header("Cookie", cookie)
                        .header("Origin", ORIGIN).header("X-Admin-CSRF", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content(changes))
                .andExpect(status().isOk());
        http.perform(get("/api/admin/config").header("Cookie", cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRestart").value(true))
                .andExpect(jsonPath("$.saved.langfuseEnabled").value(true))
                .andExpect(jsonPath("$.active.langfuseEnabled").value(false))
                .andExpect(jsonPath("$.runtime.observability.enabled").value(false))
                .andExpect(jsonPath("$.runtime.observability.adopted").value(false));
    }

    @Test
    void reachableSidecarWithoutAnObservationReportIsNotCalledUnreachableOrAdopted() throws Exception {
        healthBody = "{\"status\":\"ok\"}";
        try {
            var login = http.perform(post("/api/admin/login").header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"observability-contract-password\"}"))
                    .andExpect(status().isOk()).andReturn();
            String cookie = login.getResponse().getHeader("Set-Cookie").split(";", 2)[0];
            http.perform(get("/api/admin/config").header("Cookie", cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runtime.observability.reachable").value(true))
                    .andExpect(jsonPath("$.runtime.observability.adopted").value(false))
                    .andExpect(jsonPath("$.runtime.observability.enabled").isEmpty());
        } finally {
            healthBody = "{\"observability\":{\"enabled\":false}}";
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"observability\":{}}",
            "{\"observability\":{\"enabled\":null}}",
            "{\"observability\":{\"enabled\":\"false\"}}"
    })
    void incompleteOrMistypedReportDoesNotProveDisabledAdoption(String report) throws Exception {
        healthBody = report;
        try {
            var login = http.perform(post("/api/admin/login").header("Origin", ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"observability-contract-password\"}"))
                    .andExpect(status().isOk()).andReturn();
            String cookie = login.getResponse().getHeader("Set-Cookie").split(";", 2)[0];
            http.perform(get("/api/admin/config").header("Cookie", cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.runtime.observability.reachable").value(true))
                    .andExpect(jsonPath("$.runtime.observability.adopted").value(false))
                    .andExpect(jsonPath("$.runtime.observability.enabled").isEmpty());
        } finally {
            healthBody = "{\"observability\":{\"enabled\":false}}";
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        Files.deleteIfExists(DIRECTORY.resolve("config.bin"));
        Files.deleteIfExists(DIRECTORY);
    }

    @TestConfiguration
    static class Settings {
        @Bean @Primary
        org.springframework.web.reactive.function.client.WebClient.Builder sidecarClient() {
            return org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request ->
                    reactor.core.publisher.Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                            .create(org.springframework.http.HttpStatus.OK)
                            .header("Content-Type", "application/json").body(healthBody).build()));
        }
        @Bean @Primary
        AdminConfigurationStore adoptionSettings() {
            Map<String, Object> initial = new LinkedHashMap<>();
            for (AdminSettingField field : AdminSettingCatalog.fields()) {
                if (field.defaultValue() != null) initial.put(field.name(), field.defaultValue());
            }
            initial.put("modelBaseUrl", "https://model.example.test/v1");
            initial.put("modelId", "test-model");
            initial.put("modelApiKey", "test-model-key");
            initial.put("draftTtlMs", 600000);
            initial.put("readbackWaitMs", 6000);
            initial.put("controlTimeoutMs", 25000);
            return AdminConfigurationFactory.encryptedFile(new ObjectMapper(), DIRECTORY.resolve("config.bin"), new byte[32], () -> initial);
        }
    }
}
