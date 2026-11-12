package com.ehe.iotagent.action;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ehe.iotagent.IotAgentBackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 真实 HTTP 边界上的请求参数回归：revision/changes 的类型错误必须是 400 参数错误，
 * 绝不静默截断或落到 500（契约见 docs/api/java-contract.md）。
 */
@SpringBootTest(classes = IotAgentBackendApplication.class,
        properties = {"iot.identity.verify=false", "agent.bridge.token=test-bridge"})
@AutoConfigureMockMvc
class ActionDraftHttpContractFixtureTest {
    private static final String AUTH = "Bearer action-fixture";
    private static final String TENANT = "ACTION-TENANT";

    @Autowired private MockMvc http;

    @Test
    void revisionMustBeALosslessNonNegativeInteger() throws Exception {
        for (String body : new String[] {
                "{\"revision\":1.9}",
                "{\"revision\":1.0}",
                "{\"revision\":\"2\"}",
                "{\"revision\":null}",
                "{\"revision\":-1}",
                "{\"revision\":4294967296.5}",
                "{}"}) {
            http.perform(patch("/api/agent/action-drafts/missing-draft/selection")
                            .header("Authorization", AUTH).header("Tenantcode", TENANT)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETERS"));
        }
    }

    @Test
    void changesMustBeAnArrayOfObjects() throws Exception {
        http.perform(patch("/api/agent/action-drafts/missing-draft/parameters")
                        .header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"changes\":[{\"actionId\":\"a-1\",\"patch\":{\"temperature\":26}},\"oops\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETERS"));
        // changes 必需：缺失/显式 null/非数组在控制器就是 400，不依赖草案存在性或存储可用性。
        http.perform(patch("/api/agent/action-drafts/missing-draft/parameters")
                        .header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETERS"));
        http.perform(patch("/api/agent/action-drafts/missing-draft/parameters")
                        .header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"changes\":{\"actionId\":\"a-1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETERS"));
    }

    @Test
    void optionalConfirmRevisionRejectsDecimalsStringsAndExplicitNulls() throws Exception {
        for (String body : new String[] {
                "{\"revision\":1.9}",
                "{\"revision\":\"2\"}",
                "{\"revision\":null}"}) {
            http.perform(post("/api/agent/action-drafts/missing-draft/confirm")
                            .header("Authorization", AUTH).header("Tenantcode", TENANT)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETERS"));
        }
    }

    @Test
    void nonStringSelectedActionIdsAreRejectedInsteadOfDropped() throws Exception {
        http.perform(patch("/api/agent/action-drafts/missing-draft/selection")
                        .header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"selectedActionIds\":[\"a-1\",5]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SELECTION"));
        http.perform(patch("/api/agent/action-drafts/missing-draft/selection")
                        .header("Authorization", AUTH).header("Tenantcode", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":0,\"selectedActionIds\":\"a-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SELECTION"));
    }
}
