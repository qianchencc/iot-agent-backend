package ltd.qianc.iotagent.iot;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/** In-process production-protocol exchange fixture; no real gateway or device is contacted. */
class IotAdapterContractTest {

    @Test
    void forwardsIdentityAndKeepsOfficeScopeSource() {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        ExchangeFunction fixture = candidate -> {
            request.set(candidate);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"code\":200,\"content\":[{\"id\":\"ac-1\",\"kind\":\"ac\",\"name\":\"会议室空调\",\"installAddress\":\"A-101\"}]}")
                    .build());
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new IotGatewayRoutes("/office-scope", "/company-scope"),
                Duration.ofSeconds(1),
                IotCallPolicy.passThrough());

        AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer office-token", "TENANT-1", IotSource.OFFICE))
                .block(Duration.ofSeconds(1));

        assertThat(request.get().url().getPath()).isEqualTo("/office-scope");
        assertThat(request.get().headers().getFirst("Authorization")).isEqualTo("Bearer office-token");
        assertThat(request.get().headers().getFirst("Tenantcode")).isEqualTo("TENANT-1");
        assertThat(request.get().headers().getFirst("source")).isNull();
        assertThat(scope).isNotNull();
        assertThat(scope.source()).isEqualTo(IotSource.OFFICE);
        assertThat(scope.devices()).extracting(AuthorizedDevice::id).containsExactly("ac-1");
    }

    @Test
    void propagatesGatewayForbiddenAsTypedBusinessError() {
        ExchangeFunction fixture = ignored -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new IotGatewayRoutes("/office-scope", "/company-scope"),
                Duration.ofSeconds(1),
                IotCallPolicy.passThrough());

        StepVerifier.create(adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer token", "TENANT-1", IotSource.COMPANY)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayAuthorizationException.class);
                    assertThat(((GatewayAuthorizationException) error).upstreamStatus()).isEqualTo(403);
                })
                .verify();
    }

    @Test
    void missingGatewayFailsClosedWithoutAnEmptySuccessfulScope() {
        IotAdapter adapter = IotAdapterFactory.failClosed("物联上游未配置");

        StepVerifier.create(adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer token", "TENANT-1", IotSource.COMPANY)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(UpstreamUnavailableException.class);
                    assertThat(((UpstreamUnavailableException) error).httpStatus()).isEqualTo(503);
                })
                .verify();
    }

    @Test
    void timeoutIsPerCallAndCancelsTheInFlightExchange() {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ExchangeFunction fixture = ignored -> Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new IotGatewayRoutes("/office-scope", "/company-scope"),
                Duration.ofMillis(20),
                IotCallPolicy.passThrough());

        StepVerifier.create(adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer token", "TENANT-1", IotSource.OFFICE)))
                .expectError(UpstreamUnavailableException.class)
                .verify(Duration.ofSeconds(1));
        assertThat(cancelled).isTrue();
    }

    @Test
    void propagatesGatewayUnauthorizedAsTypedBusinessError() {
        ExchangeFunction fixture = ignored -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build());
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new IotGatewayRoutes("/office-scope", "/company-scope"),
                Duration.ofSeconds(1),
                IotCallPolicy.passThrough());

        StepVerifier.create(adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer token", "TENANT-1", IotSource.OFFICE)))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayAuthorizationException.class);
                    assertThat(((GatewayAuthorizationException) error).upstreamStatus()).isEqualTo(401);
                })
                .verify();
    }

    @Test
    void companyCatalogTraversesRuntimeSourcesAndKeepsBindingsInternal() {
        AtomicReference<java.util.List<String>> requests = new AtomicReference<>(new java.util.ArrayList<>());
        ExchangeFunction fixture = candidate -> {
            var url = candidate.url();
            requests.get().add(url.getPath() + "?" + url.getQuery());
            String path = url.getPath();
            String parent = url.getQuery() == null ? null : java.util.Arrays.stream(url.getQuery().split("&"))
                    .filter(value -> value.startsWith("parentId=")).findFirst()
                    .map(value -> java.net.URLDecoder.decode(value.substring("parentId=".length()), java.nio.charset.StandardCharsets.UTF_8))
                    .orElse(null);
            if (path.endsWith("/myAreaList")) {
                if (parent == null || parent.isBlank()) {
                    return Mono.just(json("{\"code\":200,\"content\":[{\"id\":\"root\",\"fullCode\":\"root\",\"name\":\"Root\"}]}"));
                }
                return Mono.just(json("{\"code\":204,\"content\":null}"));
            }
            if (path.endsWith("/devicePage")) {
                if (path.contains("/lighting/")) return Mono.just(json("[{\"id\":\"light-1\",\"hubDeviceId\":\"hub-light\",\"name\":\"灯\",\"installAddress\":\"A\"}]"));
                if (path.contains("/ac/")) return Mono.just(json("[{\"id\":\"ac-1\",\"hubDeviceId\":\"hub-ac\",\"name\":\"空调\",\"installAddress\":\"B\"}]"));
                return Mono.just(json("{\"code\":204,\"content\":null}"));
            }
            if (path.endsWith("/iot-center/device/page")) {
                String query = url.getQuery() == null ? "" : url.getQuery();
                if (query.contains("YQ_AIR_CONDITION")) {
                    return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"hub-ac\",\"modelCode\":\"YQ_AIR_CONDITION\",\"actionList\":[{\"id\":\"ac-control\",\"actionType\":\"airCondition\"},{\"id\":\"ac-detail\",\"actionType\":\"acDetail\"}]}],\"last\":true}}"));
                }
                String kind = query.contains("YQ-ENV-SENSOR") ? "environment" : query.contains("YQ-ENERGY-EQUIPMENT") ? "energy" : "safety";
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"" + kind + "-1\",\"name\":\"" + kind + "\",\"installAddress\":\"C\"}],\"last\":true}}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page",
                        "/micro-api/api/office/app/device",
                        "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage",
                        "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1),
                IotCallPolicy.passThrough());

        AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY))
                .block(Duration.ofSeconds(1));

        assertThat(scope).isNotNull();
        assertThat(scope.devices()).extracting(AuthorizedDevice::kind)
                .contains("environment", "energy", "safety", "lighting", "ac");
        assertThat(scope.device("light-1").orElseThrow().controlRef()).isEqualTo("light-1");
        assertThat(scope.device("hub-ac")).as("devices=%s", scope.devices()).isPresent();
        assertThat(scope.device("hub-ac").orElseThrow().controlRef()).isEqualTo("ac-control");
        assertThat(requests.get()).anyMatch(value -> value.contains("myAreaList"));
        assertThat(requests.get()).anyMatch(value -> value.contains("modelCode=YQ_AIR_CONDITION"));
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
