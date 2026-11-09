package ltd.qianc.iotagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.iot.CompanyEnergyObservationComposerFactory;
import ltd.qianc.iotagent.iot.EnergyHistoryFixture;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "iot.identity.verify=true", "agent.storage.mysql.enabled=false", "agent.heartbeat-ms=0",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@Import(CompanyEnergyHistoryPreparationContractTest.Fixtures.class)
class CompanyEnergyHistoryPreparationContractTest {
    @Autowired MockMvc http;
    @Autowired EnergyHistoryFixture fixture;
    @Autowired ObjectMapper mapper;

    @Test
    void untouchedHistoricalMonthsBecomeReadableAfterFullSyncAndRemainLocalWhenTheSourceFails() throws Exception {
        LocalDate end = fixture.clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().minusDays(1);
        var request = get("/api/agent/company-energy-daily").param("source","company").param("days","30").param("end",end.minusMonths(6).toString());
        var pending = json(http.perform(request.header("Authorization","Bearer fixture-energy").header("Tenantcode","fixture-tenant")));
        assertThat(pending.path("status").asText()).isEqualTo("preparing");
        assertThat(pending.has("days")).isFalse();
        assertThat(fixture.sourceCalls.get()).isZero();
        fixture.synchronize();
        fixture.unavailable = true;
        int collected = fixture.sourceCalls.get();
        var ready = json(http.perform(get("/api/agent/company-energy-daily").param("source","company").param("days","30").param("end",end.minusMonths(6).toString())
                .header("Authorization","Bearer fixture-energy").header("Tenantcode","fixture-tenant")));
        assertThat(ready.path("days").size()).isEqualTo(30);
        ready.path("days").forEach(day -> {
            assertThat(day.path("usage").asDouble()).isEqualTo(10d);
            assertThat(day.path("complete").asBoolean()).isTrue();
        });
        assertThat(fixture.sourceCalls.get()).isEqualTo(collected);
        var history = json(http.perform(get("/api/agent/energy-history").param("source","company").param("equipmentId",EnergyHistoryFixture.METER.id())
                .header("Authorization","Bearer fixture-energy").header("Tenantcode","fixture-tenant")));
        assertThat(history.path("status").asText()).isEqualTo("partial");
        assertThat(history.path("nextPage").asInt()).isEqualTo(1);
        assertThat(history.path("coverageComplete").asBoolean()).isFalse();
        assertThat(fixture.sourceCalls.get()).isEqualTo(collected);

        fixture.unavailable = false;
        fixture.afterSampleRead = () -> {
            fixture.meters = java.util.List.of(EnergyHistoryFixture.METER,
                    new ltd.qianc.iotagent.iot.AuthorizedDevice("fixture-added", "energy", "新增电表", "测试位置", true));
            fixture.clock.advance(65);
            fixture.synchronize();
        };
        var conflict = http.perform(request.header("Authorization","Bearer fixture-energy").header("Tenantcode","fixture-tenant"))
                .andExpect(status().isConflict()).andReturn().getResponse();
        var changed = mapper.readTree(conflict.getContentAsString());
        assertThat(changed.path("code").asText()).isEqualTo("ENERGY_AUTHORIZATION_CHANGED");
        assertThat(changed.has("days")).isFalse();
        assertThat(changed.has("cache")).isFalse();
    }

    private com.fasterxml.jackson.databind.JsonNode json(org.springframework.test.web.servlet.ResultActions response) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @TestConfiguration
    static class Fixtures {
        @Bean(destroyMethod="close") EnergyHistoryFixture energyHistoryFixture() {
            var fixture = new EnergyHistoryFixture();
            var rows = new ArrayList<Map<String,Object>>();
            LocalDate today = fixture.clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            for(LocalDate day=today; !day.isBefore(today.minusMonths(8)); day=day.minusDays(1))
                rows.add(EnergyHistoryFixture.row(EnergyHistoryFixture.METER.id(),"reading-"+day,day+"T08:00:00+08:00",Long.toString(day.toEpochDay()*10)));
            fixture.rows=rows;
            return fixture;
        }
        @Bean @Primary AccountPrincipalVerifier energyIdentityVerifier() {
            return presented -> new PrincipalInfo(presented.authorization(),presented.tenantCode(),"fixture-principal","fixture-realm");
        }
        @Bean @Primary ObservationAdapter energyHistoryAdapter(EnergyHistoryFixture fixture) {
            return CompanyEnergyObservationComposerFactory.composed(fixture.history(),fixture.clock)::read;
        }
    }
}
