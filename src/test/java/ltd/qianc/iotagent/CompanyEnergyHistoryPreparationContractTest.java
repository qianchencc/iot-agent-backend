package ltd.qianc.iotagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.CompanyEnergyObservationComposerFactory;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationUnavailableException;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
    void dailyReadsKeepUnvisitedHistoryOnDemandAndRecoverAfterUpstreamReturns() throws Exception {
        var visible = read(query("3", null));
        assertDailyUsage(visible, 3);

        YearMonth unvisited = YearMonth.from(fixture.today).minusMonths(6);
        fixture.upstreamUnavailable.set(true);
        var unavailable = read(query(Integer.toString(unvisited.lengthOfMonth()), unvisited.atEndOfMonth().toString()));
        assertThat(unavailable.path("status").asText()).isEqualTo("unavailable");
        assertThat(unavailable.has("days")).isFalse();

        fixture.snapshotClock.advance(Duration.ofSeconds(61));
        fixture.upstreamUnavailable.set(false);
        var recovered = read(query(Integer.toString(unvisited.lengthOfMonth()), unvisited.atEndOfMonth().toString()));
        assertDailyUsage(recovered, unvisited.lengthOfMonth());
    }

    private com.fasterxml.jackson.databind.JsonNode read(MockHttpServletRequestBuilder query) throws Exception {
        var response = http.perform(query.header("Authorization", "Bearer fixture").header("Tenantcode", "FIXTURE"))
                .andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString());
    }

    private static MockHttpServletRequestBuilder query(String days, String end) {
        var query = get("/api/agent/company-energy-daily").param("source", "company").param("days", days);
        if (end != null) query.param("end", end);
        return query;
    }

    private static void assertDailyUsage(com.fasterxml.jackson.databind.JsonNode body, int days) {
        assertThat(body.path("days").size()).isEqualTo(days);
        body.path("days").forEach(day -> {
            assertThat(day.path("usage").asDouble()).isEqualTo(10.0d);
            assertThat(day.path("complete").asBoolean()).isTrue();
        });
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
        final MutableClock snapshotClock = new MutableClock(now, shanghai);
        final List<Map<String, Object>> rows = new ArrayList<>();
        final AtomicBoolean upstreamUnavailable = new AtomicBoolean();
        final ReadSnapshots snapshots = ReadSnapshots.create(snapshotClock, Runnable::run, Runnable::run,
                null, null, new ObjectMapper(), ReadSnapshots.Settings.defaults());
        final IotAdapter iot = identity -> Mono.just(new AuthorizedDeviceScope() {
            @Override public ltd.qianc.iotagent.iot.RequestIdentity identity() { return identity; }
            @Override public IotSourceEnum source() { return identity.source(); }
            @Override public List<AuthorizedDevice> devices() {
                return List.of(new AuthorizedDevice("meter-1", "energy", "电表", "26楼", true));
            }
        });
        final ObservationAdapter raw = request -> upstreamUnavailable.get()
                ? Mono.error(new ObservationUnavailableException("上游暂不可用"))
                : Mono.just(ObservationResponse.available(Map.of("content", rows, "last", true)));

        Fixture() {
            for (LocalDate day = today.plusDays(1); !day.isBefore(today.minusMonths(8)); day = day.minusDays(1)) {
                rows.add(Map.of("id", "reading-" + day, "equipmentId", "meter-1",
                        "readTime", day + " 08:00:00", "epf", day.toEpochDay() * 10d));
            }
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;
        MutableClock(Instant now, ZoneId zone) { this.now = now; this.zone = zone; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId target) { return new MutableClock(now, target); }
        @Override public Instant instant() { return now; }
    }
}
