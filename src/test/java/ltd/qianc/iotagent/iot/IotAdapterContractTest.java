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
}
