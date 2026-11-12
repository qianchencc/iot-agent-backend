package com.ehe.iotagent.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.DeviceStateReadResult;
import com.ehe.iotagent.iot.GatewayAuthorizationException;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Behavioral regressions for the migrated Runtime executeDevices concurrency:
 * independent hub groups dispatch through a bounded worker window (peak six,
 * the seventh group waits), one hub keeps its actions strictly ordered, and a
 * typed upstream failure stops workers from claiming further groups while the
 * in-flight calls are awaited before the failure is rethrown.
 */
class ActionDispatchConcurrencyContractTest {
    private static final RequestIdentity IDENTITY = new RequestIdentity("Bearer company-user", "TENANT-1", IotSourceEnum.COMPANY);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void independentHubsPeakAtSixConcurrentDispatchesAndTheSeventhGroupWaits() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<String> events = new ArrayList<>();
        CountDownLatch sixInFlight = new CountDownLatch(6);
        IotAdapter iot = mock(IotAdapter.class);
        when(iot.readDeviceStates(eq(IDENTITY), anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            Map<String, Object> states = new java.util.LinkedHashMap<>();
            for (String id : ids) states.put(id, Map.of("power", "on"));
            return Mono.just(new DeviceStateReadResult(states, nowIso()));
        });
        ActionCommandGateway gateway = gateway(candidate -> {
            String hub = hubOf(candidate.url().getPath());
            synchronized (events) {
                events.add("start:" + hub);
            }
            int current = inFlight.incrementAndGet();
            peak.accumulateAndGet(current, Math::max);
            sixInFlight.countDown();
            try {
                if (!sixInFlight.await(5, TimeUnit.SECONDS)) fail("六个独立设备未并发进入下发");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                fail("测试被中断");
            }
            inFlight.decrementAndGet();
            synchronized (events) {
                events.add("end:" + hub);
            }
            return Mono.just(ok());
        }, iot);

        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(acDevices(7)),
                actions(acDevices(7)));

        assertThat(peak.get()).as("在途峰值应为6").isEqualTo(6);
        assertThat(inFlight.get()).isZero();
        JsonNode receipts = parse(result.resultJson()).path("receipts");
        assertThat(receipts).hasSize(7);
        for (int index = 0; index < 7; index++) {
            assertThat(receipts.get(index).path("actionId").asText()).isEqualTo("action-" + (index + 1));
            assertThat(receipts.get(index).path("outcome").asText()).isEqualTo("ACCEPTED");
        }
        // The seventh group is only claimed after one of the first six finished.
        Integer firstEnd = null;
        Integer seventhStart = null;
        synchronized (events) {
            for (int index = 0; index < events.size(); index++) {
                String event = events.get(index);
                if (event.startsWith("end:") && firstEnd == null) firstEnd = index;
                if ("start:hub-7".equals(event)) seventhStart = index;
            }
        }
        assertThat(seventhStart).as("第七组也必须被下发").isNotNull();
        assertThat(firstEnd).as("至少有一组已完成").isNotNull();
        assertThat(seventhStart).as("第七组不得先于任一在途完成被领取").isGreaterThan(firstEnd);
    }

    @Test
    void sameHubActionsStayStrictlyOrderedWhileIndependentGroupsOverlap() {
        List<String> events = new ArrayList<>();
        AtomicInteger hubARequests = new AtomicInteger();
        CountDownLatch independentArrived = new CountDownLatch(1);
        IotAdapter iot = mock(IotAdapter.class);
        when(iot.readDeviceStates(eq(IDENTITY), anyList()))
                .thenReturn(Mono.just(new DeviceStateReadResult(Map.of(), nowIso())));
        ActionCommandGateway gateway = gateway(candidate -> {
            String hub = hubOf(candidate.url().getPath());
            int ordinal;
            if ("hub-A".equals(hub)) {
                ordinal = hubARequests.incrementAndGet();
            } else {
                ordinal = 1;
            }
            synchronized (events) {
                events.add("start:" + hub + "#" + ordinal);
            }
            if ("hub-A".equals(hub) && ordinal == 1) {
                try {
                    if (!independentArrived.await(5, TimeUnit.SECONDS)) fail("独立设备未并发下发");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    fail("测试被中断");
                }
            } else {
                independentArrived.countDown();
            }
            synchronized (events) {
                events.add("end:" + hub + "#" + ordinal);
            }
            return Mono.just(ok());
        }, iot);

        List<AuthorizedDevice> devices = List.of(
                ac("ac-1", "hub-A"), ac("ac-independent", "hub-B"), ac("ac-2", "hub-A"));
        ActionDispatchResult result = gateway.dispatch(IDENTITY, scope(devices), actions(devices));

        assertThat(result.status()).isEqualTo(ActionDraftStatusEnum.ACCEPTED);
        Integer firstAEnd = eventIndex(events, "end:hub-A#1");
        Integer secondAStart = eventIndex(events, "start:hub-A#2");
        Integer independentStart = eventIndex(events, "start:hub-B#1");
        assertThat(firstAEnd).isNotNull();
        assertThat(secondAStart).isNotNull();
        assertThat(independentStart).isNotNull();
        assertThat(secondAStart).as("同一hub的第二条指令必须等第一条完成").isGreaterThan(firstAEnd);
        // The independent group was claimed while the first hub-A command was
        // still waiting for it, so the strict order is not global serialization.
        assertThat(independentStart).as("独立设备应与同hub首条指令并发").isLessThan(firstAEnd);
        assertThat(hubARequests.get()).isEqualTo(2);
    }

    @Test
    void authorizationFailureStopsClaimingFurtherGroupsAndWaitsInFlight() throws Exception {
        RecordingExchange exchange = new RecordingExchange();
        exchange.failFastHub = "hub-fail";
        exchange.authorizationFailure = true;
        ActionCommandGateway gateway = gateway(exchange, mock(IotAdapter.class));
        List<AuthorizedDevice> devices = new ArrayList<>();
        devices.add(ac("ac-fail", "hub-fail"));
        for (int index = 1; index <= 5; index++) devices.add(ac("ac-slow-" + index, "hub-slow-" + index));
        for (int index = 1; index <= 4; index++) devices.add(ac("ac-tail-" + index, "hub-tail-" + index));

        FutureTask<ActionDispatchResult> dispatch = new FutureTask<>(() ->
                gateway.dispatch(IDENTITY, scope(devices), actions(devices)));
        Thread.ofVirtual().name("dispatch-caller").start(dispatch);
        assertThat(exchange.arrivals.await(5, TimeUnit.SECONDS))
                .as("前6组应全部进入下发").isTrue();
        assertThat(exchange.requestedHubs())
                .as("失败后不得领取后续组").hasSize(6)
                .doesNotContain("hub-tail-1", "hub-tail-2", "hub-tail-3", "hub-tail-4");
        Thread.sleep(200); // Let the failed response propagate before releasing successful in-flight calls.
        exchange.release.countDown();
        try {
            dispatch.get(10, TimeUnit.SECONDS);
            fail("授权失败必须抛出");
        } catch (java.util.concurrent.ExecutionException error) {
            assertThat(error.getCause()).isInstanceOfSatisfying(ActionDispatchException.class, partial -> {
                assertThat(partial.dispatchStarted()).as("accepted in-flight writes must never roll back to a retryable draft").isTrue();
                assertThat(partial.getCause()).isInstanceOf(GatewayAuthorizationException.class);
            });
        }
        assertThat(exchange.requestedHubs())
                .as("在途收口后仍不得领取后续组").hasSize(6);
        assertThat(exchange.slowCompleted.get())
                .as("在途调用必须被等待完成").isEqualTo(5);
    }

    @Test
    void uncertainUpstreamFailureStopsFurtherCommandsWithoutRetry() {
        AtomicInteger requests = new AtomicInteger();
        ExchangeFunction exchange = candidate -> {
            requests.incrementAndGet();
            return Mono.error(new ActionDispatchException("控制请求结果未知", true));
        };
        ActionCommandGateway gateway = gateway(exchange, mock(IotAdapter.class));
        // One physical device chain removes scheduler timing from the failure
        // ordering: after the first uncertain write, its next command must not run.
        List<AuthorizedDevice> devices = List.of(ac("ac-first", "hub-shared"), ac("ac-next", "hub-shared"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                gateway.dispatch(IDENTITY, scope(devices), actions(devices)))
                .isInstanceOfSatisfying(ActionDispatchException.class, failed ->
                        assertThat(failed.dispatchStarted()).isTrue());
        assertThat(requests.get()).as("未知结果不重试，也不下发同设备链中的下一条指令").isEqualTo(1);
    }

    @Test
    void callerInterruptStopsClaimingButAwaitsInFlightRequests() throws Exception {
        RecordingExchange exchange = new RecordingExchange();
        ActionCommandGateway gateway = gateway(exchange, mock(IotAdapter.class));
        List<AuthorizedDevice> devices = new ArrayList<>();
        for (int index = 1; index <= 6; index++) devices.add(ac("ac-hold-" + index, "hub-hold-" + index));
        for (int index = 1; index <= 4; index++) devices.add(ac("ac-tail-" + index, "hub-tail-" + index));

        FutureTask<ActionDispatchResult> dispatch = new FutureTask<>(() ->
                gateway.dispatch(IDENTITY, scope(devices), actions(devices)));
        Thread caller = Thread.ofVirtual().name("dispatch-caller").unstarted(dispatch);
        caller.start();
        assertThat(exchange.arrivals.await(5, TimeUnit.SECONDS))
                .as("六个独立设备应先全部在途").isTrue();
        caller.interrupt();
        assertThat(exchange.requestedHubs())
                .as("中断只停止领取后续组，不得取消在途请求").hasSize(6)
                .doesNotContain("hub-tail-1", "hub-tail-2", "hub-tail-3", "hub-tail-4");
        exchange.release.countDown();
        try {
            dispatch.get(10, TimeUnit.SECONDS);
            fail("中断必须以不确定收口抛出");
        } catch (java.util.concurrent.ExecutionException error) {
            assertThat(error.getCause()).isInstanceOfSatisfying(ActionDispatchException.class, interrupted ->
                    assertThat(interrupted.dispatchStarted()).isTrue());
        }
        assertThat(exchange.requestedHubs()).hasSize(6);
        assertThat(exchange.slowCompleted.get())
                .as("在途请求必须真实完成而非被取消").isEqualTo(6);
    }

    // ------------------------------------------------------------------ fakes

    private static final class RecordingExchange implements ExchangeFunction {
        final CountDownLatch arrivals = new CountDownLatch(6);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger slowCompleted = new AtomicInteger();
        private final List<String> hubs = new ArrayList<>();
        private String failFastHub;
        private boolean authorizationFailure;

        @Override
        public Mono<ClientResponse> exchange(org.springframework.web.reactive.function.client.ClientRequest request) {
            String hub = hubOf(request.url().getPath());
            synchronized (hubs) {
                hubs.add(hub);
            }
            arrivals.countDown();
            if (hub.equals(failFastHub)) {
                // Wait until all six groups are in flight so the typed failure
                // can never preempt a sibling worker's pre-dispatch check.
                try {
                    if (!arrivals.await(5, TimeUnit.SECONDS)) fail("前6组未并发进入下发");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    fail("测试被中断");
                }
                return Mono.just(authorizationFailure
                        ? ClientResponse.create(HttpStatus.FORBIDDEN).build()
                        : ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
            }
            try {
                if (!release.await(5, TimeUnit.SECONDS)) fail("在途下发未被释放");
                slowCompleted.incrementAndGet();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                fail("测试被中断");
            }
            return Mono.just(ok());
        }

        List<String> requestedHubs() {
            synchronized (hubs) {
                return List.copyOf(hubs);
            }
        }
    }

    private static String nowIso() {
        return java.time.Instant.now().toString();
    }

    private static Integer eventIndex(List<String> events, String event) {
        synchronized (events) {
            return events.contains(event) ? events.indexOf(event) : null;
        }
    }

    private static String hubOf(String path) {
        String marker = "/interactive/";
        int at = path.indexOf(marker);
        if (at < 0) return path;
        String rest = path.substring(at + marker.length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static List<AuthorizedDevice> acDevices(int count) {
        List<AuthorizedDevice> devices = new ArrayList<>();
        for (int index = 1; index <= count; index++) devices.add(ac("ac-" + index, "hub-" + index));
        return devices;
    }

    private static AuthorizedDevice ac(String id, String hub) {
        return new AuthorizedDevice(id, "ac", "空调" + id, "一层", false,
                id + "-control", id, hub, "1-2-3", List.of(), List.of(),
                Map.of("companyAcActionId", "air-condition-action"));
    }

    private static String actions(List<AuthorizedDevice> devices) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < devices.size(); index++) {
            if (index > 0) json.append(",");
            json.append("{\"id\":\"action-").append(index + 1)
                    .append("\",\"deviceId\":\"").append(devices.get(index).id())
                    .append("\",\"kind\":\"ac\",\"command\":{\"power\":\"on\"}}");
        }
        return json.append("]").toString();
    }

    private static ActionCommandGateway gateway(ExchangeFunction exchange, IotAdapter iot) {
        return ActionCommandGatewayFactory.webClient(MAPPER,
                WebClient.builder().exchangeFunction(exchange).build(), iot,
                Duration.ofSeconds(5), "/micro-api/api/office/app/device/lighting/down",
                "/micro-api/api/office/app/device/access/down", "/micro-api/api/office/app/device/ac/down",
                Duration.ZERO);
    }

    private static AuthorizedDeviceScope scope(List<AuthorizedDevice> devices) {
        return new AuthorizedDeviceScope() {
            @Override public RequestIdentity identity() { return IDENTITY; }
            @Override public IotSourceEnum source() { return IotSourceEnum.COMPANY; }
            @Override public List<AuthorizedDevice> devices() { return List.copyOf(devices); }
        };
    }

    private static ClientResponse ok() {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("{\"code\":0,\"content\":{\"code\":0,\"status\":\"ok\"}}").build();
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }
}
