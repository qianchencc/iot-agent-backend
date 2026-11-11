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
            new RequestIdentity("Bearer company", "TENANT-1", IotSourceEnum.COMPANY);
    private static final Instant NOW = Instant.parse("2024-04-20T07:11:01Z");


    @Test
    void originalEventNameCanFindCamerasWithoutCurrentDeviceMetadata() {
        ObservationAdapter upstream = event(Map.of(
                "eventId", "alarm-1", "ledger", "gas", "deviceId", "gas-1",
                "deviceName", "27楼茶水间燃气感应器", "occurredAt", "2024-04-20T07:10:01Z"));
        ObservationAdapter adapter = composed(upstream,
                scope(device("gas-1", "safety", "26楼当前燃气设备", "26楼当前位置", null, List.of())),
                scope(device("camera-event", "video", "27楼茶水间监控", "27楼茶水间", "hub-event", List.of()),
                        device("camera-current", "video", "26楼监控", "26楼当前位置", "hub-current", List.of())));
        Map<?, ?> payload = adapter.read(request()).block().payload();
        List<?> candidates = (List<?>) payload.get("candidates");
        assertThat(candidates).hasSize(1);
        Map<?, ?> candidate = (Map<?, ?>) candidates.getFirst();
        assertThat(candidate.get("deviceId")).isEqualTo("camera-event");
        assertThat(candidate.get("beginTime")).isEqualTo("2024-04-20T14:55:01.000+08:00");
        assertThat(candidate.get("endTime")).isEqualTo("2024-04-20T15:25:01.000+08:00");
        assertThat(((Map<?, ?>) payload.get("currentSafetyDevice")).get("location")).isNull();
    }

    @Test
    void missingCurrentSafetyMetadataDoesNotInventLocationOrCandidates() {
        ObservationAdapter upstream = event(Map.of(
                "eventId", "alarm-1", "ledger", "gas", "deviceId", "gas-1",
                "deviceName", "recorded gas", "occurredAt", "2024-04-20T07:10:01Z"));
        ObservationAdapter adapter = composed(upstream,
                scope(device("gas-1", "safety", "燃气感应器", null, null, List.of())),
                scope(device("cam-27", "video", "27楼监控", "27楼", "hub", List.of())));

        Map<?, ?> payload = adapter.read(new ObservationRequest(COMPANY,
                        ObservationOperationEnum.SAFETY_EVENT_CAMERAS,
                        Map.of("id", "alarm-1", "ledger", "gas", "deviceId", "gas-1")))
                .block().payload();
        assertThat((List<?>) payload.get("candidates")).isEmpty();
        assertThat(((Map<?, ?>) payload.get("currentSafetyDevice")).get("location")).isNull();
    }

    @Test
    void absentEventAndAuthorizationFailuresRemainTyped() {
        AuthorizedDevice sensor = device("gas-1", "safety", "燃气感应器", null, null, List.of());
        ObservationAdapter absent = request -> Mono.error(new ObservationNotFoundException("missing"));
        ObservationAdapter absentAdapter = composed(absent, scope(sensor), scope());
        assertThatThrownBy(() -> absentAdapter.read(request()).block())
                .isInstanceOf(ObservationNotFoundException.class)
                .hasMessage("missing");

        ObservationAdapter unauthorized = request -> Mono.error(new GatewayAuthorizationException(401));
        ObservationAdapter unauthorizedAdapter = composed(unauthorized, scope(sensor), scope());
        assertThatThrownBy(() -> unauthorizedAdapter.read(request()).block())
                .isInstanceOf(GatewayAuthorizationException.class);

        ObservationAdapter unavailable = request -> Mono.error(new ObservationUnavailableException("gateway"));
        ObservationAdapter unavailableAdapter = composed(unavailable, scope(sensor), scope());
        assertThatThrownBy(() -> unavailableAdapter.read(request()).block())
                .isInstanceOf(ObservationUnavailableException.class);
    }

    @Test
    void mismatchedTenantScopeFailsClosed() {
        ObservationAdapter upstream = event(Map.of(
                "eventId", "alarm-1", "ledger", "gas", "deviceId", "gas-1",
                "deviceName", "recorded gas", "occurredAt", "2024-04-20T07:10:01Z"));
        RequestIdentity other = new RequestIdentity("Bearer other", "TENANT-2", IotSourceEnum.COMPANY);
        ObservationAdapter adapter = composed(upstream, scope(other,
                device("gas-1", "safety", "gas", "27楼", null, List.of())), scope());
        assertThatThrownBy(() -> adapter.read(request()).block())
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).httpStatus()).isEqualTo(503));
    }

    private static ObservationRequest request() {
        return new ObservationRequest(COMPANY, ObservationOperationEnum.SAFETY_EVENT_CAMERAS,
                Map.of("id", "alarm-1", "ledger", "gas", "deviceId", "gas-1"));
    }

    private static ObservationAdapter event(Map<String, Object> record) {
        return request -> {
            if (request.operation() != ObservationOperationEnum.SAFETY_EVENTS) {
                return Mono.error(new AssertionError("unexpected operation: " + request.operation()));
            }
            if (request.parameters().containsKey("deviceId")
                    && !String.valueOf(record.get("deviceId")).equals(request.parameters().get("deviceId"))) {
                return Mono.error(new AssertionError("unexpected deviceId filter"));
            }
            Map<String, Object> page = new java.util.LinkedHashMap<>();
            page.put("source", "company");
            page.put("items", List.of(record));
            page.put("total", 1L);
            page.put("scanned", 1);
            page.put("last", true);
            return Mono.just(ObservationResponse.available(page));
        };
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
        return ObservationAdapterFactory.composed(upstream, iot, identity -> Mono.just(Map.of()), null, null,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AuthorizedDeviceScope scope(AuthorizedDevice... devices) {
        return scope(COMPANY, devices);
    }

    private static AuthorizedDeviceScope scope(RequestIdentity identity, AuthorizedDevice... devices) {
        return new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return identity; }
            @Override public IotSourceEnum source() { return IotSourceEnum.COMPANY; }
            @Override public List<AuthorizedDevice> devices() { return List.of(devices); }
        };
    }

    private static AuthorizedDevice device(String id, String kind, String name, String address,
            String hub, List<List<String>> paths) {
        return new AuthorizedDevice(id, kind, name, address, true, null, id, hub, null, paths, List.of(), Map.of());
    }
}
