package ltd.qianc.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import ltd.qianc.iotagent.allowlist.AllowedPhone;
import ltd.qianc.iotagent.allowlist.PhoneAllowlist;
import ltd.qianc.iotagent.allowlist.PhoneAllowlistView;
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
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
 * 白名单管理端点走既有 Admin 边界：未登录拒绝；列表只返回手机号与备注；
 * add/remove 要求 expectedRevision，冲突返回 409，不改写错误契约。
 */
@SpringBootTest(properties = {
        "agent.bridge.token=test-bridge",
        "iot.identity.verify=false",
        "agent.video.allowed-origin=",
        "admin.config-file=target/test-admin-config-does-not-exist.properties",
        "admin.password=admin-test-password"
})
@AutoConfigureMockMvc
class PhoneAllowlistAdminApiTest {

    private static final String ORIGIN = "http://127.0.0.1:5185";
    private static final RecordingAllowlist ALLOWLIST = new RecordingAllowlist();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void resetAllowlist() {
        ALLOWLIST.reset();
    }

    @Test
    void allowlistEndpointsRequireAdminSession() throws Exception {
        mockMvc.perform(get("/api/admin/allowlist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_UNAUTHORIZED"));
    }


    @Test
    void addValidatesRequiredFieldsAndRevision() throws Exception {
        AdminSession session = login();

        mockMvc.perform(post("/api/admin/allowlist")
                        .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                        .header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOWLIST_INVALID"));

        mockMvc.perform(post("/api/admin/allowlist")
                        .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                        .header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobileNumber\":\"13800138000\",\"expectedRevision\":\"3\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOWLIST_INVALID"));
        assertThat(ALLOWLIST.added).isEmpty();
    }

    @Test
    void overflowingRevisionCannotReachAnAllowlistMutation() throws Exception {
        AdminSession session = login();
        mockMvc.perform(post("/api/admin/allowlist")
                        .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                        .header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobileNumber\":\"13800138000\",\"expectedRevision\":9223372036854775808}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOWLIST_INVALID"));
        assertThat(ALLOWLIST.added).isEmpty();
    }

    @Test
    void addRejectsManualNameAndReportsVersionConflict() throws Exception {
        AdminSession session = login();

        mockMvc.perform(post("/api/admin/allowlist")
                        .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                        .header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobileNumber\":\"13800138000\",\"name\":\"不能手填\",\"expectedRevision\":3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ALLOWLIST_INVALID"));
        assertThat(ALLOWLIST.added).isEmpty();
        mockMvc.perform(post("/api/admin/allowlist")
                        .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                        .header("X-Admin-CSRF", session.csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(3)))
                .andExpect(status().isOk());

        ALLOWLIST.failure = new BusinessException("ALLOWLIST_VERSION_CONFLICT", "白名单已被其他操作修改，请刷新后重试", 409);
        mockMvc.perform(post("/api/admin/allowlist")
                        .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                        .header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOWLIST_VERSION_CONFLICT"));
    }

    @Test
    void removeRequiresCsrfAndMapsIdempotentSuccess() throws Exception {
        AdminSession session = login();

        mockMvc.perform(post("/api/admin/allowlist/remove")
                        .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_CSRF_REJECTED"));

        mockMvc.perform(post("/api/admin/allowlist/remove")
                        .header("Cookie", session.cookie()).header("Origin", ORIGIN)
                        .header("X-Admin-CSRF", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void storageUnavailableSurfacesAllowlistUnavailable() throws Exception {
        AdminSession session = login();
        ALLOWLIST.failure = new BusinessException("ALLOWLIST_UNAVAILABLE", "白名单存储不可用，请稍后重试", 503);

        mockMvc.perform(get("/api/admin/allowlist").header("Cookie", session.cookie()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ALLOWLIST_UNAVAILABLE"));
    }

    private AdminSession login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/login")
                        .header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"admin-test-password\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String cookie = result.getResponse().getHeader("Set-Cookie").split(";")[0];
        String csrf = mapper.readTree(result.getResponse().getContentAsString()).get("csrfToken").asText();
        return new AdminSession(cookie, csrf);
    }

    private static String body(long expectedRevision) {
        return "{\"mobileNumber\":\"13800138000\",\"expectedRevision\":" + expectedRevision + "}";
    }

    private record AdminSession(String cookie, String csrf) {
    }

    private record Call(long expectedRevision, String mobileNumber) {
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        PhoneAllowlist recordingPhoneAllowlist() {
            return ALLOWLIST;
        }
    }

    static final class RecordingAllowlist implements PhoneAllowlist {
        PhoneAllowlistView view = new PhoneAllowlistView(0, List.of());
        BusinessException failure;
        final List<Call> added = new ArrayList<>();
        final List<Call> removed = new ArrayList<>();

        void reset() {
            view = new PhoneAllowlistView(0, List.of());
            failure = null;
            added.clear();
            removed.clear();
        }

        @Override
        public PhoneAllowlistView list() {
            if (failure != null) throw failure;
            return view;
        }

        @Override
        public boolean companyTasksEnabled(ltd.qianc.iotagent.auth.PrincipalInfo principal) {
            return false;
        }

        @Override
        public AllowedPhone add(long expectedRevision, String mobileNumber, String actor) {
            if (failure != null) throw failure;
            added.add(new Call(expectedRevision, mobileNumber));
            return new AllowedPhone(mobileNumber, null, actor, "2026-11-03T08:00:00Z");
        }

        @Override
        public void remove(long expectedRevision, String mobileNumber, String actor) {
            if (failure != null) throw failure;
            removed.add(new Call(expectedRevision, mobileNumber));
        }
    }
}
