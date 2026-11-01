package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class SafetyCameraObservationContractTest {
    private static final RequestIdentity COMPANY =
            new RequestIdentity("Bearer company", "TENANT-1", IotSource.COMPANY);
    private static final Instant NOW = Instant.parse("2024-04-20T07:11:01Z");

    @Test
    void filtersCompanyMonitorsByCurrentFloorAndCapsWindowAtNow() {
        ObservationAdapter upstream = event(Map.of(
                "eventId", "alarm-1", "ledger", "gas", "deviceId", "gas-1",
                "deviceName", "recorded gas", "occurredAt", "2024-04-20T07:10:01Z"));
        AuthorizedDevice safety = device("gas-1", "safety", "燃气感应器", "27楼茶水间", null, List.of());
        AuthorizedDevice pantry = device("cam-27", "video", "监控-27楼茶水间", "27楼茶水间", "hub-27", List.of());
        AuthorizedDevice otherFloor = device("cam-28", "video", "大厅监控", "28楼大厅", "hub-28", List.of());
        AuthorizedDevice noHub = device("cam-no-hub", "video", "27楼无授权绑定", "27楼", null, List.of());
        ObservationAdapter adapter = composed(upstream, scope(safety), scope(pantry, otherFloor, noHub));

        Map<?, ?> payload = adapter.read(new ObservationRequest(COMPANY,
                        ObservationOperation.SAFETY_EVENT_CAMERAS,
                        Map.of("id", "alarm-1", "ledger", "gas", "deviceId", "gas-1")))
                .block().payload();
        List<?> candidates = (List<?>) payload.get("candidates");
        assertThat(candidates).withFailMessage("payload=%s", payload).hasSize(1);
        Map<?, ?> candidate = (Map<?, ?>) candidates.get(0);
        assertThat(candidate.get("deviceId")).isEqualTo("cam-27");
        assertThat(candidate.get("beginTime")).isEqualTo("2024-04-20T14:55:01.000+08:00");
        assertThat(candidate.get("endTime")).isEqualTo("2024-04-20T15:11:01.000+08:00");
        assertThat(((Map<?, ?>) candidate.get("safetyContext")).get("eventId")).isEqualTo("alarm-1");
    }

    @Test
    void missingCurrentSafetyMetadataDoesNotInventLocationOrCandidates() {
        ObservationAdapter upstream = event(Map.of(
                "eventId", "alarm-1", "ledger", "gas", "deviceId", "gas-1",
                "deviceName", "recorded gas", "occurredAt", "2024-04-20T07:10:01Z"));
        ObservationAdapter adapter = composed(upstream, scope(),
                scope(device("cam-27", "video", "27楼监控", "27楼", "hub", List.of())));

        Map<?, ?> payload = adapter.read(new ObservationRequest(COMPANY,
                        ObservationOperation.SAFETY_EVENT_CAMERAS,
                        Map.of("id", "alarm-1", "ledger", "gas", "deviceId", "gas-1")))
                .block().payload();
        assertThat((List<?>) payload.get("candidates")).isEmpty();
        assertThat(((Map<?, ?>) payload.get("currentSafetyDevice")).get("location")).isNull();
    }

    @Test
    void absentEventAndAuthorizationFailuresRemainTyped() {
        ObservationAdapter absent = request -> Mono.error(new ObservationNotFoundException("missing"));
        ObservationAdapter absentAdapter = composed(absent, scope(), scope());
        assertThatThrownBy(() -> absentAdapter.read(request()).block())
                .isInstanceOf(ObservationNotFoundException.class)
                .hasMessage("missing");

        ObservationAdapter unauthorized = request -> Mono.error(new GatewayAuthorizationException(401));
        ObservationAdapter unauthorizedAdapter = composed(unauthorized, scope(), scope());
        assertThatThrownBy(() -> unauthorizedAdapter.read(request()).block())
                .isInstanceOf(GatewayAuthorizationException.class);

        ObservationAdapter unavailable = request -> Mono.error(new ObservationUnavailableException("gateway"));
        ObservationAdapter unavailableAdapter = composed(unavailable, scope(), scope());
        assertThatThrownBy(() -> unavailableAdapter.read(request()).block())
                .isInstanceOf(ObservationUnavailableException.class);
    }

    @Test
    void mismatchedTenantScopeFailsClosed() {
        ObservationAdapter upstream = event(Map.of(
                "eventId", "alarm-1", "ledger", "gas", "deviceId", "gas-1",
                "deviceName", "recorded gas", "occurredAt", "2024-04-20T07:10:01Z"));
        RequestIdentity other = new RequestIdentity("Bearer other", "TENANT-2", IotSource.COMPANY);
        ObservationAdapter adapter = composed(upstream, scope(other,
                device("gas-1", "safety", "gas", "27楼", null, List.of())), scope());
        assertThatThrownBy(() -> adapter.read(request()).block())
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).httpStatus()).isEqualTo(503));
    }

    private static ObservationRequest request() {
        return new ObservationRequest(COMPANY, ObservationOperation.SAFETY_EVENT_CAMERAS,
                Map.of("id", "alarm-1", "ledger", "gas", "deviceId", "gas-1"));
    }

    private static ObservationAdapter event(Map<String, Object> record) {
        return request -> request.operation() == ObservationOperation.SAFETY_EVENT
                ? Mono.just(ObservationResponse.available(Map.of("source", "company", "record", record)))
                : Mono.error(new AssertionError("unexpected operation: " + request.operation()));
    }

    private static ObservationAdapter composed(
            ObservationAdapter upstream, AuthorizedDeviceScope safety, AuthorizedDeviceScope video) {
        IotAdapter iot = new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return Mono.just(safety);
            }
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedSafetyScope(RequestIdentity identity) {
                return Mono.just(safety);
            }
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedVideoScope(RequestIdentity identity) {
                return Mono.just(video);
            }
        };
        return ObservationAdapterFactory.composed(upstream, iot, identity -> Mono.just(Map.of()), null,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AuthorizedDeviceScope scope(AuthorizedDevice... devices) {
        return scope(COMPANY, devices);
    }

    private static AuthorizedDeviceScope scope(RequestIdentity identity, AuthorizedDevice... devices) {
        return new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return identity; }
            @Override public IotSource source() { return IotSource.COMPANY; }
            @Override public List<AuthorizedDevice> devices() { return List.of(devices); }
        };
    }

    private static AuthorizedDevice device(String id, String kind, String name, String address,
            String hub, List<List<String>> paths) {
        return new AuthorizedDevice(id, kind, name, address, true, null, id, hub, null, paths, List.of(), Map.of());
    }
}
