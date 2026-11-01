package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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
        AtomicReference<List<AuthorizedDevice>> observed = new AtomicReference<>();
        ObservationAdapter upstream = request -> {
            operation.set(request.operation());
            throw new AssertionError("unexpected operation " + request.operation());
        };
        IotAdapter iot = new IotAdapter() {
            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                assertThat(identity.source()).isEqualTo(COMPANY.source());
                return Mono.just(new AuthorizedDeviceScope() {
                    @Override public RequestIdentity identity() { return COMPANY; }
                    @Override public IotSource source() { return IotSource.COMPANY; }
                    @Override public List<AuthorizedDevice> devices() { return List.of(ENV); }
                });
            }

            @Override
            public Mono<DeviceStateReadResult> readObservationStates(
                    RequestIdentity identity, List<AuthorizedDevice> devices) {
                observed.set(List.copyOf(devices));
                return Mono.just(new DeviceStateReadResult(
                        Map.of("env-1", Map.of("temp", 26, "source", "device")),
                        Instant.now().toString()));
            }
        };
        ObservationAdapter adapter = ObservationAdapterFactory.composed(
                upstream, iot, identity -> Mono.just(Map.of()), null,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        ObservationResponse response = adapter.read(new ObservationRequest(COMPANY, ObservationOperation.DASHBOARD, Map.of("section", "environment"))).block();
        Map<String, Object> result = response.payload();

        assertThat(result.get("source")).isEqualTo("company");
        assertThat(result.get("alerts")).isNull();
        assertThat(operation.get()).isNull();
        assertThat(observed.get()).containsExactly(ENV);
        assertThat(result.toString()).doesNotContain("hubDeviceId");
        Object environment = result.get("environment");
        assertThat(environment.toString()).contains("26");
    }

    @Test
    void regionMembershipUsesExactOfficeIdsForDevicesStatesAndSeen() {
        AtomicReference<List<String>> stateIds = new AtomicReference<>();
        MemorySeenStore seen = new MemorySeenStore();
        AuthorizedDevice member = new AuthorizedDevice("ac-a", "ac", "空调 A", "一号楼", false,
                null, "ac-a", null, null, List.of(), List.of("office-a"), Map.of());
        AuthorizedDevice sameNameDifferentOffice = new AuthorizedDevice("ac-b", "ac", "空调 B", "一号楼", false,
                null, "ac-b", null, null, List.of(), List.of("office-b"), Map.of());
        AuthorizedDevice noMembership = new AuthorizedDevice("ac-c", "ac", "空调 C", "一号楼", false,
                null, "ac-c", null, null, List.of(), List.of(), Map.of());
        ObservationAdapter upstream = request -> Mono.just(ObservationResponse.available(Map.of(
                "regions", List.of(Map.of("id", "office-a", "name", "一号楼")))));
        ObservationAdapter adapter = ObservationAdapterFactory.composed(
                upstream, iot(OFFICE, List.of(member, sameNameDifferentOffice, noMembership), stateIds),
                identity -> Mono.just(Map.of()), seen, Clock.systemUTC());

        Map<String, Object> devices = adapter.read(new ObservationRequest(OFFICE, ObservationOperation.REGION_DEVICES,
                Map.of("regionId", "office-a"))).block().payload();
        List<?> deviceRows = (List<?>) devices.get("devices");
        assertThat(deviceRows).hasSize(1);
        assertThat(((Map<?, ?>) deviceRows.get(0)).get("id")).isEqualTo("ac-a");

        Map<String, Object> states = adapter.read(new ObservationRequest(OFFICE, ObservationOperation.REGION_STATES,
                Map.of("regionId", "office-a"))).block().payload();
        List<?> stateRows = (List<?>) states.get("states");
        assertThat(stateRows).hasSize(1);
        assertThat(((Map<?, ?>) stateRows.get(0)).get("id")).isEqualTo("ac-a");
        assertThat(stateIds.get()).containsExactly("ac-a");

        adapter.read(new ObservationRequest(OFFICE, ObservationOperation.REGION_SEEN,
                Map.of("regionId", "office-a"))).block();
        assertThat(seen.markedRegion).isEqualTo("office-a");
    }

    @Test
    void regionCompositionAlwaysLoadsItsOwnCurrentContextWithoutAnInnerSnapshotGate() {
        AtomicInteger scopeReads = new AtomicInteger();
        AtomicInteger regionReads = new AtomicInteger();
        ObservationAdapter upstream = request -> {
            regionReads.incrementAndGet();
            return Mono.just(ObservationResponse.available(Map.of(
                    "regions", List.of(Map.of("id", "office-a", "name", "一号楼")))));
        };
        IotAdapter iot = new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                scopeReads.incrementAndGet();
                return Mono.just(new AuthorizedDeviceScope() {
                    @Override public RequestIdentity identity() { return identity; }
                    @Override public IotSource source() { return identity.source(); }
                    @Override public List<AuthorizedDevice> devices() { return List.of(AC); }
                });
            }
        };
        ObservationAdapter adapter = ObservationAdapterFactory.composed(
                upstream, iot, identity -> Mono.just(Map.of()), null,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        adapter.read(new ObservationRequest(OFFICE, ObservationOperation.REGION_LIST, Map.of())).block();
        adapter.read(new ObservationRequest(OFFICE, ObservationOperation.REGION_DEVICES,
                Map.of("regionId", "office-a"))).block();

        assertThat(scopeReads).hasValue(2);
        assertThat(regionReads).hasValue(2);
    }

    @Test
    void regionListComputesAttentionFromSeenStoreWithoutFalseSuccess() {
        MemorySeenStore seen = new MemorySeenStore();
        seen.markSeen("TENANT", "office-a", "office-a", Instant.parse("2026-01-01T00:00:00Z"));
        ObservationAdapter upstream = request -> Mono.just(ObservationResponse.available(Map.of(
                "regions", List.of(
                        Map.of("id", "office-a", "name", "已读区域"),
                        Map.of("id", "office-b", "name", "未读区域")))));
        ObservationAdapter adapter = ObservationAdapterFactory.composed(
                upstream, iot(OFFICE, List.of()), identity -> Mono.just(Map.of()),
                seen, Clock.systemUTC());

        List<?> regions = (List<?>) adapter.read(new ObservationRequest(
                OFFICE, ObservationOperation.REGION_LIST, Map.of())).block().payload().get("regions");

        assertThat(((Map<?, ?>) regions.get(0)).get("attention")).isEqualTo(false);
        assertThat(((Map<?, ?>) regions.get(1)).get("attention")).isEqualTo(true);
    }

    @Test
    void regionListOmitsAttentionWhenSeenStoreIsUnavailable() {
        ObservationAdapter upstream = request -> Mono.just(ObservationResponse.available(Map.of(
                "regions", List.of(Map.of("id", "office-a", "name", "区域")))));
        ObservationAdapter adapter = ObservationAdapterFactory.composed(
                upstream, iot(OFFICE, List.of()), identity -> Mono.just(Map.of()),
                null, Clock.systemUTC());

        Map<?, ?> region = (Map<?, ?>) ((List<?>) adapter.read(new ObservationRequest(
                OFFICE, ObservationOperation.REGION_LIST, Map.of())).block().payload().get("regions")).get(0);

        assertThat(region.containsKey("attention")).isFalse();
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

    @Test
    void energyCompositionDoesNotResetOuterSnapshotFreshnessWithAnInnerCache() {
        AtomicInteger compositions = new AtomicInteger();
        CompanyEnergyObservationComposer composer = request -> {
            compositions.incrementAndGet();
            return Mono.just(ObservationResponse.available(Map.of("source", "company", "days", List.of())));
        };
        ObservationAdapter adapter = ObservationAdapterFactory.composed(
                request -> Mono.just(ObservationResponse.available(Map.of())),
                iot(OFFICE, List.of()), identity -> Mono.just(Map.of()), null, composer,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        ObservationRequest energy = new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "30"));

        adapter.read(energy).block();
        adapter.read(energy).block();

        assertThat(compositions).hasValue(2);
    }

    @Test
    void publicCandidateProjectionNeverSerializesControlProviderBindingsOrRawMetadata() throws Exception {
        AuthorizedDevice device = new AuthorizedDevice("device-a", "ac", "空调 A", "一楼", false,
                "control-secret", "office-binding", "hub-secret", "area-secret",
                List.of(List.of("一号楼", "一楼")), List.of("office-secret"),
                Map.of("rawPayload", Map.of("token", "metadata-secret")));
        DeviceCandidateSet candidates = new DeviceCandidateSet("candidate-a",
                new DeviceCandidateQuery("ac", List.of(), "一楼", List.of(), List.of(), null), List.of(device), true, 1);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(candidates.publicView());
        assertThat(json).contains("device-a", "空调 A", "一楼", "candidate-a", "observationOnly");
        assertThat(json).doesNotContain("controlRef", "officeDeviceRef", "hubDeviceId", "smartAreaCode", "officeIds", "metadata",
                "control-secret", "office-binding", "hub-secret", "area-secret", "office-secret", "metadata-secret");
        assertThat(device.controlRef()).isEqualTo("control-secret");
    }

    private static IotAdapter iot(RequestIdentity expected, List<AuthorizedDevice> devices) {
        return iot(expected, devices, null);
    }

    private static IotAdapter iot(
            RequestIdentity expected,
            List<AuthorizedDevice> devices,
            AtomicReference<List<String>> stateIds) {
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

            @Override
            public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> ids) {
                if (stateIds != null) stateIds.set(List.copyOf(ids));
                return Mono.just(new DeviceStateReadResult(Map.of("ac-a", Map.of("power", "on")), Instant.now().toString()));
            }
        };
    }


    private static final class MemorySeenStore implements RegionSeenStore {
        private String markedRegion;
        private Instant seenAt;

        @Override public void markSeen(String tenantCode, String ownerKey, String regionId, Instant at) {
            markedRegion = regionId;
            seenAt = at;
        }
        @Override public Optional<Instant> seenAt(String tenantCode, String ownerKey, String regionId) {
            return "office-a".equals(regionId) && seenAt != null ? Optional.of(seenAt) : Optional.empty();
        }
    }
}
