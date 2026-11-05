package ltd.qianc.iotagent.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Draft console admission must not replace the existing upstream identity boundary. */
@SpringBootTest(properties = {
        "agent.bridge.token=test-bridge",
        "iot.identity.verify=true",
        "agent.video.allowed-origin=",
        "agent.storage.mysql.enabled=false",
        "agent.storage.redis.enabled=false"
})
@AutoConfigureMockMvc
@Import(AgentVerifiedIdentityBoundaryTest.ProbeController.class)
class AgentVerifiedIdentityBoundaryTest {
    @Autowired private MockMvc http;
    @MockBean private AccountPrincipalVerifier verifier;

    @ParameterizedTest
    @ValueSource(strings = {"company", "office"})
    void verifiedMembersCanReachBusinessWithoutDraftAdmission(String source) throws Exception {
        when(verifier.verify(any())).thenAnswer(call -> {
            PrincipalInfo presented = call.getArgument(0);
            return new PrincipalInfo(presented.authorization(), presented.tenantCode(),
                    "verified-member", "https://identity.test");
        });
        http.perform(get("/api/agent/verified-identity-probe")
                        .header("Authorization", "Bearer verified-token")
                        .header("Tenantcode", "XSJ")
                        .queryParam("source", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principalId").value("verified-member"));
    }

    @Test
    void rejectedUpstreamIdentityStillCannotReachCompanyBusiness() throws Exception {
        when(verifier.verify(any())).thenThrow(
                new BusinessException("UNAUTHORIZED", "身份凭据无法核验", 401));
        http.perform(get("/api/agent/verified-identity-probe")
                        .header("Authorization", "Bearer rejected-token")
                        .header("Tenantcode", "XSJ")
                        .queryParam("source", "company"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void unavailableUpstreamIdentityStillFailsClosed() throws Exception {
        when(verifier.verify(any())).thenThrow(
                new BusinessException("ACCOUNT_IDENTITY_UNAVAILABLE", "当前账户身份暂不可用", 503));
        http.perform(get("/api/agent/verified-identity-probe")
                        .header("Authorization", "Bearer unavailable-token")
                        .header("Tenantcode", "XSJ")
                        .queryParam("source", "company"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_IDENTITY_UNAVAILABLE"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/agent/verified-identity-probe")
        Object verifiedIdentity() {
            return java.util.Map.of("principalId", PrincipalContext.getPrincipalInfo().principalId());
        }
    }
}
