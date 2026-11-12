package com.ehe.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.ehe.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class LatestSafetyAlertContractTest {
    private static final RequestIdentity ID = new RequestIdentity("Bearer latest-fixture", "TENANT", IotSourceEnum.COMPANY);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T06:00:00Z"), ZoneOffset.UTC);
    private static final List<AuthorizedDevice> DEVICES = List.of(
            new AuthorizedDevice("gas-a", "safety", "A燃气感应器", "A区", true),
            new AuthorizedDevice("gas-b", "safety", "B燃气感应器", "B区", true),
            new AuthorizedDevice("water-a", "safety", "水浸传感器", "A区", true));

    @Test
    void latestIsSameTypeAndDeviceEvenWhenOtherDevicesAreNewerAndPagesAreNotOrdered() {
        AtomicInteger calls = new AtomicInteger();
        ObservationAdapter upstream = request -> {
            calls.incrementAndGet();
            assertThat(request.parameters()).containsEntry("ledger", "gas").containsEntry("deviceId", "gas-a");
            int page = Integer.parseInt(request.parameters().get("page"));
            List<Map<String, Object>> rows = page == 0 ? List.of(
                    row("older", "gas-a", "gas", "2026-11-01T00:00:00Z"),
                    row("other-gas", "gas-b", "gas", "2026-11-05T05:59:00Z"),
                    row("new-water", "water-a", "flooding", "2026-11-05T05:59:59Z"))
                    : List.of(row("latest", "gas-a", "gas", "2026-11-04T10:00:00Z"),
                    row("future", "gas-a", "gas", "2026-11-06T00:00:00Z"));
            return Mono.just(ObservationResponse.available(Map.of("items", rows, "last", page == 1)));
        };
        Map<String, Object> result = adapter(upstream).read(request("gas-a")).block().payload();
        assertThat(((Map<?, ?>) result.get("record")).get("eventId")).isEqualTo("latest");
        Map<?, ?> reference = (Map<?, ?>) result.get("reference");
        assertThat(reference.get("eventId")).isEqualTo("latest");
        assertThat(reference.get("deviceId")).isEqualTo("gas-a");
        assertThat(reference.get("ledger")).isEqualTo("gas");
        assertThat(calls).hasValue(2);
    }

    @Test
    void specifiedDeviceCannotWidenToAnotherTypeOrUnauthorizedDevice() {
        AtomicInteger calls = new AtomicInteger();
        ObservationAdapter upstream = request -> { calls.incrementAndGet(); return Mono.error(new AssertionError("must reject before reading")); };
        ObservationAdapter adapter = adapter(upstream);
        assertThatThrownBy(() -> adapter.read(request("water-a")).block()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> adapter.read(request("foreign")).block()).isInstanceOf(BusinessException.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void completeEmptyIsDistinctFromFailedOrIncompleteHistory() {
        Map<String, Object> empty = adapter(r -> Mono.just(ObservationResponse.available(Map.of("items", List.of(), "last", true))))
                .read(request("gas-a")).block().payload();
        assertThat(empty).containsEntry("record", null).containsEntry("reference", null).containsEntry("coverageComplete", true);
        assertThatThrownBy(() -> adapter(r -> Mono.error(new UpstreamUnavailableException("fixture offline")))
                .read(request("gas-a")).block()).isInstanceOf(UpstreamUnavailableException.class);
        assertThatThrownBy(() -> adapter(r -> Mono.just(ObservationResponse.available(Map.of("items", List.of(), "last", false))))
                .read(request("gas-a")).block()).isInstanceOf(ObservationUnavailableException.class);
    }

    private static ObservationAdapter adapter(ObservationAdapter upstream) {
        IotAdapter iot = identity -> Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), DEVICES));
        return ObservationAdapterFactory.safetyLedger(upstream, iot, null, CLOCK);
    }
    private static ObservationRequest request(String device) {
        return new ObservationRequest(ID, ObservationOperationEnum.SAFETY_EVENTS,
                Map.of("selection", "latest", "type", "gas", "deviceId", device, "to", CLOCK.instant().toString()));
    }
    private static Map<String, Object> row(String event, String device, String ledger, String at) {
        return Map.of("eventId", event, "deviceId", device, "ledger", ledger, "deviceName", device,
                "occurredAt", at, "alarm", true, "report", false);
    }
}
