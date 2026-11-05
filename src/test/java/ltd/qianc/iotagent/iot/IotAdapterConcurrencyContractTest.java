package ltd.qianc.iotagent.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Concurrency regressions for the Node-runtime read migration: every read
 * fan-out is a per-call bounded queue of six (never a per-type 6+6 stack and
 * never a process-wide limit), same-path pagination stays sequential, results
 * keep a stable order, and failures record the first error, stop claiming new
 * work, let already-claimed reads drain, and only then rethrow — with 401/403
 * still failing closed. In-process production-protocol exchange fixtures; no
 * real gateway or device is contacted.
 */
class IotAdapterConcurrencyContractTest {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private static final IotGatewayRoutes RUNTIME_COMPANY = IotGatewayRoutes.runtimeCompany(
            "/office-scope", null,
            "/micro-api/api/iot-center/device/page",
            "/micro-api/api/energy/equipment/page",
            "/micro-api/api/perception/device/page",
            "/micro-api/api/iot-center/device",
            "/micro-api/api/office/app/device",
            "/micro-api/api/office/app/device/ac/myAreaList",
            "/micro-api/api/office/app/device/ac/devicePage",
            "/micro-api/api/iot-center/device/page");

    /** In-flight ledger for one exchange shape: peak concurrency plus drain accounting. */
    private static final class InFlight {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();

        Mono<ClientResponse> track(Mono<ClientResponse> response) {
            started.incrementAndGet();
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            return response.doOnTerminate(() -> {
                inFlight.decrementAndGet();
                finished.incrementAndGet();
            });
        }
    }

    @Test
    void companyStateReadsShareOneBoundedQueueOfExactlySixConcurrentAcReads() throws Exception {
        java.util.concurrent.CountDownLatch sixReached = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            if (path.contains("/interactive/")) {
                started.incrementAndGet();
                return Mono.fromCallable(() -> {
                    int now = inFlight.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    if (now == 6) sixReached.countDown();
                    // While all six lanes are blocked, the queue must not claim a seventh read.
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("bounded queue release latch timed out");
                    }
                    inFlight.decrementAndGet();
                    return json("{\"code\":200,\"content\":{\"data\":{\"O\":{\"val\":1},\"tT\":{\"val\":260}}}}");
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
            }
            return Mono.just(acCatalogResponse(path, candidate.url().getQuery()));
        };
        IotAdapter adapter = companyAdapter(fixture);
        List<String> ids = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> "hub-ac-" + index).toList();

        java.util.concurrent.CompletableFuture<DeviceStateReadResult> future = adapter.readDeviceStates(
                new RequestIdentity("Bearer company", "TENANT-1", IotSourceEnum.COMPANY), ids).toFuture();

        // The queue reaches exactly six simultaneous reads; the old three-lane
        // queue can never open this gate and times out here.
        try {
            assertThat(sixReached.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(started.get()).isEqualTo(6);
            assertThat(inFlight.get()).isEqualTo(6);
        } finally {
            release.countDown();
            try { future.get(15, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception cleanupFailure) { future.cancel(true); }
        }

        DeviceStateReadResult result = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(peak.get()).isEqualTo(6);
        assertThat(started.get()).isEqualTo(12);
        assertThat(result.states()).containsOnlyKeys(ids.toArray(String[]::new));
        for (String id : ids) {
            assertThat(((Map<?, ?>) result.states().get(id)).get("power")).isEqualTo("on");
        }
    }

    @Test
    void companyStateAuthorizationFailureStopsClaimingAndDrainsInFlightReads() {
        InFlight acReads = new InFlight();
        java.util.concurrent.CountDownLatch inFlightStarted = new java.util.concurrent.CountDownLatch(5);
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            if (path.contains("/interactive/")) {
                if (path.contains("/hub-ac-0/")) {
                    return Mono.fromCallable(() -> {
                        if (!inFlightStarted.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("five in-flight reads did not start");
                        }
                        return ClientResponse.create(HttpStatus.FORBIDDEN).build();
                    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
                }
                inFlightStarted.countDown();
                return acReads.track(delayed(300,
                        json("{\"code\":200,\"content\":{\"data\":{\"O\":{\"val\":1}}}}")));
            }
            return Mono.just(acCatalogResponse(path, candidate.url().getQuery()));
        };
        IotAdapter adapter = companyAdapter(fixture);
        List<String> ids = java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> "hub-ac-" + index).toList();

        assertThatThrownBy(() -> adapter.readDeviceStates(
                new RequestIdentity("Bearer company", "TENANT-1", IotSourceEnum.COMPANY), ids)
                .block(Duration.ofSeconds(10)))
                .isInstanceOf(GatewayAuthorizationException.class)
                .satisfies(error -> assertThat(((GatewayAuthorizationException) error).upstreamStatus()).isEqualTo(403));
        // Reads one to six were already claimed: the five tracked ones drain to
        // completion, and reads seven to twelve are never claimed after the 403.
        assertThat(acReads.started.get()).isEqualTo(5);
        assertThat(acReads.finished.get()).isEqualTo(5);
    }

    @Test
    void companyStateQueueFailureRecordsFirstErrorStopsClaimingAndDrains() {
        // One coarse lighting-area read joins the shared queue after the AC jobs.
        // The catalog phase reads the area page successfully; the queue phase read
        // (second occurrence of the same path) fails non-authorized.
        InFlight acReads = new InFlight();
        AtomicInteger areaPageReads = new AtomicInteger();
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            String query = candidate.url().getQuery() == null ? "" : candidate.url().getQuery();
            if (path.contains("/interactive/")) {
                return acReads.track(delayed(300,
                        json("{\"code\":200,\"content\":{\"data\":{\"O\":{\"val\":1}}}}")));
            }
            if (path.endsWith("/lighting/devicePage")) {
                return areaPageReads.incrementAndGet() == 1
                        ? Mono.just(json("[{\"id\":\"light-1\",\"hubDeviceId\":\"hub-light\",\"name\":\"灯\",\"switchStatus\":true}]"))
                        : Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
            }
            if (path.endsWith("/lighting/myAreaList")) {
                return query.contains("parentId") ? Mono.just(json("[]"))
                        : Mono.just(json("[{\"id\":\"light-root\",\"fullCode\":\"L-1\",\"name\":\"一层\"}]"));
            }
            return Mono.just(acCatalogResponse(path, query, 5));
        };
        IotAdapter adapter = companyAdapter(fixture);
        List<String> ids = new ArrayList<>(List.of("light-1"));
        java.util.stream.IntStream.range(0, 5).mapToObj(index -> "hub-ac-" + index).forEach(ids::add);

        assertThatThrownBy(() -> adapter.readDeviceStates(
                new RequestIdentity("Bearer company", "TENANT-1", IotSourceEnum.COMPANY), ids)
                .block(Duration.ofSeconds(10)))
                .isInstanceOf(UpstreamUnavailableException.class);
        // The six claimed jobs (five AC + the area read) all settle; nothing is retried
        // and no seventh read is claimed after the failure.
        assertThat(acReads.started.get()).isEqualTo(5);
        assertThat(acReads.finished.get()).isEqualTo(5);
        assertThat(areaPageReads.get()).isEqualTo(2);
    }


    @Test
    void environmentReadsShareTheBoundedQueueWithTheEnergyBatch() throws Exception {
        java.util.concurrent.CountDownLatch sixReached = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        AtomicInteger energyStarts = new AtomicInteger();
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            if (path.contains("/interactive/")) {
                started.incrementAndGet();
                return Mono.fromCallable(() -> {
                    int now = inFlight.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    if (now == 6) sixReached.countDown();
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("environment queue release latch timed out");
                    }
                    inFlight.decrementAndGet();
                    return json("{\"code\":200,\"content\":\"{\\\"temp\\\":\\\"25\\\",\\\"humidity\\\":\\\"55%\\\"}\"}");
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
            }
            if (path.endsWith("/energy/realTimeRead/page")) {
                energyStarts.incrementAndGet();
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"equipmentId\":\"meter-1\","
                        + "\"readTime\":\"2026-10-30 04:00:00\",\"ia\":1.5}],\"last\":true}}"));
            }
            if (path.endsWith("/iot-center/device/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"env-1\",\"code\":\"YQ-ENV-1\","
                        + "\"actionList\":[{\"id\":\"act-1\",\"actionType\":\"realTimeData\"}]}],\"last\":true}}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new IotGatewayRoutes("/office-scope", "/company-scope"),
                CALL_TIMEOUT, IotCallPolicy.passThrough());
        List<AuthorizedDevice> devices = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            devices.add(new AuthorizedDevice("env-" + index, "environment", "环境" + index, "一层", true,
                    null, "env-" + index, "hub-env-" + index, null, List.of(), List.of(),
                    Map.of("code", "YQ-ENV-1")));
        }
        devices.add(new AuthorizedDevice("meter-1", "energy", "总电表", "配电房", true));

        java.util.concurrent.CompletableFuture<DeviceStateReadResult> future = adapter.readObservationStates(
                new RequestIdentity("Bearer company", "TENANT-1", IotSourceEnum.COMPANY), devices).toFuture();

        try {
            // Six independent environment reads claim all lanes at once (the Node
            // runtime's serial per-device loop can never open this gate); the energy
            // batch waits for a freed lane instead of stacking a seventh read.
            assertThat(sixReached.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(started.get()).isEqualTo(6);
            assertThat(energyStarts.get()).isZero();
        } finally {
            release.countDown();
            try { future.get(15, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception cleanupFailure) { future.cancel(true); }
        }

        DeviceStateReadResult result = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(peak.get()).isEqualTo(6);
        assertThat(energyStarts.get()).isEqualTo(1);
        assertThat(result.states().keySet()).containsExactly(
                "env-0", "env-1", "env-2", "env-3", "env-4", "env-5", "meter-1");
        for (int index = 0; index < 6; index++) {
            assertThat(((Map<?, ?>) result.states().get("env-" + index)).get("temp")).isEqualTo(25.0);
        }
        assertThat(((Map<?, ?>) result.states().get("meter-1")).get("ia")).isEqualTo(1.5);
    }
    @Test
    void officeScopeFansOutSourcesBoundedAtSixWithSequentialSamePathPagination() {
        InFlight sourceReads = new InFlight();
        Map<String, List<Integer>> pagesByPath = new LinkedHashMap<>();
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            String query = candidate.url().getQuery() == null ? "" : candidate.url().getQuery();
            if (path.equals("/membership")) {
                return Mono.just(json("{\"code\":200,\"content\":["
                        + "{\"elementId\":\"office-a\",\"elementName\":\"A区\"},"
                        + "{\"elementId\":\"office-b\",\"elementName\":\"B区\"},"
                        + "{\"elementId\":\"office-c\",\"elementName\":\"C区\"},"
                        + "{\"elementId\":\"office-d\",\"elementName\":\"D区\"}]}"));
            }
            int page = Integer.parseInt(query.replaceAll(".*(?:^|&)page=(\\d+).*", "$1"));
            synchronized (pagesByPath) {
                pagesByPath.computeIfAbsent(path + "?" + query.replaceAll("page=\\d+|&page=\\d+|^page=\\d+&?", ""),
                        ignored -> new ArrayList<>()).add(page);
            }
            // Two pages per (source, office): page 0 carries the device, page 1 ends.
            String deviceId = (path.endsWith("/lighting") ? "light" : "ac") + "-"
                    + query.replaceAll(".*elementId=([a-z-]+).*", "$1");
            String device = "{\"id\":\"" + deviceId + "\",\"name\":\"设备\",\"hubDeviceId\":\"hub-" + deviceId + "\"}";
            String body = page == 0
                    ? "{\"code\":200,\"content\":{\"content\":[" + device + "],\"last\":false}}"
                    : "{\"code\":200,\"content\":{\"content\":[],\"last\":true}}";
            return sourceReads.track(delayed(100, json(body)));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.officeDirectory("/office-scope", null, "/membership", List.of(
                        new OfficeDeviceSource("my-office.lighting", "lighting", "/lighting", Map.of(), true),
                        new OfficeDeviceSource("my-office.ac", "ac", "/ac", Map.of(), true))),
                CALL_TIMEOUT, IotCallPolicy.passThrough());

        AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer office", "TENANT-1", IotSourceEnum.OFFICE))
                .block(Duration.ofSeconds(10));

        // Offices × sources fan out like the runtime Promise.all, bounded at six.
        assertThat(sourceReads.peak.get()).isGreaterThanOrEqualTo(3).isLessThanOrEqualTo(6);
        // Stable merge order: per source, per office — regardless of completion order.
        assertThat(scope.devices()).extracting(AuthorizedDevice::id).containsExactly(
                "light-office-a", "light-office-b", "light-office-c", "light-office-d",
                "ac-office-a", "ac-office-b", "ac-office-c", "ac-office-d");
        // Same-path pagination keeps its page dependency: page 0 strictly before page 1.
        synchronized (pagesByPath) {
            assertThat(pagesByPath.values()).hasSize(8).allSatisfy(pages -> assertThat(pages).containsExactly(0, 1));
        }
    }

    @Test
    void companySmartAreaTreeSiblingsAndLeafPagesFanOutBoundedAtSixInStableOrder() {
        InFlight siblingListCalls = new InFlight();
        InFlight leafPageReads = new InFlight();
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            String query = candidate.url().getQuery() == null ? "" : candidate.url().getQuery();
            if (path.endsWith("/lighting/myAreaList")) {
                if (query.contains("parentId")) {
                    return siblingListCalls.track(delayed(100, json("[]")));
                }
                return Mono.just(json(java.util.stream.IntStream.range(0, 8)
                        .mapToObj(index -> "{\"id\":\"n-" + index + "\",\"fullCode\":\"leaf-" + index
                                + "\",\"name\":\"区域" + index + "\"}")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"))));
            }
            if (path.endsWith("/lighting/devicePage")) {
                String area = query.replaceAll(".*areaFullCode=(leaf-\\d+).*", "$1");
                return leafPageReads.track(delayed(100, json("[{\"id\":\"light-"
                        + area.substring("leaf-".length()) + "\",\"hubDeviceId\":\"hub\",\"name\":\"灯\"}]")));
            }
            return Mono.just(acCatalogResponse(path, query, 0));
        };
        IotAdapter adapter = companyAdapter(fixture);

        AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer company", "TENANT-1", IotSourceEnum.COMPANY))
                .block(Duration.ofSeconds(10));

        // Sibling area-list calls and leaf device pages each stay within the
        // per-call fan-out bound of six (two separate call sites, not one global cap).
        assertThat(siblingListCalls.peak.get()).isGreaterThanOrEqualTo(3).isLessThanOrEqualTo(6);
        assertThat(leafPageReads.peak.get()).isGreaterThanOrEqualTo(3).isLessThanOrEqualTo(6);
        assertThat(scope.devices()).extracting(AuthorizedDevice::id)
                .containsExactly("light-0", "light-1", "light-2", "light-3",
                        "light-4", "light-5", "light-6", "light-7");
    }

    @Test
    void companySafetyDetailEnrichmentRunsExactlySixReadsConcurrentlyInRowOrder() throws Exception {
        java.util.concurrent.CountDownLatch sixReached = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            String query = candidate.url().getQuery() == null ? "" : candidate.url().getQuery();
            if (path.matches(".*/iot-center/device/hub-safety-\\d+$")) {
                started.incrementAndGet();
                String hub = path.substring(path.lastIndexOf('/') + 1);
                return Mono.fromCallable(() -> {
                    int now = inFlight.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    if (now == 6) sixReached.countDown();
                    // The old serial concat (and any sub-six cap) never opens this gate.
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new IllegalStateException("safety detail release latch timed out");
                    }
                    inFlight.decrementAndGet();
                    return json("{\"code\":200,\"content\":{\"id\":\"" + hub
                            + "\",\"name\":\"烟感" + hub.substring("hub-safety-".length())
                            + "\",\"installAddress\":\"机房\"}}");
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
            }
            if (path.endsWith("/perception/device/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":"
                        + java.util.stream.IntStream.range(0, 12)
                        .mapToObj(index -> "{\"id\":\"safety-" + index + "\",\"hubDeviceId\":\"hub-safety-"
                                + index + "\",\"type\":\"FIRE\"}")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                        + ",\"last\":true}}"));
            }
            return Mono.just(acCatalogResponse(path, query, 0));
        };
        IotAdapter adapter = companyAdapter(fixture);

        java.util.concurrent.CompletableFuture<AuthorizedDeviceScope> future = adapter.loadAuthorizedDeviceScope(
                new RequestIdentity("Bearer company", "TENANT-1", IotSourceEnum.COMPANY)).toFuture();

        try {
            assertThat(sixReached.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(started.get()).isEqualTo(6);
            assertThat(inFlight.get()).isEqualTo(6);
        } finally {
            release.countDown();
            try { future.get(15, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception cleanupFailure) { future.cancel(true); }
        }

        AuthorizedDeviceScope scope = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(peak.get()).isEqualTo(6);
        assertThat(started.get()).isEqualTo(12);
        // Bounded enrichment keeps the catalog row order stable.
        assertThat(scope.devices()).extracting(AuthorizedDevice::id).containsExactly(
                java.util.stream.IntStream.range(0, 12).mapToObj(index -> "safety-" + index).toArray(String[]::new));
    }

    private static IotAdapter companyAdapter(ExchangeFunction fixture) {
        return IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                RUNTIME_COMPANY, CALL_TIMEOUT, IotCallPolicy.passThrough());
    }

    /** Company AC catalog fixture; unrelated families answer 204 (empty). */
    private static ClientResponse acCatalogResponse(String path, String query) {
        return acCatalogResponse(path, query, 12);
    }

    private static ClientResponse acCatalogResponse(String path, String query, int acDevices) {
        if (path.endsWith("/ac/myAreaList")) {
            return query != null && query.contains("parentId") ? json("[]")
                    : json("[{\"id\":\"ac-root\",\"fullCode\":\"ac-leaf\",\"name\":\"一层\"}]");
        }
        if (path.endsWith("/ac/devicePage")) {
            return json(java.util.stream.IntStream.range(0, acDevices)
                    .mapToObj(index -> "{\"id\":\"ac-" + index + "\",\"hubDeviceId\":\"hub-ac-" + index
                            + "\",\"name\":\"空调" + index + "\"}")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]")));
        }
        if (path.endsWith("/iot-center/device/page") && query != null
                && query.contains("modelCode=YQ_AIR_CONDITION") && acDevices > 0) {
            return json("{\"code\":200,\"content\":{\"content\":"
                    + java.util.stream.IntStream.range(0, acDevices)
                    .mapToObj(index -> "{\"id\":\"hub-ac-" + index + "\",\"modelCode\":\"YQ_AIR_CONDITION\","
                            + "\"code\":\"provider-" + index + "\",\"actionList\":["
                            + "{\"id\":\"ac-control\",\"actionType\":\"airCondition\"},"
                            + "{\"id\":\"ac-detail-" + index + "\",\"actionType\":\"acDetail\"}]}")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"))
                    + ",\"last\":true}}");
        }
        return json("{\"code\":200,\"content\":{\"content\":[],\"last\":true}}");
    }

    private static Mono<ClientResponse> delayed(long millis, ClientResponse response) {
        return Mono.just(response).delayElement(Duration.ofMillis(millis));
    }

    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
