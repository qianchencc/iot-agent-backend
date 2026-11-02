package ltd.qianc.iotagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.CompanyEnergyObservationComposerFactory;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;

@SpringBootTest(properties = {
        "iot.identity.verify=false", "agent.storage.mysql.enabled=false", "agent.heartbeat-ms=0",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@Import(CompanyEnergyHistoryPreparationContractTest.Fixtures.class)
class CompanyEnergyHistoryPreparationContractTest {
    @Autowired MockMvc http;
    @Autowired Fixture fixture;
    @Autowired ObjectMapper mapper;

    @Test
    void enteringEnergyPreparesUnvisitedMonthsWithoutBlockingTheRequest() throws Exception {
        http.perform(get("/api/agent/company-energy-daily").param("source", "company").param("days", "3")
                        .header("Authorization", "Bearer fixture").header("Tenantcode", "FIXTURE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("preparing"));
        assertThat(fixture.calls.get()).isZero();
        while (!fixture.background.isEmpty()) fixture.background.remove(0).run();
        int preparedCalls = fixture.calls.get();
        fixture.upstreamUnavailable.set(true);

        YearMonth earlier = YearMonth.from(fixture.today).minusMonths(9);
        var response = http.perform(get("/api/agent/company-energy-daily").param("source", "company")
                        .param("days", Integer.toString(earlier.lengthOfMonth()))
                        .param("end", earlier.atEndOfMonth().toString())
                        .header("Authorization", "Bearer fixture").header("Tenantcode", "FIXTURE"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var history = mapper.readTree(response.getContentAsString());
        assertThat(history.path("days").size()).isEqualTo(earlier.lengthOfMonth());
        history.path("days").forEach(day -> {
            assertThat(day.path("usage").asDouble()).isEqualTo(10.0d);
            assertThat(day.path("complete").asBoolean()).isTrue();
        });
        assertThat(fixture.calls.get()).isEqualTo(preparedCalls);
    }

    @TestConfiguration
    static class Fixtures {
        @Bean Fixture energyHistoryFixture() { return new Fixture(); }
        @Bean @Primary ReadSnapshots energyHistorySnapshots(Fixture fixture) { return fixture.snapshots; }
        @Bean @Primary IotAdapter energyHistoryIot(Fixture fixture) { return fixture.iot; }
        @Bean @Primary ObservationAdapter energyHistoryAdapter(Fixture fixture) {
            var composer = CompanyEnergyObservationComposerFactory.composed(
                    fixture.raw, fixture.iot, fixture.clock, fixture.snapshots);
            return composer::read;
        }
    }

    static final class Fixture {
        final Instant now = Instant.now();
        final ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        final LocalDate today = now.atZone(shanghai).toLocalDate();
        final Clock clock = Clock.fixed(now, shanghai);
        final List<Map<String, Object>> rows = new ArrayList<>();
        final AtomicBoolean upstreamUnavailable = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        final List<Runnable> background = new ArrayList<>();
        final ReadSnapshots snapshots = ReadSnapshots.create(clock, background::add, Runnable::run,
                null, null, new ObjectMapper(), ReadSnapshots.Settings.defaults());
        final IotAdapter iot = identity -> Mono.just(new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return identity; }
            @Override public IotSource source() { return identity.source(); }
            @Override public List<AuthorizedDevice> devices() {
                return List.of(new AuthorizedDevice("meter-1", "energy", "电表", "26楼", true));
            }
        });
        final ObservationAdapter raw = request -> {
            calls.incrementAndGet();
            if (upstreamUnavailable.get()) return Mono.error(new ObservationUnavailableException("上游已不可用"));
            int page = Integer.parseInt(request.parameters().getOrDefault("page", "0"));
            int from = Math.min(rows.size(), page * 500), to = Math.min(rows.size(), (page + 1) * 500);
            return Mono.just(ObservationResponse.available(Map.of("content", rows.subList(from, to),
                    "last", to == rows.size(), "totalElements", rows.size())));
        };

        Fixture() {
            for (LocalDate day = today.plusDays(1); day.isAfter(today.minusMonths(30)); day = day.minusDays(1)) {
                rows.add(Map.of("id", "reading-" + day, "equipmentId", "meter-1",
                        "readTime", day + " 08:00:00", "epf", day.toEpochDay() * 10d));
            }
        }
    }
}
