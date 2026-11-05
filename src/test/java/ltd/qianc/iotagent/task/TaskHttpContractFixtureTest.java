package ltd.qianc.iotagent.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ltd.qianc.iotagent.IotAgentBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Real HTTP validation/authentication boundaries; business behavior is covered through the real task facade. */
@SpringBootTest(classes = IotAgentBackendApplication.class,
        properties = {"iot.identity.verify=false", "agent.bridge.token=test-bridge"})
@AutoConfigureMockMvc
class TaskHttpContractFixtureTest {
    private static final String AUTH = "Bearer task-fixture";
    private static final String TENANT = "TASK-TENANT";

    @Autowired private MockMvc http;
    @Test
    void rejectsOversizedCalendarWindowsAndMissingIdentity() throws Exception {
        http.perform(get("/api/agent/devices/device-1/calendar").header("Authorization", AUTH).header("Tenantcode", TENANT).queryParam("from", "2026-10-30").queryParam("to", "2027-01-01"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        http.perform(get("/api/agent/tasks")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void versionStrictnessNeverTruncatesOrDefaultsExplicitValues() throws Exception {
        for (String body : new String[] {
                "{\"version\":1.9}",
                "{\"version\":1.0}",
                "{\"version\":\"2\"}",
                "{\"version\":null}",
                "{\"version\":0}",
                "{}"}) {
            http.perform(post("/api/agent/tasks/missing-task/cancel")
                            .header("Authorization", AUTH).header("Tenantcode", TENANT)
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_VERSION"));
        }
        // 可缺省 version 的历史默认只用于真正缺失；显式非法值同样 400。
        http.perform(post("/api/agent/task-changes/missing-change/confirm")
                        .header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"version\":1.9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION"));
    }

    @Test
    void nonStringSourceIsAParameterErrorNotASilentOfficeDefault() throws Exception {
        http.perform(post("/api/agent/task-proposals")
                        .header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"source\":5,\"payload\":{\"label\":\"灯\",\"phases\":[]}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_SOURCE"));
    }
}
