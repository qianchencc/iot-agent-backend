package com.ehe.iotagent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ehe.iotagent.IotAgentBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consumer-visible independent administrator session and mutation protection. */
@SpringBootTest(classes = IotAgentBackendApplication.class, properties = {
        "agent.storage.mysql.enabled=false", "agent.storage.redis.enabled=false",
        "admin.password=console-contract-password", "admin.password-minimum=6",
        "admin.origin=http://127.0.0.1:5185"
})
@AutoConfigureMockMvc
class AdminAuthenticationHttpTest {
    private static final String ORIGIN = "http://127.0.0.1:5185";
    @Autowired MockMvc http;
    @Autowired ObjectMapper mapper;

    @Test
    void agentBearerDoesNotCreateAnAdministratorSession() throws Exception {
        http.perform(get("/api/admin/session")
                        .header("Authorization", "Bearer business-user")
                        .header("Tenantcode", "TEST"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
        http.perform(post("/api/admin/login").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"business-user\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_LOGIN_FAILED"));
    }

    @Test
    void loginRejectsForeignOriginAndSessionSurvivesReadUntilLogout() throws Exception {
        http.perform(post("/api/admin/login").header("Origin", "http://untrusted.example")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"console-contract-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_ORIGIN_REJECTED"));
        Session session = login();
        http.perform(get("/api/admin/session").header("Cookie", session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csrfToken").value(session.csrf()));
        http.perform(post("/api/admin/logout").header("Cookie", session.cookie())
                        .header("Origin", ORIGIN).header("X-Admin-CSRF", session.csrf()))
                .andExpect(status().isOk());
        http.perform(get("/api/admin/session").header("Cookie", session.cookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mutationNeedsBothMatchingOriginAndSessionCsrf() throws Exception {
        Session session = login();
        http.perform(post("/api/admin/logout").header("Cookie", session.cookie()).header("Origin", ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_CSRF_REJECTED"));
        http.perform(post("/api/admin/logout").header("Cookie", session.cookie())
                        .header("Origin", "http://untrusted.example").header("X-Admin-CSRF", session.csrf()))
                .andExpect(status().isForbidden());
        http.perform(get("/api/admin/session").header("Cookie", session.cookie()))
                .andExpect(status().isOk());
    }

    @Test
    void administratorCannotRewriteTheDeploymentPasswordFromTheWeb() throws Exception {
        Session session = login();
        http.perform(post("/api/admin/password").header("Cookie", session.cookie())
                        .header("Origin", ORIGIN).header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"console-contract-password\",\"newPassword\":\"replaced-password\",\"confirmPassword\":\"replaced-password\"}"))
                .andExpect(status().isNotFound());
        http.perform(get("/api/admin/session").header("Cookie", session.cookie()))
                .andExpect(status().isOk());
        login();
    }

    @Test
    void configurationRevisionRejectsFractionalAndOverflowingConcurrencyTokens() throws Exception {
        Session session = login();
        for (String revision : new String[]{"1.9", "9223372036854775808"}) {
            http.perform(patch("/api/admin/config")
                            .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                            .header("X-Admin-CSRF", session.csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedRevision\":" + revision + ",\"changes\":{}}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CONFIG_INVALID"));
        }
    }

    private Session login() throws Exception {
        MvcResult result = http.perform(post("/api/admin/login").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"console-contract-password\"}"))
                .andExpect(status().isOk()).andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("HttpOnly", "SameSite=Strict", "Path=/api/admin");
        String csrf = mapper.readTree(result.getResponse().getContentAsString()).path("csrfToken").asText();
        return new Session(cookie.split(";", 2)[0], csrf);
    }

    private record Session(String cookie, String csrf) { }
}
