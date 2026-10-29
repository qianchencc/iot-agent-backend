package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.region.RegionSeenStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class CompanyOfficeObservationAdapterContractTest {
    private static final RequestIdentity COMPANY = new RequestIdentity("Bearer company-a", "TENANT", IotSource.COMPANY);
    private static final RequestIdentity OFFICE = new RequestIdentity("Bearer office-a", "TENANT", IotSource.OFFICE);
    private static final AuthorizedDevice ENV = new AuthorizedDevice("env-1", "environment", "温度", "一号楼", true);
    private static final AuthorizedDevice AC = new AuthorizedDevice("ac-1", "ac", "空调", "一号楼", false);

    @Test
    void dashboardComposesCompanyCatalogWithoutCallingSingleDashboardEndpoint() {
        AtomicReference<ObservationOperation> operation = new AtomicReference<>();
        ObservationAdapter upstream = request -> {
            operation.set(request.operation());
            if (request.operation() == ObservationOperation.ENVIRONMENT_HISTORY) {
                return Mono.just(ObservationResponse.available(Map.of("items", List.of(
                        Map.of("deviceId", "env-1", "reading", Map.of("temp", 26))))));
            }
            throw new AssertionError("unexpected operation " + request.operation());
        };
        ObservationAdapter adapter = ObservationAdapterFactory.composed(
                upstream, iot(COMPANY, List.of(ENV)), identity -> Mono.just(Map.of()), null,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        ObservationResponse response = adapter.read(new ObservationRequest(COMPANY, ObservationOperation.DASHBOARD, Map.of("section", "environment"))).block();
        Map<String, Object> result = response.payload();

        assertThat(result.get("source")).isEqualTo("company");
        assertThat(result.get("alerts")).isNull();
        assertThat(operation.get()).isEqualTo(ObservationOperation.ENVIRONMENT_HISTORY);
        assertThat(result.toString()).doesNotContain("hubDeviceId");
    }

    @Test
    void regionDevicesRejectRegionOutsideCurrentOfficeIdentity() {
        ObservationAdapter upstream = request -> Mono.just(ObservationResponse.available(Map.of(
                "regions", List.of(Map.of("id", "region-a", "name", "一号楼")))));
        ObservationAdapter adapter = ObservationAdapterFactory.composed(
                upstream, iot(OFFICE, List.of(AC)), identity -> Mono.just(Map.of()),
                new MemorySeenStore(), Clock.systemUTC());

        assertThatThrownBy(() -> adapter.read(new ObservationRequest(OFFICE, ObservationOperation.REGION_DEVICES,
                Map.of("regionId", "region-b"))).block())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("区域不存在");
    }

    private static IotAdapter iot(RequestIdentity expected, List<AuthorizedDevice> devices) {
        return new IotAdapter() {
            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                assertThat(identity.source()).isEqualTo(expected.source());
                return Mono.just(new AuthorizedDeviceScope() {
                    @Override public RequestIdentity identity() { return identity; }
                    @Override public IotSource source() { return identity.source(); }
                    @Override public List<AuthorizedDevice> devices() { return devices; }
                });
            }
        };
    }


    private static final class MemorySeenStore implements RegionSeenStore {
        @Override public void markSeen(String tenantCode, String ownerKey, String regionId, Instant at) { }
        @Override public Optional<Instant> seenAt(String tenantCode, String ownerKey, String regionId) { return Optional.empty(); }
    }
}
