package ltd.qianc.iotagent.iot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import ltd.qianc.iotagent.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Office 现在状态读取契约：a state read resolves the authorized scope once and then
 * touches only the scoped source pages its requested kinds and offices require —
 * no member-directory reread, no unrelated access/video/association requests, no
 * other offices. Unknown state stays null, never false. In-process
 * production-protocol exchange fixture; no real gateway or device is contacted.
 */
class OfficeDeviceStateReadContractTest {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private static IotAdapter officeAdapter(ExchangeFunction exchange) {
        return IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(exchange).build(),
                IotGatewayRoutes.officeDirectory("/office-scope", null, "/membership", List.of(
                        new OfficeDeviceSource("my-office.ac", "ac", "/ac", Map.of("type", "AirCold"), true),
                        new OfficeDeviceSource("my-office.lighting", "lighting", "/lighting", Map.of("type", "LIGHTING"), true),
                        new OfficeDeviceSource("my-office.access", "access", "/access",
                                Map.of("type", "SECURITY_PROTECTION", "subClass", "ACCESS"), true),
                        new OfficeDeviceSource("my-office.video", "video", "/video",
                                Map.of("type", "SECURITY_PROTECTION", "subClass", "VIDEO"), true),
                        new OfficeDeviceSource("office-association.ac", "ac", "/ac-assoc", Map.of(), false),
                        new OfficeDeviceSource("office-association.lighting", "lighting", "/lighting-assoc", Map.of(), false),
                        new OfficeDeviceSource("office-association.access", "access", "/access-assoc", Map.of(), false))),
                CALL_TIMEOUT, IotCallPolicy.passThrough());
    }

    private static OfficeStateGateway twoOfficeGateway() {
        OfficeStateGateway gateway = new OfficeStateGateway();
        gateway.serve("membership|-", "{\"code\":200,\"content\":["
                + "{\"elementId\":\"office-a\",\"elementName\":\"A区\"},"
                + "{\"elementId\":\"office-b\",\"elementName\":\"B区\"}]}");
        gateway.serve("lighting-assoc|-", "{\"code\":200,\"content\":["
                + "{\"id\":\"assoc-light\",\"hubDeviceId\":\"hub-light-a\",\"name\":\"关联灯\"}]}");
        return gateway;
    }

    @Test
    void lightingOnlyStateReadTouchesOnlyItsScopedOfficePageDespiteUnrelatedFailures() {
        OfficeStateGateway gateway = twoOfficeGateway();
        gateway.serve("lighting|office-a", "{\"code\":200,\"content\":["
                + "{\"id\":\"light-a\",\"name\":\"A区灯\",\"hubDeviceId\":\"hub-light-a\",\"switchStatus\":true},"
                + "{\"id\":\"light-unknown\",\"name\":\"A区走廊灯\",\"hubDeviceId\":\"hub-light-unknown\"}]}");
        gateway.serve("lighting|office-b", "{\"code\":200,\"content\":["
                + "{\"id\":\"light-b\",\"name\":\"B区灯\",\"hubDeviceId\":\"hub-light-b\",\"switchStatus\":false}]}");
        IotAdapter adapter = officeAdapter(gateway);
        RequestIdentity identity = new RequestIdentity("Bearer office-token", "TENANT-1", IotSourceEnum.OFFICE, "owner-state-1");

        adapter.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(10));

        // Unrelated sources (access, video, association, the other office's lighting)
        // and the member directory now fail; the lighting read must still succeed
        // without contacting any of them.
        gateway.strictOn("lighting|office-a");
        gateway.resetCalls();

        DeviceStateReadResult states = adapter.readDeviceStates(identity, List.of("light-a", "light-unknown"))
                .block(Duration.ofSeconds(10));

        assertThat(states.states().keySet()).containsExactly("light-a", "light-unknown");
        assertThat(states.states()).containsEntry("light-a", Boolean.TRUE).containsEntry("light-unknown", null);
        assertThat(gateway.calls()).containsExactly("lighting|office-a");
    }

    @Test
    void unauthorizedDeviceIsRejectedBeforeAnyStateNetworkRead() {
        OfficeStateGateway gateway = twoOfficeGateway();
        gateway.serve("lighting|office-a", "{\"code\":200,\"content\":["
                + "{\"id\":\"light-a\",\"name\":\"A区灯\",\"hubDeviceId\":\"hub-light-a\",\"switchStatus\":true}]}");
        IotAdapter adapter = officeAdapter(gateway);
        RequestIdentity identity = new RequestIdentity("Bearer office-token", "TENANT-1", IotSourceEnum.OFFICE, "owner-state-2");

        adapter.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(10));

        gateway.strictOn();
        gateway.resetCalls();

        assertThatThrownBy(() -> adapter.readDeviceStates(identity, List.of("light-a", "rogue-light"))
                .block(Duration.ofSeconds(10)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("DEVICE_OUTSIDE_SCOPE"));
        assertThat(gateway.calls()).isEmpty();
    }

    @Test
    void mixedLightingAndAcAcrossOfficesReadOnlyRequestedKindOfficePages() {
        OfficeStateGateway gateway = twoOfficeGateway();
        gateway.serve("lighting|office-a", "{\"code\":200,\"content\":["
                + "{\"id\":\"light-a\",\"name\":\"A区灯\",\"hubDeviceId\":\"hub-light-a\",\"switchStatus\":true}]}");
        gateway.serve("lighting|office-b", "{\"code\":200,\"content\":["
                + "{\"id\":\"light-b\",\"name\":\"B区灯\",\"hubDeviceId\":\"hub-light-b\",\"switchStatus\":false}]}");
        gateway.serve("ac|office-a", "{\"code\":200,\"content\":["
                + "{\"id\":\"ac-a\",\"name\":\"A区空调\",\"hubDeviceId\":\"hub-ac-a\",\"switchStatus\":false,\"temperature\":18}]}");
        gateway.serve("ac|office-b", "{\"code\":200,\"content\":["
                + "{\"id\":\"ac-b\",\"name\":\"B区空调\",\"hubDeviceId\":\"hub-ac-b\",\"switchStatus\":true,"
                + "\"temperature\":26,\"targetTemperature\":20},"
                + "{\"id\":\"ac-empty\",\"name\":\"B区闲置空调\",\"hubDeviceId\":\"hub-ac-empty\"}]}");
        IotAdapter adapter = officeAdapter(gateway);
        RequestIdentity identity = new RequestIdentity("Bearer office-token", "TENANT-1", IotSourceEnum.OFFICE, "owner-state-3");

        adapter.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(10));

        // office-a has an air conditioner, but no AC was requested there: its page,
        // the access/video/association directories and the member list must stay unread.
        gateway.strictOn("lighting|office-a", "lighting|office-b", "ac|office-b");
        gateway.resetCalls();

        DeviceStateReadResult states = adapter.readDeviceStates(identity,
                        List.of("light-a", "light-b", "ac-b", "ac-empty"))
                .block(Duration.ofSeconds(10));

        assertThat(states.states().keySet()).containsExactly("light-a", "light-b", "ac-b", "ac-empty");
        assertThat(states.states())
                .containsEntry("light-a", Boolean.TRUE)
                .containsEntry("light-b", Boolean.FALSE)
                .containsEntry("ac-empty", null);
        assertThat(gateway.calls()).containsExactlyInAnyOrder(
                "lighting|office-a", "lighting|office-b", "ac|office-b");
    }

    @Test
    void videoOnlineFactsComeFromScopedVideoPagesAndAccessStaysUnknownWithoutReads() {
        OfficeStateGateway gateway = twoOfficeGateway();
        gateway.serve("video|office-a", "{\"code\":200,\"content\":["
                + "{\"id\":\"cam-on\",\"name\":\"前门摄像头\",\"netStatus\":\"ON_LINE\"},"
                + "{\"id\":\"cam-off\",\"name\":\"后门摄像头\",\"netStatus\":\"OFF_LINE\"},"
                + "{\"id\":\"cam-init\",\"name\":\"走廊摄像头\",\"netStatus\":\"INIT\"}]}");
        gateway.serve("access|office-a", "{\"code\":200,\"content\":["
                + "{\"id\":\"door-1\",\"name\":\"大门门禁\",\"hubDeviceId\":\"hub-door-1\"}]}");
        IotAdapter adapter = officeAdapter(gateway);
        RequestIdentity identity = new RequestIdentity("Bearer office-token", "TENANT-1", IotSourceEnum.OFFICE, "owner-state-4");

        adapter.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(10));

        gateway.strictOn("video|office-a");
        gateway.resetCalls();

        DeviceStateReadResult states = adapter.readDeviceStates(identity,
                        List.of("cam-on", "cam-off", "cam-init", "door-1"))
                .block(Duration.ofSeconds(10));

        assertThat(states.states().keySet()).containsExactly("cam-on", "cam-off", "cam-init", "door-1");
        assertThat(states.states())
                .containsEntry("cam-on", Boolean.TRUE)
                .containsEntry("cam-off", Boolean.FALSE)
                .containsEntry("cam-init", null)
                .containsEntry("door-1", null);
        assertThat(gateway.calls()).containsExactly("video|office-a");
    }

    @Test
    void stateSourcePermissionDenialPropagatesWithoutMemberReread() {
        OfficeStateGateway gateway = twoOfficeGateway();
        gateway.serve("lighting|office-a", "{\"code\":200,\"content\":["
                + "{\"id\":\"light-a\",\"name\":\"A区灯\",\"hubDeviceId\":\"hub-light-a\",\"switchStatus\":true}]}");
        IotAdapter adapter = officeAdapter(gateway);
        RequestIdentity identity = new RequestIdentity("Bearer office-token", "TENANT-1", IotSourceEnum.OFFICE, "owner-state-5");

        adapter.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(10));

        gateway.fail("lighting|office-a", 403);
        gateway.strictOn("lighting|office-a");
        gateway.resetCalls();

        assertThatThrownBy(() -> adapter.readDeviceStates(identity, List.of("light-a"))
                .block(Duration.ofSeconds(10)))
                .isInstanceOf(GatewayAuthorizationException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("IOT_GATEWAY_FORBIDDEN"));
        assertThat(gateway.calls()).containsExactly("lighting|office-a");
    }

    @Test
    void independentOfficeStatePagesFanOutOnOneBoundedQueue() {
        OfficeStateGateway gateway = new OfficeStateGateway().delayEach(Duration.ofMillis(150));
        StringBuilder offices = new StringBuilder();
        for (int office = 1; office <= 7; office++) {
            offices.append(office == 1 ? "" : ",").append("{\"elementId\":\"office-").append(office)
                    .append("\",\"elementName\":\"区域").append(office).append("\"}");
            gateway.serve("lighting|office-" + office, "{\"code\":200,\"content\":["
                    + "{\"id\":\"light-" + office + "\",\"name\":\"灯" + office + "\",\"switchStatus\":true}]}");
        }
        gateway.serve("membership|-", "{\"code\":200,\"content\":[" + offices + "]}");
        IotAdapter adapter = officeAdapter(gateway);
        RequestIdentity identity = new RequestIdentity("Bearer office-token", "TENANT-1", IotSourceEnum.OFFICE, "owner-state-6");

        adapter.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(20));

        gateway.resetCalls();
        gateway.resetPeakInFlight();

        List<String> requested = IntStream.rangeClosed(1, 7).mapToObj(office -> "light-" + office).toList();
        DeviceStateReadResult states = adapter.readDeviceStates(identity, requested)
                .block(Duration.ofSeconds(20));

        assertThat(states.states()).hasSize(7);
        assertThat(states.states().values()).containsOnly(Boolean.TRUE);
        assertThat(gateway.calls()).containsExactlyInAnyOrder(
                "lighting|office-1", "lighting|office-2", "lighting|office-3", "lighting|office-4",
                "lighting|office-5", "lighting|office-6", "lighting|office-7");
        // Independent (source, office) pages share the bounded six-lane read queue:
        // parallel like the runtime fan-out, never unbounded.
        assertThat(gateway.peakInFlight()).isGreaterThanOrEqualTo(2).isLessThanOrEqualTo(6);
    }

    /**
     * Serves one JSON page per "path|elementId" key ("-" when the source carries no
     * office context). After {@link #strictOn} every unexpected request fails, so a
     * read that touches an unrelated source, office or the member directory cannot
     * pass silently. Tracks concurrency for the bounded-queue contract.
     */
    private static final class OfficeStateGateway implements ExchangeFunction {
        private final Map<String, String> pages = new ConcurrentHashMap<>();
        private final Map<String, Integer> failures = new ConcurrentHashMap<>();
        private final List<String> calls = new ArrayList<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private volatile Set<String> allowed;
        private volatile Duration delay = Duration.ZERO;

        OfficeStateGateway serve(String key, String body) {
            pages.put(key, body);
            return this;
        }

        OfficeStateGateway strictOn(String... keys) {
            allowed = Set.copyOf(Arrays.asList(keys));
            return this;
        }

        OfficeStateGateway fail(String key, int status) {
            failures.put(key, status);
            return this;
        }

        OfficeStateGateway delayEach(Duration value) {
            delay = value;
            return this;
        }

        List<String> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        void resetCalls() {
            synchronized (calls) {
                calls.clear();
            }
        }

        int peakInFlight() {
            return peak.get();
        }

        void resetPeakInFlight() {
            peak.set(0);
        }

        @Override
        public Mono<ClientResponse> exchange(ClientRequest request) {
            String key = keyOf(request);
            synchronized (calls) {
                calls.add(key);
            }
            Integer status = failures.get(key);
            if (status != null) {
                return tracked(Mono.just(ClientResponse.create(HttpStatus.valueOf(status)).build()));
            }
            Set<String> expected = allowed;
            if (expected != null && !expected.contains(key)) {
                return tracked(Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY).build()));
            }
            String body = pages.getOrDefault(key, "{\"code\":204,\"content\":null}");
            Mono<ClientResponse> response = Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(body).build());
            Duration each = delay;
            if (!each.isZero()) response = response.delayElement(each);
            return tracked(response);
        }

        private Mono<ClientResponse> tracked(Mono<ClientResponse> response) {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            return response.doFinally(signal -> inFlight.decrementAndGet());
        }

        private static String keyOf(ClientRequest request) {
            String query = request.url().getQuery() == null ? "" : request.url().getQuery();
            String office = Arrays.stream(query.split("&"))
                    .filter(param -> param.startsWith("elementId="))
                    .map(param -> param.substring("elementId=".length()))
                    .findFirst().orElse("-");
            String path = request.url().getPath();
            return (path.startsWith("/") ? path.substring(1) : path) + "|" + office;
        }
    }
}
