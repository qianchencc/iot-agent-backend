package com.ehe.iotagent.iot;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ehe.iotagent.auth.AccountPrincipalVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"iot.identity.verify=true", "agent.storage.mysql.enabled=false", "agent.heartbeat-ms=0",
        "spring.main.allow-bean-definition-overriding=true"})
@AutoConfigureMockMvc
@Import(SafetyHistoryHttpContractTest.Fixtures.class)
class SafetyHistoryHttpContractTest {
    @Autowired MockMvc http;
    @Autowired SafetyHistoryBehaviorTest.Fixture fixture;
    @Autowired ObjectMapper mapper;

    @Test void preparingDoesNotBecomeAnEmptyLedgerAndTheGlobalBoundsSurviveAnEmptyDateFilter() throws Exception {
        fixture.register(fixture.identity());
        fixture.clock.now = fixture.clock.now.plusNanos(123456789);
        assertEquals("preparing", query("gas").path("status").asText());
        for (int index = 0; index < 41; index++)
            fixture.rows.add(SafetyHistoryBehaviorTest.row("gas", "gas", "fixture-gas-" + index, "2020-01-01T23:59:59Z"));
        fixture.rows.add(SafetyHistoryBehaviorTest.row("smoke", "smoke", "fixture-smoke", "2019-01-01T00:00:00Z"));
        fixture.publish();
        var dashboard = mapper.readTree(http.perform(get("/api/agent/dashboard").param("source", "company").param("section", "summary")
                .header("Authorization", "Bearer fixture-safety").header("Tenantcode", "fixture-tenant"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(dashboard.has("environment"), "the domain ledger must not replace the shared observation router");
        assertEquals(42, dashboard.path("alerts").path("total").asInt(), "summary totals must include all authorized types, not just the first ledger page");
        for (JsonNode failure : dashboard.path("errors"))
            assertNotEquals("alerts", failure.path("area").asText());
        int calls = fixture.calls.get();
        fixture.offline = true;
        var emptyWindow = mapper.readTree(http.perform(get("/api/agent/safety-events").param("source", "company").param("type", "gas")
                .param("from", "2021-01-01T00:00:00+08:00").param("to", "2021-01-02T23:59:59.999+08:00")
                .header("Authorization", "Bearer fixture-safety").header("Tenantcode", "fixture-tenant"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(emptyWindow.path("items").isEmpty());
        assertEquals("2020-01-01T23:59:59Z", emptyWindow.path("availableWindow").path("from").asText());
        assertTrue(query("water").path("availableWindow").path("from").isNull());
        assertEquals(calls, fixture.calls.get());
    }
    private JsonNode query(String type) throws Exception {
        return mapper.readTree(http.perform(get("/api/agent/safety-events").param("source", "company").param("type", type)
                .header("Authorization", "Bearer fixture-safety").header("Tenantcode", "fixture-tenant"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @TestConfiguration static class Fixtures {
        @Bean(destroyMethod = "close") SafetyHistoryBehaviorTest.Fixture safetyFixture() { return new SafetyHistoryBehaviorTest.Fixture(); }
        @Bean @Primary AccountPrincipalVerifier safetyIdentity() { return presented -> SafetyHistoryBehaviorTest.Fixture.principal(presented.authorization(), presented.tenantCode()); }
        @Bean SafetyHistory localSafetyHistory(SafetyHistoryBehaviorTest.Fixture fixture) { return fixture.directory.safetyHistory(); }
        @Bean EnergyHistory localEnergyHistory(SafetyHistoryBehaviorTest.Fixture fixture) { return fixture.directory.energyHistory(); }
        @Bean EnvironmentHistory localEnvironmentHistory(SafetyHistoryBehaviorTest.Fixture fixture) { return fixture.directory.environmentHistory(); }
        @Bean @Primary IotAdapter localIot(SafetyHistoryBehaviorTest.Fixture fixture) {
            return new IotAdapter() {
                @Override public reactor.core.publisher.Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                    return reactor.core.publisher.Mono.fromCallable(() -> fixture.directory.loadScope(identity));
                }
            };
        }
    }
}
