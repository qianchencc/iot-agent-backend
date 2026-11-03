package ltd.qianc.iotagent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cross-origin behaviour of the single browser-readable video route. */
@SpringBootTest(properties = {
        "agent.bridge-token=test-bridge",
        "iot.identity.verify=false",
        "agent.video.allowed-origin=http://127.0.0.1:9419"
})
@AutoConfigureMockMvc
@Import(VideoStreamCorsTest.ProbeController.class)
class VideoStreamCorsTest {

    private static final String VIDEO_STREAM = "/api/agent/devices/camera-1/video-stream";
    private static final String ORIGIN = "http://127.0.0.1:9419";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightForVideoStreamSucceedsWithoutCredentials() throws Exception {
        mockMvc.perform(options(VIDEO_STREAM)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "authorization,tenantcode,cache-control,pragma")
                        .queryParam("source", "office")
                        .queryParam("mode", "live"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("GET")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        allOf(containsString("authorization"), containsString("tenantcode"),
                                containsString("cache-control"), containsString("pragma"))))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void actualVideoFetchStillRequiresIdentityAndExposesTheFailure() throws Exception {
        mockMvc.perform(get(VIDEO_STREAM)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .queryParam("source", "office")
                        .queryParam("mode", "live"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void otherAgentRoutesStayOutsideTheCorsPolicy() throws Exception {
        mockMvc.perform(options("/api/agent/test-boundary")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void preflightFromAnotherOriginIsRejected() throws Exception {
        mockMvc.perform(options(VIDEO_STREAM)
                        .header(HttpHeaders.ORIGIN, "http://127.0.0.1:9999")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,tenantcode"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightRejectsMethodsAndHeadersOutsideTheVideoContract() throws Exception {
        mockMvc.perform(options(VIDEO_STREAM)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

        mockMvc.perform(options(VIDEO_STREAM)
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-arbitrary"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void onlyTheExactVideoStreamPathIsMatched() throws Exception {
        mockMvc.perform(options(VIDEO_STREAM + "/extra")
                        .header(HttpHeaders.ORIGIN, ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void unsafeOriginsPreventApplicationStartup() {
        for (String origin : java.util.List.of("*", "http://127.0.0.1:9419/player",
                "http://user@127.0.0.1:9419", "file:///tmp/player")) {
            new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
                    .withUserConfiguration(ltd.qianc.iotagent.IotAgentBackendApplication.class)
                    .withPropertyValues("agent.video.allowed-origin=" + origin,
                            "agent.storage.mysql.enabled=false", "agent.storage.redis.enabled=false")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    });
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/agent/test-boundary")
        Object testBoundary() {
            return java.util.Map.of("ok", true);
        }
    }
}
