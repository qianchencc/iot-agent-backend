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
            new RequestIdentity("Bearer company", "TENANT-1", IotSourceEnum.COMPANY);


    @Test
    void unifiedEnergyFeedRetainsEveryMeterAndUsesTheRequestedPageSize() {
        ExchangeFunction fixture = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"code":200,"content":{"totalElements":2,"content":[
                          {"id":"a","equipmentId":"meter-a","readTime":"2026-10-03 08:00:00","epf":100},
                          {"id":"b","equipmentId":"meter-b","readTime":"2026-10-03 08:00:00","epf":200}
                        ]}}
                        """).build());
        ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new ObservationGatewayRoutes(Map.of(ObservationOperationEnum.ENERGY_HISTORY, "/history")),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        ObservationResponse response = adapter.read(new ObservationRequest(COMPANY,
                ObservationOperationEnum.ENERGY_HISTORY, Map.of("size", "500"))).block();

        java.util.List<?> samples = (java.util.List<?>) response.payload().get("samples");
        assertThat(samples.stream().map(sample -> ((Map<?, ?>) sample).get("equipmentId")).toList())
                .isEqualTo(java.util.List.of("meter-a", "meter-b"));
        assertThat(response.payload().get("nextPage")).isNull();
        assertThat(response.coverageComplete()).isTrue();
    }

    @Test
    void energyDefaultSmallPageDoesNotSkipRemainingRowsWhenLastFlagIsAbsent() {
        String rows = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> "{\"id\":\"" + index
                        + "\",\"equipmentId\":\"meter-a\",\"readTime\":\"2026-10-03 08:00:00\",\"epf\":100}")
                .collect(java.util.stream.Collectors.joining(","));
        ExchangeFunction fixture = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"code\":200,\"content\":{\"totalElements\":40,\"content\":[" + rows + "]}}")
                .build());
        ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new ObservationGatewayRoutes(Map.of(ObservationOperationEnum.ENERGY_HISTORY, "/history")),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        ObservationResponse response = adapter.read(new ObservationRequest(COMPANY,
                ObservationOperationEnum.ENERGY_HISTORY, Map.of())).block();

        assertThat(response.payload().get("nextPage")).isEqualTo(1);
        assertThat(response.coverageComplete()).isFalse();
    }
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
                new ObservationGatewayRoutes(Map.of(ObservationOperationEnum.COMPANY_ENERGY_DAILY,
                        "/customer/company-energy/daily")),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        ObservationResponse response = adapter.read(new ObservationRequest(COMPANY,
                ObservationOperationEnum.COMPANY_ENERGY_DAILY, Map.of("days", "7", "end", "2026-01-01"))).block();

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
                new ObservationGatewayRoutes(Map.of(ObservationOperationEnum.SAFETY_EVENT_CAMERAS,
                        "/customer/safety/{ledger}/cameras")),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        adapter.read(new ObservationRequest(COMPANY, ObservationOperationEnum.SAFETY_EVENT_CAMERAS,
                Map.of("ledger", "smoke", "id", "event-1"))).block();

        assertThat(request.get().url().getPath()).isEqualTo("/customer/safety/smoke/cameras");
    }

    @Test
    void environmentHistoryMapsWindowSamplesAndPartialCoverage() {
        ExchangeFunction fixture = candidate -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"code\":200,\"content\":{\"content\":[{\"id\":\"e1\",\"deviceCode\":\"site-1\",\"createdAt\":\"2026-10-09 08:00:00\",\"tem\":\"28.5\",\"hum\":null,\"pm25\":\"bad\"}],\"last\":false,\"totalElements\":101}}")
                .build());
        ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new ObservationGatewayRoutes(Map.of(ObservationOperationEnum.ENVIRONMENT_HISTORY, "/customer/environment/history")),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());
        ObservationResponse response = adapter.read(new ObservationRequest(COMPANY, ObservationOperationEnum.ENVIRONMENT_HISTORY,
                Map.of("from", "2026-10-09T00:00:00+08:00", "to", "2026-10-09T23:59:59+08:00", "page", "0"))).block();
        assertThat(response.status()).isEqualTo(ObservationStatusEnum.PARTIAL);
        assertThat(response.coverageComplete()).isFalse();
        Map<?, ?> sample = (Map<?, ?>) ((java.util.List<?>) response.payload().get("samples")).get(0);
        assertThat(sample.get("recordedAt")).isEqualTo("2026-10-09T00:00:00Z");
        assertThat(((Map<?, ?>) sample.get("values")).get("temp")).isEqualTo(28.5d);
        assertThat(((Map<?, ?>) sample.get("values")).get("pm25")).isNull();
        assertThat(response.payload().get("nextPage")).isEqualTo(1);
    }

    @Test
    void upstreamAuthorizationAndServiceFailuresRemainTyped() {
        for (HttpStatus status : new HttpStatus[] { HttpStatus.UNAUTHORIZED, HttpStatus.SERVICE_UNAVAILABLE }) {
            ExchangeFunction fixture = candidate -> Mono.just(ClientResponse.create(status).build());
            ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                    WebClient.builder().exchangeFunction(fixture).build(),
                    new ObservationGatewayRoutes(Map.of(ObservationOperationEnum.ENVIRONMENT_HISTORY, "/customer/environment/history")),
                    Duration.ofSeconds(1), IotCallPolicy.passThrough());
            Throwable error = org.assertj.core.api.Assertions.catchThrowable(() -> adapter.read(new ObservationRequest(
                    COMPANY, ObservationOperationEnum.ENVIRONMENT_HISTORY, Map.of())).block());
            if (status == HttpStatus.UNAUTHORIZED) assertThat(error).isInstanceOf(GatewayAuthorizationException.class);
            else assertThat(error).isInstanceOf(ObservationUnavailableException.class);
        }
    }

    @Test
    void absentRouteFailsClosedAndDoesNotBecomeSuccessfulEmptyPayload() {
        ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                WebClient.builder().build(), new ObservationGatewayRoutes(Map.of()),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        StepVerifier.create(adapter.read(new ObservationRequest(COMPANY,
                        ObservationOperationEnum.COMPANY_METER_REPORT, Map.of())))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ObservationUnavailableException.class);
                    assertThat(((ObservationUnavailableException) error).httpStatus()).isEqualTo(503);
                })
                .verify();
    }
    @Test
    void unknownEnergyReportProtocolRemainsTypedUnavailable() {
        ObservationAdapter adapter = ObservationAdapterFactory.webClient(
                WebClient.builder().build(), new ObservationGatewayRoutes(Map.of()),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        StepVerifier.create(adapter.read(new ObservationRequest(COMPANY,
                        ObservationOperationEnum.ENERGY_REPORT, Map.of("kind", "statistics"))))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ObservationUnavailableException.class);
                    assertThat(((ObservationUnavailableException) error).code())
                            .isEqualTo("OBSERVATION_UPSTREAM_UNAVAILABLE");
                    assertThat(((ObservationUnavailableException) error).httpStatus()).isEqualTo(503);
                })
                .verify();
    }
}
