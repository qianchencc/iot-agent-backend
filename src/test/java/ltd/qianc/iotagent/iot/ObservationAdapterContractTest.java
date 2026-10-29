package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ObservationAdapterContractTest {
    private static final RequestIdentity COMPANY =
            new RequestIdentity("Bearer company", "TENANT-1", IotSource.COMPANY);

    @Test
    void configuredCompanyEnergyDailyRouteIsSelectedAndPayloadIsMapped() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        ExchangeFunction fixture = candidate -> {
            request.set(candidate);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"code\":200,\"content\":{\"days\":[{\"date\":\"2026-01-01\",\"value\":12}]}}")
                    .build());
        };
        ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new ObservationGatewayRoutes(Map.of(ObservationOperation.COMPANY_ENERGY_DAILY,
                        "/customer/company-energy/daily")),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        ObservationResponse response = adapter.read(new ObservationRequest(COMPANY,
                ObservationOperation.COMPANY_ENERGY_DAILY, Map.of("days", "7", "end", "2026-01-01"))).block();

        assertThat(request.get().url().getQuery()).contains("days=7", "end=2026-01-01");
        assertThat(response.payload()).containsKey("days");
    }

    @Test
    void safetyEventCamerasUsesOnlyExplicitLedgerTemplate() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        ExchangeFunction fixture = candidate -> {
            request.set(candidate);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"code\":200,\"content\":{\"cameras\":[]}}")
                    .build());
        };
        ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new ObservationGatewayRoutes(Map.of(ObservationOperation.SAFETY_EVENT_CAMERAS,
                        "/customer/safety/{ledger}/cameras")),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        adapter.read(new ObservationRequest(COMPANY, ObservationOperation.SAFETY_EVENT_CAMERAS,
                Map.of("ledger", "smoke", "id", "event-1"))).block();

        assertThat(request.get().url().getPath()).isEqualTo("/customer/safety/smoke/cameras");
    }

    @Test
    void absentRouteFailsClosedAndDoesNotBecomeSuccessfulEmptyPayload() {
        ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                WebClient.builder().build(), new ObservationGatewayRoutes(Map.of()),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        StepVerifier.create(adapter.read(new ObservationRequest(COMPANY,
                        ObservationOperation.COMPANY_METER_REPORT, Map.of())))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ObservationUnavailableException.class);
                    assertThat(((ObservationUnavailableException) error).httpStatus()).isEqualTo(503);
                })
                .verify();
    }
}
