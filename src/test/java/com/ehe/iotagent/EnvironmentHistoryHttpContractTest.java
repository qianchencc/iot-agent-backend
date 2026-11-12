package com.ehe.iotagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import com.ehe.iotagent.auth.AccountPrincipalVerifier;
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.iot.EnvironmentHistoryFixture;
import com.ehe.iotagent.iot.ObservationAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "iot.identity.verify=true", "agent.storage.mysql.enabled=false", "agent.heartbeat-ms=0",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@Import(EnvironmentHistoryHttpContractTest.Fixtures.class)
class EnvironmentHistoryHttpContractTest {
    @Autowired MockMvc http;
    @Autowired EnvironmentHistoryFixture fixture;
    @Autowired ObjectMapper mapper;

    @Test
    void theSelectedWindowIncludesEarlySamplesAndPeaksWithoutManualPaging() throws Exception {
        fixture.publish();
        fixture.unavailable = true;
        int collected = fixture.sourceCalls.get();
        JsonNode window = read(query());
        assertThat(window.path("samples").size()).isEqualTo(165);
        assertThat(window.path("samples").get(0).path("recordedAt").asText()).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(window.path("samples").get(164).path("recordedAt").asText()).isEqualTo("2020-01-07T20:00:00Z");
        assertThat(window.path("coverage").path("complete").asText()).isEqualTo("true");
        assertThat(window.path("nextPage").isNull()).isTrue();
        assertThat(window.path("metrics").get(0).path("peak").path("value").asInt()).isEqualTo(35);
        assertThat(window.path("metrics").get(0).path("peak").path("at").asText()).isEqualTo("2020-01-01T00:00:00Z");

        JsonNode trend = read(query().param("resolution", "auto"));
        assertThat(trend.path("resolution").asText()).isEqualTo("3h");
        assertThat(trend.path("samples").size()).isZero();
        assertThat(trend.path("buckets").size()).isLessThanOrEqualTo(64);
        assertThat(trend.path("coverage").path("readSamples").asInt()).isEqualTo(165);
        assertThat(trend.path("metrics").get(0).path("peak")).isEqualTo(window.path("metrics").get(0).path("peak"));
        http.perform(query().param("resolution", "auto").param("to", "2021-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());

        JsonNode first = read(query().param("page", "0"));
        assertThat(first.path("samples").size()).isEqualTo(100);
        assertThat(first.path("coverage").path("complete").asText()).isEqualTo("false");
        assertThat(first.path("nextPage").asInt()).isEqualTo(1);
        assertThat(first.path("metrics").get(0).path("peak").path("value").asInt()).isEqualTo(20);
        JsonNode last = read(query().param("page", "1"));
        assertThat(last.path("samples").size()).isEqualTo(65);
        assertThat(last.path("samples").get(0).path("recordedAt").asText()).isEqualTo("2020-01-01T00:00:00Z");
        assertThat(last.path("nextPage").isNull()).isTrue();
        assertThat(fixture.sourceCalls.get()).isEqualTo(collected);
    }

    private MockHttpServletRequestBuilder query() {
        return get("/api/agent/environment-history").param("source", "company")
                .param("from", "2020-01-01T00:00:00Z").param("to", "2020-01-08T00:00:00Z")
                .header("Authorization", "Bearer fixture-environment").header("Tenantcode", "fixture-tenant");
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(http.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @TestConfiguration
    static class Fixtures {
        @Bean(destroyMethod = "close") EnvironmentHistoryFixture environmentFixture() {
            var fixture = new EnvironmentHistoryFixture();
            var rows = new ArrayList<Map<String, Object>>();
            Instant first = Instant.parse("2020-01-01T00:00:00Z");
            for (int i = 0; i < 165; i++) rows.add(EnvironmentHistoryFixture.row(
                    "fixture-sample-" + i, "fixture-env-code", first.plusSeconds(i * 3600L).toString(), i == 0 ? "35" : "20"));
            fixture.rows = rows;
            return fixture;
        }
        @Bean @Primary AccountPrincipalVerifier environmentIdentity() {
            return presented -> new PrincipalInfo(presented.authorization(), presented.tenantCode(), "fixture-principal", "fixture-realm");
        }
        @Bean @Primary ObservationAdapter environmentAdapter(EnvironmentHistoryFixture fixture) {
            return fixture.composed();
        }
    }
}
