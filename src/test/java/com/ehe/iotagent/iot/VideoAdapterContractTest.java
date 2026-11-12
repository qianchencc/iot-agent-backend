package com.ehe.iotagent.iot;

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

/** In-process Runtime video protocol fixtures; no customer cloud is contacted. */
class VideoAdapterContractTest {

    private static final RequestIdentity COMPANY = new RequestIdentity("Bearer company-a", "TENANT-1", IotSourceEnum.COMPANY);

    @Test
    void companyLiveUsesCurrentVideoOnlyScopeAndMonitorReference() {
        AtomicReference<ClientRequest> streamRequest = new AtomicReference<>();
        IotAdapter adapter = adapter(request -> {
            streamRequest.set(request);
            if (request.url().getPath().endsWith("/myAreaList")) {
                return Mono.just(json(request.url().getQuery() == null ? "[{\"id\":\"leaf\",\"fullCode\":\"L-1\",\"name\":\"一层\"}]" : "[]"));
            }
            if (request.url().getPath().endsWith("/monitor/devicePage")) {
                return Mono.just(json("[{\"id\":\"monitor-1\",\"hubDeviceId\":\"hub-current\",\"name\":\"大厅摄像头\"}]"));
            }
            return Mono.just(json("{\"code\":200,\"content\":{\"result\":true,\"url\":\"wss://fixture/live\"}}"));
        });

        VideoStream stream = adapter.openVideoStream(COMPANY, "monitor-1").block(Duration.ofSeconds(1));

        assertThat(stream).isEqualTo(new VideoStream("大厅摄像头", "wss://fixture/live"));
        assertThat(streamRequest.get().method().name()).isEqualTo("POST");
        assertThat(streamRequest.get().url().getPath()).isEqualTo("/micro-api/api/office/app/device/monitor/down");
        assertThat(streamRequest.get().body()).isNotNull();
    }

    @Test
    void playbackUsesFixedActionCurrentHubAndWindowOnly() {
        AtomicReference<ClientRequest> playbackRequest = new AtomicReference<>();
        IotAdapter adapter = adapter(request -> {
            if (request.url().getPath().endsWith("/myAreaList")) {
                return Mono.just(json(request.url().getQuery() == null ? "[{\"id\":\"leaf\",\"fullCode\":\"L-1\",\"name\":\"一层\"}]" : "[]"));
            }
            if (request.url().getPath().endsWith("/monitor/devicePage")) {
                return Mono.just(json("[{\"id\":\"monitor-1\",\"hubDeviceId\":\"hub-authorized\",\"name\":\"大厅摄像头\"}]"));
            }
            playbackRequest.set(request);
            return Mono.just(json("{\"code\":200,\"content\":{\"result\":true,\"url\":\"wss://fixture/playback\"}}"));
        });

        VideoStream stream = adapter.openVideoPlayback(COMPANY, "monitor-1",
                        new VideoPlaybackWindow("2026-10-08T00:00:00.000+08:00", "2026-10-08T02:00:00.000+08:00"))
                .block(Duration.ofSeconds(1));

        assertThat(stream.url()).isEqualTo("wss://fixture/playback");
        assertThat(playbackRequest.get().url().getPath())
                .isEqualTo("/micro-api/api/iot-center/interactive/hub-authorized/bec190c9819348e08b203a95b7629ad8/down");
    }

    @Test
    void playbackBusyAndAuthorizationRemainTyped() {
        IotAdapter busy = adapter(request -> {
            if (request.url().getPath().endsWith("/myAreaList")) {
                return Mono.just(json(request.url().getQuery() == null ? "[{\"id\":\"leaf\",\"fullCode\":\"L-1\"}]" : "[]"));
            }
            if (request.url().getPath().endsWith("/monitor/devicePage")) {
                return Mono.just(json("[{\"id\":\"monitor-1\",\"hubDeviceId\":\"hub-authorized\",\"name\":\"大厅摄像头\"}]"));
            }
            return Mono.just(json("{\"code\":207,\"content\":null}"));
        });
        StepVerifier.create(busy.openVideoPlayback(COMPANY, "monitor-1",
                        new VideoPlaybackWindow("2026-10-08T00:00:00.000+08:00", "2026-10-08T02:00:00.000+08:00")))
                .expectError(VideoStreamBusyException.class).verify();

        IotAdapter unauthorized = adapter(request -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build()));
        StepVerifier.create(unauthorized.openVideoStream(COMPANY, "monitor-1"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayAuthorizationException.class);
                    assertThat(((GatewayAuthorizationException) error).upstreamStatus()).isEqualTo(403);
                }).verify();
    }

    @Test
    void liveAndPlaybackReuseVideoAuthorizationButAlwaysRequestNewStreams() {
        var directoryReads = new java.util.concurrent.atomic.AtomicInteger();
        var streamReads = new java.util.concurrent.atomic.AtomicInteger();
        IotAdapter adapter = adapter(request -> {
            if (request.url().getPath().endsWith("/myAreaList")) {
                directoryReads.incrementAndGet();
                return Mono.just(json(request.url().getQuery() == null
                        ? "[{\"id\":\"leaf\",\"fullCode\":\"L-1\",\"name\":\"Fixture floor\"}]" : "[]"));
            }
            if (request.url().getPath().endsWith("/monitor/devicePage")) {
                directoryReads.incrementAndGet();
                return Mono.just(json("[{\"id\":\"monitor-1\",\"hubDeviceId\":\"hub-authorized\",\"name\":\"Fixture camera\"}]"));
            }
            return Mono.just(json("{\"code\":200,\"content\":{\"result\":true,\"url\":\"wss://fixture/session-"
                    + streamReads.incrementAndGet() + "\"}}"));
        });
        var first = adapter.openVideoStream(COMPANY, "monitor-1").block();
        int initialDirectoryReads = directoryReads.get();
        var recording = adapter.openVideoPlayback(COMPANY, "monitor-1",
                new VideoPlaybackWindow("2026-10-08T00:00:00.000+08:00", "2026-10-08T02:00:00.000+08:00")).block();
        var retry = adapter.openVideoStream(COMPANY, "monitor-1").block();
        assertThat(first.url()).isEqualTo("wss://fixture/session-1");
        assertThat(recording.url()).isEqualTo("wss://fixture/session-2");
        assertThat(retry.url()).isEqualTo("wss://fixture/session-3");
        assertThat(directoryReads.get()).isEqualTo(initialDirectoryReads);
        // A rotated token with the same principal must not reuse the prior authorization.
        RequestIdentity rotated = new RequestIdentity("Bearer rotated", COMPANY.tenantCode(), IotSourceEnum.COMPANY, COMPANY.ownerKey());
        adapter.openVideoStream(rotated, "monitor-1").block();
        assertThat(directoryReads.get()).isEqualTo(initialDirectoryReads * 2);
        RequestIdentity otherTenant = new RequestIdentity(COMPANY.authorization(), "TENANT-2", IotSourceEnum.COMPANY, COMPANY.ownerKey());
        adapter.openVideoStream(otherTenant, "monitor-1").block();
        assertThat(directoryReads.get()).isEqualTo(initialDirectoryReads * 3);
        RequestIdentity otherOwner = new RequestIdentity(COMPANY.authorization(), COMPANY.tenantCode(), IotSourceEnum.COMPANY, "other-owner");
        adapter.openVideoStream(otherOwner, "monitor-1").block();
        assertThat(directoryReads.get()).isEqualTo(initialDirectoryReads * 4);
    }

    @Test
    void failedVideoDirectoryIsNotReusedAndCannotAuthorizeAStream() {
        var firstFailure = new java.util.concurrent.atomic.AtomicBoolean(true);
        var streamReads = new java.util.concurrent.atomic.AtomicInteger();
        IotAdapter adapter = adapter(request -> {
            if (request.url().getPath().endsWith("/myAreaList")) {
                if (firstFailure.getAndSet(false)) return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
                return Mono.just(json(request.url().getQuery() == null
                        ? "[{\"id\":\"leaf\",\"fullCode\":\"L-1\"}]" : "[]"));
            }
            if (request.url().getPath().endsWith("/monitor/devicePage")) {
                return Mono.just(json("[{\"id\":\"monitor-1\",\"hubDeviceId\":\"hub-authorized\",\"name\":\"Fixture camera\"}]"));
            }
            streamReads.incrementAndGet();
            return Mono.just(json("{\"code\":200,\"content\":{\"result\":true,\"url\":\"wss://fixture/recovered\"}}"));
        });
        StepVerifier.create(adapter.openVideoStream(COMPANY, "monitor-1"))
                .expectError(GatewayAuthorizationException.class).verify();
        assertThat(streamReads.get()).isZero();
        assertThat(adapter.openVideoStream(COMPANY, "monitor-1").block().url()).isEqualTo("wss://fixture/recovered");
        assertThat(streamReads.get()).isEqualTo(1);
    }

    private static IotAdapter adapter(ExchangeFunction exchange) {
        return IotAdapterFactory.webClient(WebClient.builder().exchangeFunction(exchange).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page", "/micro-api/api/energy/equipment/page",
                        "/micro-api/api/perception/device/page", "/micro-api/api/iot-center/device",
                        "/micro-api/api/office/app/device", "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage", "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build();
    }
}
