package ltd.qianc.iotagent.iot;

import java.time.Duration;
import java.util.List;
import ltd.qianc.iotagent.error.BusinessException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void reusesVerifiedReadScopeBrieflyWithoutCrossingIdentityKeys() {
        AtomicInteger calls = new AtomicInteger();
        ExchangeFunction fixture = candidate -> {
            calls.incrementAndGet();
            return Mono.just(json("{\"code\":200,\"content\":[{\"id\":\"ac-1\",\"kind\":\"ac\",\"name\":\"会议室空调\",\"installAddress\":\"A-101\"}]}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new IotGatewayRoutes("/office-scope", "/company-scope"),
                Duration.ofSeconds(1),
                IotCallPolicy.passThrough());
        RequestIdentity identity = new RequestIdentity("Bearer office-token", "TENANT-1", IotSource.OFFICE);

        adapter.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(1));
        adapter.loadAuthorizedDeviceScope(identity).block(Duration.ofSeconds(1));

        assertThat(calls).hasValue(1);
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
    void officeMembershipIdsSurviveScopeDeduplication() {
        AtomicReference<java.util.List<String>> officeIds = new AtomicReference<>(new java.util.ArrayList<>());
        ExchangeFunction fixture = candidate -> {
            var url = candidate.url();
            if (url.getPath().equals("/membership")) {
                return Mono.just(json("{\"code\":200,\"content\":[{\"elementId\":\"office-a\",\"elementName\":\"一号楼\"},{\"elementId\":\"office-b\",\"elementName\":\"二号楼\"}]}"));
            }
            if (url.getPath().equals("/ac")) {
                officeIds.get().add(url.getQuery());
                return Mono.just(json("{\"code\":200,\"content\":[{\"id\":\"ac-shared\",\"name\":\"共享空调\",\"installAddress\":\"机房\"}]}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.officeDirectory("/office-scope", null, "/membership",
                        java.util.List.of(new OfficeDeviceSource("fixture-office", "ac", "/ac", java.util.Map.of(), true))),
                Duration.ofSeconds(1),
                IotCallPolicy.passThrough());

        AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer office-token", "TENANT-1", IotSource.OFFICE))
                .block(Duration.ofSeconds(1));

        AuthorizedDevice device = scope.device("ac-shared").orElseThrow();
        assertThat(device.officeIds()).containsExactly("office-a", "office-b");
        assertThat(officeIds.get()).allMatch(query -> query.contains("elementId=office-"));
    }

    @Test
    void officePhysicalJoinKeepsScopedIdentityAndDropsAssociationOnlyDevices() {
        ExchangeFunction fixture = candidate -> {
            var url = candidate.url();
            if (url.getPath().equals("/membership")) {
                return Mono.just(json("{\"code\":200,\"content\":[{\"elementId\":\"office-a\",\"elementName\":\"26楼运营办公区\"}]}"));
            }
            if (url.getPath().equals("/lighting")) {
                return Mono.just(json("{\"code\":200,\"content\":[{\"id\":\"scoped-light\",\"hubDeviceId\":\"hub-1\",\"name\":\"灯\",\"installAddress\":\"26楼\"}]}"));
            }
            if (url.getPath().equals("/lighting-assoc")) {
                return Mono.just(json("{\"code\":200,\"content\":["
                        + "{\"id\":\"association-light\",\"hubDeviceId\":\"hub-1\",\"name\":\"灯\"},"
                        + "{\"id\":\"association-only\",\"hubDeviceId\":\"hub-2\",\"name\":\"关联灯\"}]}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.officeDirectory("/office-scope", null, "/membership", List.of(
                        new OfficeDeviceSource("my-office.lighting", "lighting", "/lighting", Map.of(), true),
                        new OfficeDeviceSource("office-association.lighting", "lighting", "/lighting-assoc", Map.of(), false))),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer office-token", "TENANT-1", IotSource.OFFICE))
                .block(Duration.ofSeconds(1));

        assertThat(scope.devices()).extracting(AuthorizedDevice::id).containsExactly("scoped-light");
        AuthorizedDevice device = scope.device("scoped-light").orElseThrow();
        assertThat(device.controlRef()).isEqualTo("association-light");
        assertThat(device.officeIds()).containsExactly("office-a");
        assertThat(device.sourceKeys()).containsExactly("my-office.lighting", "office-association.lighting");
        assertThat(scope.device("association-only")).isEmpty();
    }

    @Test
    void candidateSetsPersistFullMembershipAndRejectDevicesOutsideTheirSet() {
        java.util.Map<String, DeviceCandidateRecord> saved = new java.util.concurrent.ConcurrentHashMap<>();
        DeviceCandidateSetStore store = new DeviceCandidateSetStore() {
            @Override public DeviceCandidateRecord save(DeviceCandidateRecord record) {
                saved.put(record.candidateSetId(), record);
                return record;
            }
            @Override public java.util.Optional<DeviceCandidateRecord> find(String id) {
                return java.util.Optional.ofNullable(saved.get(id));
            }
        };
        ExchangeFunction fixture = candidate -> {
            var url = candidate.url();
            if (url.getPath().equals("/membership")) {
                return Mono.just(json("{\"code\":200,\"content\":[{\"elementId\":\"office-a\",\"elementName\":\"A区\"},{\"elementId\":\"office-b\",\"elementName\":\"B区\"}]}"));
            }
            if (url.getPath().equals("/lighting")) {
                String office = url.getQuery().contains("elementId=office-a") ? "A区" : "B区";
                String id = office.equals("A区") ? "light-a" : "light-b";
                return Mono.just(json("{\"code\":200,\"content\":[{\"id\":\"" + id + "\",\"hubDeviceId\":\"hub-" + id
                        + "\",\"name\":\"灯\",\"installAddress\":\"" + office + "\"}]}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.officeDirectory("/office-scope", null, "/membership", List.of(
                        new OfficeDeviceSource("my-office.lighting", "lighting", "/lighting", Map.of(), true))),
                Duration.ofSeconds(1), IotCallPolicy.passThrough(), store);
        RequestIdentity identity = new RequestIdentity("Bearer office-token", "TENANT-1", IotSource.OFFICE, "owner-1");

        DeviceCandidateSet set = adapter.findDeviceCandidates(identity,
                        new DeviceCandidateQuery("lighting", List.of(), "A区", List.of(), List.of(), null, "office-a"),
                        new DeviceCandidateBinding("conversation-1"))
                .block(Duration.ofSeconds(1));

        assertThat(set.devices()).extracting(AuthorizedDevice::id).containsExactly("light-a");
        DeviceCandidateRecord record = saved.get(set.candidateSetId());
        assertThat(record).isNotNull();
        assertThat(record.query().regionId()).isEqualTo("office-a");
        assertThat(record.conversationId()).isEqualTo("conversation-1");
        assertThat(record.members()).extracting(AuthorizedDevice::id).containsExactly("light-a");
        assertThat(record.complete()).isTrue();

        assertThatThrownBy(() -> adapter.inspectDeviceCapabilities(identity,
                        new DeviceCapabilityRequest(List.of(new DeviceCapabilityRequest.DeviceCapabilitySelection(
                                set.candidateSetId(), List.of("light-b")))), "conversation-1")
                .block(Duration.ofSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("INVALID_DEVICE_REFERENCE"));

        DeviceStateReadResult states = adapter.readDeviceStates(identity,
                        new DeviceCapabilityRequest(List.of(new DeviceCapabilityRequest.DeviceCapabilitySelection(
                                set.candidateSetId(), List.of("light-a")))), "conversation-1")
                .block(Duration.ofSeconds(1));
        assertThat(states.states()).containsKey("light-a");
        assertThatThrownBy(() -> adapter.readDeviceStates(identity,
                        new DeviceCapabilityRequest(List.of(new DeviceCapabilityRequest.DeviceCapabilitySelection(
                                set.candidateSetId(), List.of("light-b")))), "conversation-1")
                .block(Duration.ofSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("INVALID_DEVICE_REFERENCE"));
    }

    @Test
    void companyCandidateMeterStateReadUsesOnlyTheEnergyFamily() {
        java.util.List<String> requests = new java.util.ArrayList<>();
        ExchangeFunction fixture = candidate -> {
            var url = candidate.url();
            synchronized (requests) { requests.add(url.getPath() + "?" + (url.getQuery() == null ? "" : url.getQuery())); }
            String path = url.getPath();
            if (path.endsWith("/energy/equipment/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":["
                        + "{\"id\":\"meter-1\",\"equipmentName\":\"总电表\",\"areaName\":\"配电房\"},"
                        + "{\"id\":\"meter-2\",\"equipmentName\":\"分电表\",\"areaName\":\"二层\"}],\"last\":true}}"));
            }
            if (path.endsWith("/energy/realTimeRead/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":["
                        + "{\"equipmentId\":\"meter-1\",\"readTime\":\"2026-10-30 04:00:00\",\"ia\":6.9,\"ib\":0,\"ic\":0},"
                        + "{\"equipmentId\":\"meter-2\",\"readTime\":\"2026-10-30 04:00:00\",\"ia\":2.1,\"ib\":1.2,\"ic\":1.3},"
                        + "{\"equipmentId\":\"meter-1\",\"readTime\":\"2026-10-30 04:05:00\",\"ia\":7.7,\"ib\":0,\"ic\":0}"
                        + "],\"last\":true}}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        java.util.Map<String, DeviceCandidateRecord> saved = new java.util.concurrent.ConcurrentHashMap<>();
        DeviceCandidateSetStore store = new DeviceCandidateSetStore() {
            @Override public DeviceCandidateRecord save(DeviceCandidateRecord record) {
                saved.put(record.candidateSetId(), record);
                return record;
            }
            @Override public java.util.Optional<DeviceCandidateRecord> find(String id) {
                return java.util.Optional.ofNullable(saved.get(id));
            }
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page",
                        "/micro-api/api/energy/equipment/page",
                        "/micro-api/api/perception/device/page",
                        "/micro-api/api/iot-center/device",
                        "/micro-api/api/office/app/device",
                        "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage",
                        "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough(), store);
        RequestIdentity identity = new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY,
                "owner-1");
        AuthorizedDeviceScope catalog = adapter.loadAuthorizedDeviceScope(identity)
                .block(Duration.ofSeconds(1));
        AuthorizedDevice meter1 = catalog.device("meter-1").orElseThrow();
        AuthorizedDevice meter2 = catalog.device("meter-2").orElseThrow();
        DeviceCandidateRecord set = new DeviceCandidateRecord("set-meter", "conversation-meter",
                "TENANT-1", "owner-1", IotSource.COMPANY, "snapshot",
                new DeviceCandidateQuery("energy", List.of(), null, List.of(), List.of(), null, null),
                List.of(meter1, meter2), true, 2, "2026-10-30T09:00:00Z", "exact");
        store.save(set);
        synchronized (requests) { requests.clear(); }

        DeviceStateReadResult states = adapter.readDeviceStates(identity,
                        new DeviceCapabilityRequest(List.of(new DeviceCapabilityRequest.DeviceCapabilitySelection(
                                "set-meter", List.of("meter-1", "meter-2")))), "conversation-meter")
                .block(Duration.ofSeconds(1));

        assertThat(((Map<?, ?>) states.states().get("meter-1")).get("ia")).isEqualTo(7.7);
        assertThat(((Map<?, ?>) states.states().get("meter-2")).get("ia")).isEqualTo(2.1);
        assertThat(states.states().get("meter-1")).isNotEqualTo(states.states().get("meter-2"));
        synchronized (requests) {
            assertThat(requests).allMatch(request -> request.contains("/energy/"));
            assertThat(requests).anyMatch(request -> request.contains("/energy/equipment/page"));
            assertThat(requests).anyMatch(request -> request.contains("/energy/realTimeRead/page"));
            assertThat(requests).noneMatch(request -> request.contains("/perception")
                    || request.contains("/myAreaList") || request.contains("/ac/") || request.contains("/iot-center"));
        }
    }

    @Test
    void companyEnergyScopeReadsOnlyMetersAndKeepsUnauthorizedEmpty() {
        java.util.List<String> requests = new java.util.ArrayList<>();
        ExchangeFunction fixture = candidate -> {
            var url = candidate.url();
            synchronized (requests) { requests.add(url.getPath()); }
            if (url.getPath().endsWith("/energy/equipment/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":["
                        + "{\"id\":\"meter-1\",\"equipmentName\":\"总电表\",\"areaName\":\"配电房\"},"
                        + "{\"id\":\"meter-2\",\"equipmentName\":\"分电表\",\"areaName\":\"二层\"}],\"last\":true}}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page",
                        "/micro-api/api/energy/equipment/page",
                        "/micro-api/api/perception/device/page",
                        "/micro-api/api/iot-center/device",
                        "/micro-api/api/office/app/device",
                        "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage",
                        "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());
        RequestIdentity identity = new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY,
                "owner-1");

        AuthorizedDeviceScope scope = adapter.loadAuthorizedEnergyScope(identity).block(Duration.ofSeconds(1));

        assertThat(scope.identity()).isEqualTo(identity);
        assertThat(scope.source()).isEqualTo(IotSource.COMPANY);
        assertThat(scope.devices()).extracting(AuthorizedDevice::kind).containsOnly("energy");
        assertThat(scope.devices()).extracting(AuthorizedDevice::id).containsExactly("meter-1", "meter-2");
        assertThat(scope.device("meter-missing")).isEmpty();
        synchronized (requests) {
            assertThat(requests).containsExactly("/micro-api/api/energy/equipment/page");
        }
        assertThatThrownBy(() -> adapter.loadAuthorizedEnergyScope(
                        new RequestIdentity("Bearer office-token", "TENANT-1", IotSource.OFFICE, "owner-1"))
                .block(Duration.ofSeconds(1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).code()).isEqualTo("IOT_SOURCE_UNAVAILABLE"));
    }

    @Test
    void companyCatalogUsesRuntimeShapesAndMergesAcLocations() {
        AtomicReference<java.util.List<String>> requests = new AtomicReference<>(new java.util.ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean validBinding = new java.util.concurrent.atomic.AtomicBoolean(true);
        ExchangeFunction fixture = candidate -> {
            var url = candidate.url();
            requests.get().add(url.getPath() + "?" + url.getQuery());
            String query = url.getQuery() == null ? "" : url.getQuery();
            String path = url.getPath();
            if (path.endsWith("/myAreaList")) {
                if (query.contains("parentId")) return Mono.just(json("[]"));
                return Mono.just(json("[{\"id\":\"leaf-1\",\"fullCode\":\"leaf-1\",\"name\":\"一层\"},{\"id\":\"leaf-2\",\"fullCode\":\"leaf-2\",\"name\":\"二层\"}]"));
            }
            if (path.endsWith("/devicePage")) {
                if (path.contains("/lighting/")) return Mono.just(json("[{\"id\":\"light-1\",\"hubDeviceId\":\"hub-light\",\"name\":\"灯\",\"installAddress\":\"A\"}]"));
                if (path.contains("/ac/") && query.contains("leaf-1")) return Mono.just(json("[{\"id\":\"ac-1\",\"hubDeviceId\":\"hub-ac\",\"name\":\"空调\",\"installAddress\":\"B\"}]"));
                if (path.contains("/ac/") && query.contains("leaf-2")) return Mono.just(json("[{\"id\":\"ac-duplicate\",\"hubDeviceId\":\"hub-ac\",\"name\":\"空调\",\"installAddress\":\"B\"}]"));
                return Mono.just(json("[]"));
            }
            if (path.endsWith("/iot-center/device/page") && query.contains("modelCode=YQ-ENV-SENSOR")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"env-1\",\"code\":\"ENV-1\",\"name\":\"环境传感器\",\"installAddress\":\"C\",\"actionList\":[]}],\"last\":true}}"));
            }
            if (path.endsWith("/energy/equipment/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"energy-1\",\"equipmentName\":\"总电表\",\"areaName\":\"配电房\",\"feeSchemeName\":\"峰谷方案\",\"energyType\":\"electricity\"}],\"last\":true}}"));
            }
            if (path.endsWith("/perception/device/page") && query.contains("type=FIRE")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"safety-1\",\"hubDeviceId\":\"hub-safety\",\"type\":\"FIRE\",\"deviceName\":\"烟感\",\"installAddress\":\"D\",\"subtype\":\"smoke\",\"currentDevice\":{\"name\":\"烟感\",\"location\":\"D\"}},{\"id\":\"safety-detail\",\"hubDeviceId\":\"hub-detail\",\"type\":\"FIRE\"}],\"last\":true}}"));
            }
            if (path.endsWith("/iot-center/device/hub-detail")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"id\":\"hub-detail\",\"name\":\"核验烟感\",\"installAddress\":\"E\"}}"));
            }
            if (path.endsWith("/iot-center/device/page")) {
                String code = validBinding.get() ? "\"code\":\"provider-ac\"," : "";
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"hub-ac\",\"modelCode\":\"YQ_AIR_CONDITION\"," + code + "\"actionList\":[{\"id\":\"ac-control\",\"actionType\":\"airCondition\"},{\"id\":\"ac-detail\",\"actionType\":\"acDetail\"}]}],\"last\":true}}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page",
                        "/micro-api/api/energy/equipment/page",
                        "/micro-api/api/perception/device/page",
                        "/micro-api/api/iot-center/device",
                        "/micro-api/api/office/app/device",
                        "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage",
                        "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1),
                IotCallPolicy.passThrough());

        AuthorizedDeviceScope scope = adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY))
                .block(Duration.ofSeconds(2));

        assertThat(scope).isNotNull();
        assertThat(scope.devices()).extracting(AuthorizedDevice::kind)
                .contains("environment", "energy", "safety", "lighting", "ac");
        assertThat(scope.device("energy-1").orElseThrow().name()).isEqualTo("总电表");
        assertThat(scope.device("safety-1").orElseThrow().hubDeviceId()).isEqualTo("hub-safety");
        assertThat(scope.device("safety-detail").orElseThrow().name()).isEqualTo("核验烟感");
        assertThat(scope.device("energy-1").orElseThrow().metadata())
                .containsEntry("feeSchemeName", "峰谷方案")
                .containsEntry("energyType", "electricity");
        assertThat(scope.device("hub-ac").orElseThrow().metadata()).containsEntry("companyAcActionId", "ac-control");
        assertThat(scope.device("hub-ac").orElseThrow().locationPaths()).containsExactly(
                java.util.List.of("一层"), java.util.List.of("二层"));
        assertThat(requests.get()).anyMatch(value -> value.contains("modelCode=YQ-ENV-SENSOR"));
        assertThat(requests.get()).anyMatch(value -> value.contains("/energy/equipment/page"));
        assertThat(requests.get()).anyMatch(value -> value.contains("/perception/device/page") && value.contains("type=FIRE"));
        validBinding.set(false);
        AuthorizedDeviceScope missingBinding = adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY))
                .block(Duration.ofSeconds(2));
        assertThat(missingBinding.device("hub-ac").orElseThrow().observationOnly()).isTrue();
        assertThat(missingBinding.device("hub-ac").orElseThrow().controlRef()).isNull();
        DeviceCandidateSet lighting = adapter.findDeviceCandidates(
                        new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY),
                        new DeviceCandidateQuery("lighting", List.of(), "一层", List.of(), List.of(), null, null))
                .block(Duration.ofSeconds(2));
        assertThat(lighting.devices()).extracting(AuthorizedDevice::id).containsExactly("light-1");
        DeviceCandidateSet companyEnergy = adapter.findDeviceCandidates(
                        new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY),
                        new DeviceCandidateQuery("energy", List.of(), "公司", List.of(), List.of(), null, null))
                .block(Duration.ofSeconds(2));
        assertThat(companyEnergy.devices()).extracting(AuthorizedDevice::id).containsExactly("energy-1");
        StepVerifier.create(adapter.findDeviceCandidates(
                        new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY),
                        new DeviceCandidateQuery("energy", List.of(), null, List.of(), List.of(), null, null)))
                .expectError(UpstreamUnavailableException.class).verify();
    }

    @Test
    void acBindingPaginationWithoutLastAndFullPagesFailsClosedAfterTenPages() {
        String rows = java.util.stream.IntStream.range(0, 100)
                .mapToObj(index -> "{\"id\":\"binding-" + index + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        String fullPage = "{\"code\":200,\"content\":{\"content\":[" + rows + "]}}";
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            String query = candidate.url().getQuery() == null ? "" : candidate.url().getQuery();
            if (path.endsWith("/myAreaList")) return Mono.just(json("[]"));
            if (path.endsWith("/iot-center/device/page") && query.contains("modelCode=YQ_AIR_CONDITION")) return Mono.just(json(fullPage));
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page",
                        "/micro-api/api/energy/equipment/page",
                        "/micro-api/api/perception/device/page",
                        null,
                        "/micro-api/api/office/app/device",
                        "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage",
                        "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1),
                IotCallPolicy.passThrough());

        StepVerifier.create(adapter.loadAuthorizedDeviceScope(
                        new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY)))
                .expectError(UpstreamUnavailableException.class)
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void observationStatesUseRealtimeActionShadowFallbackAndRealtimeRead() {
        java.util.List<String> requests = new java.util.ArrayList<>();
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            requests.add(candidate.method() + " " + path + "?" + (candidate.url().getQuery() == null ? "" : candidate.url().getQuery()));
            if (path.endsWith("/iot-center/device/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"env-1\",\"code\":\"YQ-ENV-1\",\"actionList\":[{\"id\":\"action-1\",\"actionType\":\"realTimeData\"}]}],\"last\":true}}"));
            }
            if (path.contains("/interactive/")) {
                return Mono.just(json("{\"code\":200,\"content\":\"{\\\"temp\\\":\\\"25\\\",\\\"humidity\\\":\\\"55%\\\"}\"}"));
            }
            if (path.endsWith("/energy/realTimeRead/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"equipmentId\":\"meter-1\",\"readTime\":\"2026-10-30 04:00:00\",\"ia\":1.5,\"ib\":2.0,\"ic\":2.5}],\"last\":true}}"));
            }
            if (path.endsWith("/home/yq/env/data")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"temp\":\"25.6\",\"humidity\":\"55.1\"}}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new IotGatewayRoutes("/office-scope", "/company-scope"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());
        AuthorizedDevice env = new AuthorizedDevice("env-1", "environment", "环境监测", "一层", true,
                null, "env-1", "hub-env-1", null, java.util.List.of(), java.util.List.of(), java.util.Map.of("code", "YQ-ENV-1"));
        AuthorizedDevice meter = new AuthorizedDevice("meter-1", "energy", "总电表", "配电房", true);

        DeviceStateReadResult result = adapter.readObservationStates(
                new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY),
                java.util.List.of(env, meter)).block(Duration.ofSeconds(2));

        assertThat(result.states().get("env-1").toString()).contains("25").contains("device");
        assertThat(result.states().get("meter-1").toString()).contains("1.5").contains("2026-10-29T20:00");
        assertThat(requests).anyMatch(value -> value.startsWith("POST") && value.contains("/interactive/hub-env-1/action-1/down"));
        assertThat(requests).noneMatch(value -> value.contains("home/yq/env/data"));
    }

    @Test
    void observationStatesFallBackToEnvironmentShadowWhenActionReadFails() {
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            if (path.endsWith("/iot-center/device/page")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"content\":[{\"id\":\"env-1\",\"code\":\"YQ-ENV-1\",\"actionList\":[{\"id\":\"action-1\",\"actionType\":\"realTimeData\"}]}],\"last\":true}}"));
            }
            if (path.contains("/interactive/")) {
                return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
            }
            if (path.endsWith("/home/yq/env/data")) {
                return Mono.just(json("{\"code\":200,\"content\":{\"temp\":\"25.6\",\"humidity\":\"55.1\"}}"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                new IotGatewayRoutes("/office-scope", "/company-scope"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());
        AuthorizedDevice env = new AuthorizedDevice("env-1", "environment", "环境监测", "一层", true,
                null, "env-1", "hub-env-1", null, java.util.List.of(), java.util.List.of(), java.util.Map.of("code", "YQ-ENV-1"));

        DeviceStateReadResult result = adapter.readObservationStates(
                new RequestIdentity("Bearer company-token", "TENANT-1", IotSource.COMPANY),
                java.util.List.of(env)).block(Duration.ofSeconds(2));

        assertThat(result.states().get("env-1").toString()).contains("25.6").contains("shadow");
    }

    @Test
    void companyLightsInOneAreaUseOneCoherentObservation() {
        var snapshot = new java.util.concurrent.atomic.AtomicInteger();
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            String query = candidate.url().getQuery() == null ? "" : candidate.url().getQuery();
            if (path.endsWith("/lighting/myAreaList") && !query.contains("parentId")) {
                return Mono.just(json("[{\"id\":\"lighting-root\",\"fullCode\":\"L-1\",\"name\":\"一层\"}]"));
            }
            if (path.endsWith("/lighting/myAreaList")) return Mono.just(json("[]"));
            if (path.endsWith("/lighting/devicePage")) {
                boolean on = snapshot.getAndIncrement() % 2 == 0;
                return Mono.just(json("[{\"id\":\"light-a\",\"hubDeviceId\":\"hub-a\",\"name\":\"A灯\",\"switchStatus\":"
                        + on + "},{\"id\":\"light-b\",\"hubDeviceId\":\"hub-b\",\"name\":\"B灯\",\"switchStatus\":"
                        + on + "}]"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page", "/micro-api/api/energy/equipment/page",
                        "/micro-api/api/perception/device/page", "/micro-api/api/iot-center/device",
                        "/micro-api/api/office/app/device", "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage", "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());
        DeviceStateReadResult result = adapter.readDeviceStates(
                new RequestIdentity("Bearer company-user", "TENANT-1", IotSource.COMPANY),
                List.of("light-a", "light-b")).block(Duration.ofSeconds(2));
        assertThat(result.states()).containsOnlyKeys("light-a", "light-b");
        assertThat(result.states().get("light-a")).isInstanceOf(Boolean.class)
                .isEqualTo(result.states().get("light-b"));
    }

    @Test
    void companyControlReadbackUsesMonitorAndLightingLeafScope() {
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            String query = candidate.url().getQuery() == null ? "" : candidate.url().getQuery();
            if (path.endsWith("/lighting/myAreaList") && !query.contains("parentId")) {
                return Mono.just(json("[{\"id\":\"lighting-root\",\"fullCode\":\"L-1\",\"name\":\"一层\"}]"));
            }
            if (path.endsWith("/lighting/myAreaList")) return Mono.just(json("[]"));
            if (path.endsWith("/lighting/devicePage")) {
                return Mono.just(json("[{\"id\":\"light-1\",\"hubDeviceId\":\"hub-light\",\"name\":\"大厅灯\",\"switchStatus\":true}]"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page", "/micro-api/api/energy/equipment/page",
                        "/micro-api/api/perception/device/page", "/micro-api/api/iot-center/device",
                        "/micro-api/api/office/app/device", "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage", "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        DeviceStateReadResult result = adapter.readDeviceStates(
                new RequestIdentity("Bearer company-user", "TENANT-1", IotSource.COMPANY), List.of("light-1"))
                .block(Duration.ofSeconds(2));

        assertThat(result.states()).containsEntry("light-1", true);
    }
    @Test
    void companyVideoStateReadsProduceRuntimeObservationBlockWithoutStartingStream() {
        ExchangeFunction fixture = candidate -> {
            String path = candidate.url().getPath();
            String query = candidate.url().getQuery() == null ? "" : candidate.url().getQuery();
            if (path.endsWith("/monitor/myAreaList") && !query.contains("parentId")) {
                return Mono.just(json("[{\"id\":\"monitor-root\",\"fullCode\":\"M-1\",\"name\":\"一层\"}]"));
            }
            if (path.endsWith("/monitor/myAreaList")) return Mono.just(json("[]"));
            if (path.endsWith("/monitor/devicePage")) {
                return Mono.just(json("[{\"id\":\"camera-1\",\"hubDeviceId\":\"hub-camera\",\"name\":\"摄像头\",\"netStatus\":\"ON_LINE\"}]"));
            }
            return Mono.just(json("{\"code\":204,\"content\":null}"));
        };
        IotAdapter adapter = IotAdapterFactory.webClient(
                WebClient.builder().exchangeFunction(fixture).build(),
                IotGatewayRoutes.runtimeCompany("/office-scope", null,
                        "/micro-api/api/iot-center/device/page", "/micro-api/api/energy/equipment/page",
                        "/micro-api/api/perception/device/page", "/micro-api/api/iot-center/device",
                        "/micro-api/api/office/app/device", "/micro-api/api/office/app/device/ac/myAreaList",
                        "/micro-api/api/office/app/device/ac/devicePage", "/micro-api/api/iot-center/device/page"),
                Duration.ofSeconds(1), IotCallPolicy.passThrough());

        DeviceStateReadResult result = adapter.readDeviceStates(
                new RequestIdentity("Bearer company-user", "TENANT-1", IotSource.COMPANY), List.of("camera-1"))
                .block(Duration.ofSeconds(2));

        assertThat(result.states()).containsEntry("camera-1", true);
        assertThat(result.blocks()).containsExactly(Map.<String, Object>of(
                "type", "video",
                "devices", List.<Map<String, Object>>of(Map.of(
                        "deviceId", "camera-1", "name", "摄像头", "location", "摄像头", "online", true))));
    }
    private static ClientResponse json(String body) {
        return ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build();
    }
}
