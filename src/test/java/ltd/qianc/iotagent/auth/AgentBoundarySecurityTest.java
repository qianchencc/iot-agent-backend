package ltd.qianc.iotagent.auth;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"agent.bridge-token=test-bridge", "iot.identity.verify=false"})
@AutoConfigureMockMvc
@Import(AgentBoundarySecurityTest.ProbeController.class)
class AgentBoundarySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAgentRequestWithoutIdentity() throws Exception {
        mockMvc.perform(get("/api/agent/test-boundary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void exposesTenantAndAuthorizationOnlyInsideAuthenticatedAgentRequest() throws Exception {
        mockMvc.perform(get("/api/agent/test-boundary")
                        .header("Authorization", "Bearer boundary-user")
                        .header("Tenantcode", "XSJ")
                        .queryParam("source", "company"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("XSJ"))
                .andExpect(jsonPath("$.authorization").value("Bearer boundary-user"))
                .andExpect(jsonPath("$.source").value("company"));
    }

    @Test
    void rejectsUnknownAgentSource() throws Exception {
        mockMvc.perform(get("/api/agent/test-boundary?source=unknown")
                        .header("Authorization", "Bearer boundary-user")
                        .header("Tenantcode", "XSJ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_SOURCE"));
    }

    @Test
    void protectsInternalBridgeWithIndependentToken() throws Exception {
        mockMvc.perform(get("/internal/agent/test-boundary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BRIDGE_UNAUTHORIZED"));

        mockMvc.perform(get("/internal/agent/test-boundary")
                        .header("X-Agent-Bridge-Token", "test-bridge"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bridge").value("ok"));
    }

    @Test
    void stablePrincipalOwnerSurvivesTokenRotation() {
        PrincipalInfo first = new PrincipalInfo("Bearer token-a", "XSJ", "principal-1", "https://ncim.577119.com");
        PrincipalInfo rotated = new PrincipalInfo("Bearer token-b", "XSJ", "principal-1", "https://ncim.577119.com");
        PrincipalInfo other = new PrincipalInfo("Bearer token-b", "XSJ", "principal-2", "https://ncim.577119.com");

        Assertions.assertEquals(first.ownerKey(), rotated.ownerKey());
        Assertions.assertNotEquals(first.ownerKey(), other.ownerKey());
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/agent/test-boundary")
        Object agentBoundary() {
            PrincipalInfo principal = PrincipalContext.getPrincipalInfo();
            return java.util.Map.of(
                    "authorization", principal.authorization(),
                    "tenantCode", principal.tenantCode(),
                    "source", "company");
        }

        @GetMapping("/internal/agent/test-boundary")
        Object bridgeBoundary() {
            return java.util.Map.of("bridge", "ok");
        }
    }
}
