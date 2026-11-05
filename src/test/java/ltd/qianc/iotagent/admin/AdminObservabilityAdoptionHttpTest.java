package ltd.qianc.iotagent.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "agent.storage.mysql.enabled=false", "agent.storage.redis.enabled=false",
        "admin.password=observability-contract-password", "admin.origin=http://127.0.0.1:5185",
        "agent.bridge.token=observability-contract-token"
})
@AutoConfigureMockMvc
class AdminObservabilityAdoptionHttpTest {
    private static final String ORIGIN = "http://127.0.0.1:5185";
    private static final Path DIRECTORY;
    private static final HttpServer SIDECAR;
    private static volatile String healthBody = "{\"observability\":{\"enabled\":false}}";
    static {
        try {
            DIRECTORY = Files.createTempDirectory("admin-adoption-");
            SIDECAR = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SIDECAR.createContext("/healthz", exchange -> {
                byte[] bytes = healthBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            SIDECAR.start();
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource
    static void sidecarAddress(DynamicPropertyRegistry properties) {
        properties.add("agent.bridge.base-url", () -> "http://127.0.0.1:" + SIDECAR.getAddress().getPort());
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

    @AfterAll
    static void cleanup() throws Exception {
        SIDECAR.stop(0);
        Files.deleteIfExists(DIRECTORY.resolve("config.bin"));
        Files.deleteIfExists(DIRECTORY);
    }

    @TestConfiguration
    static class Settings {
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
            return AdminConfigurationFactory.encryptedFile(new ObjectMapper(), DIRECTORY.resolve("config.bin"),
                    new byte[32], initial, Set.of());
        }
    }
}
