package ltd.qianc.iotagent.iot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.auth.AccountPrincipalVerifier;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.schema.SchemaMigrations;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

class CompanyDirectoryBehaviorTest {
    private static final String KEY = "ab".repeat(32);
    private static final AuthorizedDevice FIRST = new AuthorizedDevice("fixture-meter-a", "energy", "测试电表甲", "测试位置甲", true);
    private static final AuthorizedDevice SECOND = new AuthorizedDevice("fixture-meter-b", "energy", "测试电表乙", "测试位置乙", true);
    private static final RequestIdentity IDENTITY = identity("Bearer fixture-alpha", "fixture-tenant");

    @Test void fullDisplayCatalogIsSharedWithinTenantButNeverExpandsAuthorizedMembers() {
        try (Fixture f = new Fixture()) {
            f.catalogDevices = List.of(
                    new DeviceCatalogSource.Entry("fixture-display-ac", "测试空调", "ac", "测试会议室"),
                    new DeviceCatalogSource.Entry("fixture-display-light", "测试照明", "lighting", ""));
            assertPreparing(() -> f.directory.readCatalog(IDENTITY));
            assertEquals(0, f.catalogCalls.get());
            f.directory.synchronizeDue();
            assertEquals(List.of("fixture-display-ac", "fixture-display-light"), ids(f.directory.readCatalog(IDENTITY)));
            RequestIdentity colleague = identity("Bearer fixture-other", "fixture-tenant");
            assertEquals(ids(f.directory.readCatalog(IDENTITY)), ids(f.directory.readCatalog(colleague)));
            assertEquals(1, f.catalogCalls.get());
            assertEquals(List.of("fixture-meter-a"), ids(f.directory.read(IDENTITY)));
            assertEquals(List.of(FIRST), f.localGateway().loadAuthorizedDeviceScope(IDENTITY).block().devices());
            assertPreparing(() -> f.directory.readCatalog(identity("Bearer fixture-alpha", "fixture-other-tenant")));
            RequestIdentity forged = new RequestIdentity(IDENTITY.authorization(), IDENTITY.tenantCode(), IotSourceEnum.COMPANY, colleague.ownerKey());
            assertEquals(403, assertThrows(BusinessException.class, () -> f.directory.readCatalog(forged)).httpStatus());
            f.restart();
            f.catalogFailure = new IllegalStateException("fixture source offline");
            assertEquals(List.of("fixture-display-ac", "fixture-display-light"), ids(f.directory.readCatalog(IDENTITY)));
            assertEquals(1, f.catalogCalls.get());
        }
    }

    @Test void catalogBatchReplacementPreservesOldDataOnFailureAndCanPublishTrueEmpty() {
        try (Fixture f = new Fixture()) {
            f.catalogDevices = List.of(new DeviceCatalogSource.Entry("fixture-display-a", "测试设备甲", "ac", "旧位置"));
            assertPreparing(() -> f.directory.readCatalog(IDENTITY));
            f.catalogFailure = new IllegalStateException("fixture first collection failed");
            f.directory.synchronizeDue();
            assertEquals("IOT_SCOPE_UNAVAILABLE", assertThrows(BusinessException.class, () -> f.directory.readCatalog(IDENTITY)).code());
            f.catalogFailure = null;
            f.clock.advance(61);
            f.directory.synchronizeDue();
            Object synchronizedAt = f.directory.readCatalog(IDENTITY).get("synchronizedAt");
            f.catalogDevices = List.of(new DeviceCatalogSource.Entry("fixture-display-b", "测试设备乙", "ac", "新位置"),
                    new DeviceCatalogSource.Entry("fixture-invalid", "x".repeat(513), "ac", ""));
            f.clock.advance(61);
            f.directory.synchronizeDue();
            Map<String, Object> stale = f.directory.readCatalog(IDENTITY);
            assertEquals(List.of("fixture-display-a"), ids(stale));
            assertEquals(synchronizedAt, stale.get("synchronizedAt"));
            assertEquals("stale", ((Map<?, ?>) stale.get("cache")).get("status"));
            f.catalogDevices = List.of(new DeviceCatalogSource.Entry("fixture-display-b", "测试设备乙", "ac", "新位置"));
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals(List.of("fixture-display-b"), ids(f.directory.readCatalog(IDENTITY)));
            Map<?, ?> changed = (Map<?, ?>) ((List<?>) f.directory.readCatalog(IDENTITY).get("devices")).getFirst();
            assertEquals("新位置", changed.get("location"));
            f.catalogDevices = List.of();
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals(List.of(), ids(f.directory.readCatalog(IDENTITY)));
            assertEquals("fresh", ((Map<?, ?>) f.directory.readCatalog(IDENTITY).get("cache")).get("status"));
        }
    }

    @Test void catalogChangesKeepSameIdKindsSeparateAndPublishCompleteMetadata() {
        try (Fixture f = new Fixture()) {
            var light = new DeviceCatalogSource.Entry("fixture-shared-id", "测试照明", "lighting", "原位置");
            var ac = new DeviceCatalogSource.Entry("fixture-shared-id", "测试空调", "ac", "原位置");
            f.catalogDevices = List.of(light, ac);
            assertPreparing(() -> f.directory.readCatalog(IDENTITY));
            f.directory.synchronizeDue();
            f.clock.advance(61);
            f.catalogDevices = List.of(light, new DeviceCatalogSource.Entry(ac.id(), "新空调名称", ac.kind(), "新位置"));
            f.directory.synchronizeDue();
            assertEquals(List.of(
                    Map.of("id", ac.id(), "name", "新空调名称", "kind", "ac", "location", "新位置"),
                    Map.of("id", light.id(), "name", light.name(), "kind", "lighting", "location", "原位置")),
                    f.directory.readCatalog(IDENTITY).get("devices"));
            f.clock.advance(61);
            f.catalogDevices = List.of(light);
            f.directory.synchronizeDue();
            Map<String, Object> view = f.directory.readCatalog(IDENTITY);
            assertEquals(List.of(Map.of("id", light.id(), "name", light.name(), "kind", "lighting", "location", "原位置")), view.get("devices"));
            assertEquals(f.clock.instant().toString(), view.get("synchronizedAt"));
            assertEquals(List.of(FIRST), f.directory.loadBatch(IDENTITY).scope().devices());
        }
    }

    @Test void localReadsAndAgentRecoverFromCacheLossAndRestartWithoutCollecting() {
        try (Fixture f = new Fixture()) {
            assertPreparing(() -> f.directory.read(IDENTITY));
            assertEquals(0, f.calls.get());
            f.directory.synchronizeDue();
            assertEquals(List.of("fixture-meter-a"), ids(f.directory.read(IDENTITY)));
            assertNull(f.directory.read(IDENTITY).get("observedAt"));
            f.failure = new IllegalStateException("fixture upstream is offline");
            f.restart();
            assertEquals(List.of("fixture-meter-a"), ids(f.directory.read(IDENTITY)));
            IotAdapter agent = IotAdapterFactory.localReads(f.upstream, () -> f.directory, () -> null, IotAdapterFactory.candidateSets(f.jdbc, f.mapper));
            assertEquals("fixture-meter-a", agent.loadAuthorizedDeviceScope(IDENTITY).block().devices().getFirst().id());
            assertEquals(1, f.calls.get());
        }
    }

    @Test void failedBatchDoesNotPublishPartialCatalogOrAdvanceSuccessTime() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            Object success = f.directory.status(IDENTITY).get("lastSuccessAt");
            f.devices = List.of(SECOND, new AuthorizedDevice("fixture-invalid", "energy", "x".repeat(513), "", true));
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals("FAILED", f.directory.status(IDENTITY).get("state"));
            assertEquals(success, f.directory.status(IDENTITY).get("lastSuccessAt"));
            assertEquals("1", f.directory.status(IDENTITY).get("version"));
            f.restart();
            assertEquals(List.of("fixture-meter-a"), ids(f.directory.read(IDENTITY)));
            assertEquals("stale", ((Map<?, ?>) f.directory.read(IDENTITY).get("cache")).get("status"));
        }
    }

    @Test void completeEmptyBatchIsDifferentFromMissingCoverage() {
        try (Fixture f = new Fixture()) {
            assertPreparing(() -> f.directory.read(IDENTITY));
            f.devices = List.of();
            f.directory.synchronizeDue();
            assertEquals(List.of(), ids(f.directory.read(IDENTITY)));
            assertEquals("READY", f.directory.status(IDENTITY).get("state"));
        }
    }

    @Test void principalAndTenantCannotReadAnExistingScopeWithTheSameSourceDeviceId() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            RequestIdentity otherOwner = identity("Bearer fixture-other", "fixture-tenant");
            RequestIdentity otherTenant = identity("Bearer fixture-alpha", "fixture-other-tenant");
            assertPreparing(() -> f.directory.read(otherOwner));
            assertPreparing(() -> f.directory.read(otherTenant));
            f.devices = List.of(SECOND);
            f.directory.synchronizeDue();
            assertEquals(List.of("fixture-meter-b"), ids(f.directory.read(otherOwner)));
            assertEquals(List.of("fixture-meter-b"), ids(f.directory.read(otherTenant)));
            assertEquals(List.of("fixture-meter-a"), ids(f.directory.read(IDENTITY)));
            RequestIdentity forged = new RequestIdentity(IDENTITY.authorization(), IDENTITY.tenantCode(), IotSourceEnum.COMPANY, otherOwner.ownerKey());
            assertEquals(403, assertThrows(BusinessException.class, () -> f.directory.read(forged)).httpStatus());
        }
    }

    @Test void credentialRotationCannotPublishAnOlderInflightGrantAndSyncDoesNotReenter() throws Exception {
        try (Fixture f = new Fixture()) {
            assertPreparing(() -> f.directory.read(IDENTITY));
            f.entered = new CountDownLatch(1);
            f.release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> { try { f.directory.synchronizeDue(); } catch (Throwable error) { failure.set(error); } });
            worker.start();
            try {
                assertTrue(f.entered.await(5, TimeUnit.SECONDS));
                f.directory.synchronizeDue();
                assertEquals(1, f.calls.get());
                RequestIdentity rotated = identity("Bearer fixture-narrow", "fixture-tenant");
                assertPreparing(() -> f.directory.read(rotated));
                f.devices = List.of(SECOND);
                f.release.countDown();
                worker.join(5000);
                assertFalse(worker.isAlive());
                assertNull(failure.get());
                assertPreparing(() -> f.directory.read(rotated));
                f.directory.synchronizeDue();
                assertEquals(List.of("fixture-meter-b"), ids(f.directory.read(rotated)));
                assertEquals("1", f.directory.status(rotated).get("version"));
            } finally {
                f.release.countDown();
                worker.join(5000);
            }
        }
    }

    @Test void explicitDenialBlocksCachedContentAndPausesDataCollectionUntilReadmission() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            f.failure = new GatewayAuthorizationException(403);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals(403, assertThrows(BusinessException.class, () -> f.directory.read(IDENTITY)).httpStatus());
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals(2, f.calls.get());
            f.snapshots.admit(snapshotIdentity(IDENTITY));
            f.failure = null;
            f.devices = List.of(SECOND);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals(List.of("fixture-meter-b"), ids(f.directory.read(IDENTITY)));
        }
    }

    @Test void missingDatabaseDoesNotFallBackToUpstreamOrReturnCachedSuccess() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            var office = new RequestIdentity(IDENTITY.authorization(), IDENTITY.tenantCode(), IotSourceEnum.OFFICE, IDENTITY.ownerKey());
            assertPreparing(() -> f.directory.loadBatch(office));
            f.directory.synchronizeDue();
            f.jdbc.execute("SHUTDOWN");
            assertEquals("IOT_SCOPE_UNAVAILABLE", assertThrows(BusinessException.class, () -> f.directory.read(IDENTITY)).code());
            assertEquals("IOT_SCOPE_UNAVAILABLE", assertThrows(BusinessException.class, () -> f.directory.loadBatch(office)).code());
            assertEquals(2, f.calls.get());
        }
    }

    @Test void dueRegistrationSurvivesRestartWithoutAnotherPageVisit() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            f.restart();
            f.devices = List.of(SECOND);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals(List.of("fixture-meter-b"), ids(f.directory.read(IDENTITY)));
            assertEquals("2", f.directory.status(IDENTITY).get("version"));
        }
    }

    @Test void agentKeepsReadOnlyBindingsPathsAndFingerprintWithoutExposingBindingsInHttp() {
        try (Fixture f = new Fixture()) {
            AuthorizedDevice device = new AuthorizedDevice("fixture-sensor", "safety", "测试烟感", "", true,
                    "private-control", "office-ref", "hub-ref", "area-ref",
                    List.of(List.of("测试园区", "测试楼层")), List.of("fixture-office"),
                    Map.of("subtype", "smoke", "sourceKeys", List.of("fixture.safety")));
            f.devices = List.of(device);
            assertPreparing(() -> f.directory.read(IDENTITY));
            f.directory.synchronizeDue();
            IotAdapter agent = IotAdapterFactory.localReads(f.upstream, () -> f.directory, () -> null, IotAdapterFactory.candidateSets(f.jdbc, f.mapper));
            AuthorizedDevice restored = agent.loadAuthorizedDeviceScope(IDENTITY).block().devices().getFirst();
            assertEquals(device, restored);
            assertEquals(device.catalogFingerprint(), restored.catalogFingerprint());
            Map<?, ?> publicDevice = (Map<?, ?>) ((List<?>) f.directory.read(IDENTITY).get("devices")).getFirst();
            assertEquals("smoke", publicDevice.get("subtype"));
            assertFalse(publicDevice.containsKey("controlRef"));
            assertFalse(publicDevice.containsKey("metadata"));
        }
    }

    @Test void changedBindingsRelationsAndSourceOrderRemainOneCompleteOfficeBatch() {
        try (Fixture f = new Fixture()) {
            var office = new RequestIdentity(IDENTITY.authorization(), IDENTITY.tenantCode(), IotSourceEnum.OFFICE, IDENTITY.ownerKey());
            var before = new AuthorizedDevice("fixture-light", "lighting", "测试照明", "", true,
                    "fixture-control", "fixture-office-ref", "fixture-hub", "fixture-area",
                    List.of(List.of("测试园区", "旧楼层")), List.of("room-a", "room-b"), Map.of("mode", "old"));
            f.devices = List.of(FIRST, before, SECOND);
            f.regions = List.of(new OfficeRegion("room-a", "区域甲"), new OfficeRegion("room-b", "区域乙"));
            assertPreparing(() -> f.directory.loadBatch(office));
            f.directory.synchronizeDue();
            var changed = new AuthorizedDevice(before.id(), before.kind(), before.name(), "", false,
                    null, "fixture-new-office-ref", "fixture-new-hub", null,
                    List.of(List.of("测试园区", "新楼层"), List.of("备用位置")), List.of("room-b", "room-a"),
                    Map.of("mode", "new", "port", 2));
            f.devices = List.of(SECOND, changed, FIRST);
            f.regions = List.of(new OfficeRegion("room-b", "新区乙"), new OfficeRegion("room-a", "区域甲"));
            f.clock.advance(61);
            f.directory.synchronizeDue();
            var batch = f.directory.loadBatch(office);
            assertEquals(f.devices, batch.scope().devices());
            assertEquals(f.regions, batch.regions());
            assertEquals(List.of(changed), batch.forRegion("room-b").devices());
            assertEquals(changed.bindingFingerprint(), batch.scope().device(changed.id()).orElseThrow().bindingFingerprint());
            f.clock.advance(61);
            f.directory.synchronizeDue();
            f.restart();
            f.failure = new IllegalStateException("fixture source offline");
            assertEquals(f.devices, f.directory.loadBatch(office).scope().devices());
            assertEquals(f.regions, f.directory.loadBatch(office).regions());
        }
    }

    @Test void externalDenialAndReadmissionRequireANewDirectoryGrantBeforeReturningOldData() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            f.snapshots.revoke(snapshotIdentity(IDENTITY), new GatewayAuthorizationException(403));
            f.snapshots.admit(snapshotIdentity(IDENTITY));
            assertPreparing(() -> f.directory.read(IDENTITY));
            f.devices = List.of(SECOND);
            f.directory.synchronizeDue();
            assertEquals(List.of("fixture-meter-b"), ids(f.directory.read(IDENTITY)));
        }
    }

    @Test void verifiedSameCredentialCanRegisterAgainAfterCipherLoss() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            f.jdbc.update("UPDATE local_sync_scopes SET credential_cipher='damaged-fixture-cipher'");
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals("SYNC_CREDENTIAL_UNAVAILABLE", f.directory.status(IDENTITY).get("failureCode"));
            assertPreparing(() -> f.directory.read(IDENTITY));
            f.directory.synchronizeDue();
            assertEquals(List.of("fixture-meter-a"), ids(f.directory.read(IDENTITY)));
            assertEquals("READY", f.directory.status(IDENTITY).get("state"));
        }
    }

    @Test void officeRegionsIncludingEmptyOnesAndMembersShareOneDurableIsolatedBatch() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            var office = new RequestIdentity(IDENTITY.authorization(), IDENTITY.tenantCode(), IotSourceEnum.OFFICE, IDENTITY.ownerKey());
            f.regions = List.of(new OfficeRegion("fixture-room", "测试区域"), new OfficeRegion("fixture-empty", "测试空区域"));
            var member = new AuthorizedDevice("fixture-office-light", "lighting", "测试照明", "", false,
                    "fixture-control", "fixture-office-device", "fixture-hub", null,
                    List.of(), List.of("fixture-room"), Map.of("sourceKeys", List.of("my-office.lighting")));
            f.devices = List.of(member);
            assertPreparing(() -> f.directory.loadBatch(office));
            assertEquals(1, f.calls.get());
            f.directory.synchronizeDue();
            f.restart();
            f.failure = new IllegalStateException("fixture source stopped");
            var local = f.directory.loadBatch(office);
            assertEquals(f.regions, local.regions());
            assertEquals(List.of(member), local.forRegion("fixture-room").devices());
            assertEquals(List.of(), local.forRegion("fixture-empty").devices());
            assertEquals("FOCUS_REGION_UNAVAILABLE", assertThrows(BusinessException.class,
                    () -> local.forRegion("fixture-other-room")).code());
            assertEquals(List.of("fixture-meter-a"), ids(f.directory.read(IDENTITY)));
            assertEquals(2, f.calls.get());
            f.failure = null;
            f.regions = List.of(new OfficeRegion("fixture-new-room", "x".repeat(513)));
            f.devices = List.of(SECOND);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            var afterFailure = f.directory.loadBatch(office);
            assertEquals(local.regions(), afterFailure.regions());
            assertEquals(local.scope().devices(), afterFailure.scope().devices());
            assertEquals("1", f.directory.status(office).get("version"));
        }
    }

    @Test void officePaginationDriftRetainsThePublishedBatchAndRecoveryRescansMembership() {
        try (Fixture f = new Fixture()) {
            var phase = new AtomicInteger();
            var identity = new RequestIdentity(IDENTITY.authorization(), IDENTITY.tenantCode(), IotSourceEnum.OFFICE, IDENTITY.ownerKey());
            var web = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
                String body;
                if (request.url().getPath().equals("/membership") && phase.get() == 1) {
                    boolean first = request.url().getQuery().contains("page=0");
                    body = first
                            ? "{\"code\":200,\"content\":{\"content\":[{\"elementId\":\"room-a\"},{\"elementId\":\"room-b\"}],\"last\":false,\"number\":0,\"totalElements\":4}}"
                            : "{\"code\":200,\"content\":{\"content\":[{\"elementId\":\"room-a\"},{\"elementId\":\"room-c\"}],\"last\":true,\"number\":1,\"totalElements\":4}}";
                } else if (request.url().getPath().equals("/membership")) {
                    String room = phase.get() == 0 ? "room-a" : "room-b";
                    body = "{\"code\":200,\"content\":[{\"elementId\":\"" + room + "\",\"elementName\":\"Fixture Room\"}]}";
                } else {
                    String device = phase.get() == 0 ? "light-a" : "light-b";
                    body = "{\"code\":200,\"content\":[{\"id\":\"" + device + "\",\"name\":\"Fixture Light\"}]}";
                }
                return Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK)
                        .header("Content-Type", "application/json").body(body).build());
            }).build();
            f.sourceOverride = IotAdapterFactory.webClient(web,
                    IotGatewayRoutes.officeDirectory("/scope", null, "/membership", List.of(
                            new OfficeDeviceSource("my-office.lighting", "lighting", "/lighting", Map.of(), true))),
                    java.time.Duration.ofSeconds(1), IotCallPolicy.passThrough());
            assertPreparing(() -> f.directory.loadBatch(identity));
            f.directory.synchronizeDue();
            assertEquals(List.of("light-a"), f.directory.loadBatch(identity).forRegion("room-a").devices().stream().map(AuthorizedDevice::id).toList());
            phase.set(1);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals("FAILED", f.directory.status(identity).get("state"));
            assertEquals("1", f.directory.status(identity).get("version"));
            assertEquals(List.of("light-a"), f.directory.loadBatch(identity).forRegion("room-a").devices().stream().map(AuthorizedDevice::id).toList());
            phase.set(2);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals("READY", f.directory.status(identity).get("state"));
            assertEquals("2", f.directory.status(identity).get("version"));
            var recovered = f.directory.loadBatch(identity);
            assertEquals(List.of("room-b"), recovered.regions().stream().map(OfficeRegion::id).toList());
            assertEquals(List.of("light-b"), recovered.scope().devices().stream().map(AuthorizedDevice::id).toList());
        }
    }

    @Test void persistedCandidateHandlesKeepCompleteMembershipAcrossRestartButCannotRestoreRevokedMembers() {
        try (Fixture f = new Fixture()) {
            f.devices = java.util.stream.IntStream.range(0, 65).mapToObj(index ->
                    new AuthorizedDevice("fixture-meter-" + index, "energy", "测试电表" + index, "测试位置", true)).toList();
            assertPreparing(() -> f.directory.loadBatch(IDENTITY));
            f.directory.synchronizeDue();
            new SchemaMigrations(f.jdbc.getDataSource()).ensureDeviceCandidateSets();
            f.jdbc.execute("ALTER TABLE device_candidate_sets ALTER COLUMN payload_json VARCHAR(1000000) NOT NULL");
            var store = IotAdapterFactory.candidateSets(f.jdbc, f.mapper);
            var networkCalls = new AtomicInteger();
            var client = org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request -> {
                networkCalls.incrementAndGet();
                return Mono.error(new IllegalStateException("ordinary candidate read used source"));
            }).build();
            var gateway = IotAdapterFactory.localReads(IotAdapterFactory.webClient(client, new IotGatewayRoutes("/office", "/company"),
                    java.time.Duration.ofSeconds(1), IotCallPolicy.passThrough(), store), () -> f.directory, () -> null, store);
            var query = new DeviceCandidateQuery("energy", List.of(), "全部", List.of(), List.of(), null, null);
            var found = gateway.findDeviceCandidates(IDENTITY, query, new DeviceCandidateBinding("fixture-conversation")).block();
            assertEquals(65, found.totalMatches());
            assertEquals(50, found.devices().size());
            assertFalse(found.complete());
            assertEquals(f.devices, found.allDevices());
            assertEquals("s1", found.handle());
            var original = gateway.resolveDeviceCandidateSet(IDENTITY, "s1", "fixture-conversation").block();
            assertEquals(f.directory.loadScope(IDENTITY).catalogFingerprint(), original.catalogFingerprint());
            f.restart();
            f.devices = java.util.stream.Stream.concat(f.devices.stream(), java.util.stream.Stream.of(SECOND)).toList();
            f.clock.advance(61);
            f.directory.synchronizeDue();
            var restored = gateway.resolveDeviceCandidateSet(IDENTITY, "s1", "fixture-conversation").block();
            assertEquals(original.members(), restored.members());
            assertEquals(original.resolveDeviceRef("d1_1"), restored.resolveDeviceRef("d1_1"));
            assertEquals("CANDIDATE_SET_NOT_FOUND", assertThrows(BusinessException.class, () ->
                    gateway.resolveDeviceCandidateSet(IDENTITY, "s1", "fixture-other-conversation").block()).code());
            f.devices = f.devices.subList(1, f.devices.size());
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals("CANDIDATE_SET_STALE", assertThrows(BusinessException.class, () ->
                    gateway.resolveDeviceCandidateSet(IDENTITY, "s1", "fixture-conversation").block()).code());
            assertEquals(0, networkCalls.get());
        }
    }

    @Test void currentStatesSurviveRestartAndUpstreamFailureWithoutReadTriggeredCollection() {
        try (Fixture f = new Fixture()) {
            assertPreparing(() -> f.observations.read(IDENTITY, List.of(FIRST.id())));
            assertEquals(0, f.stateCalls.get());
            f.publishInitial();
            DeviceStateReadResult initial = f.observations.read(IDENTITY, List.of(FIRST.id()));
            assertEquals(new java.math.BigDecimal("12345678901234567890.1234567890123456789"),
                    ((Map<?, ?>) initial.states().get(FIRST.id())).get("totalActivePower"));
            assertEquals(ObservationStatusEnum.AVAILABLE, initial.status());
            assertEquals(f.clock.instant().toString(), initial.observedAt());
            f.failure = new IllegalStateException("fixture source offline");
            f.clock.advance(61);
            f.directory.synchronizeDue();
            f.restart();
            DeviceStateReadResult restored = f.localGateway().readDeviceStates(IDENTITY, List.of(FIRST.id())).block();
            assertEquals(initial.states(), restored.states());
            assertEquals(initial.observedAt(), restored.observedAt());
            assertEquals(initial.cache().get("updatedAt"), restored.cache().get("updatedAt"));
            assertEquals("stale", restored.cache().get("status"));
            assertEquals(1, f.stateCalls.get());
            assertEquals(2, f.calls.get());
        }
    }

    @Test void missingValuesAndUnknownSamplingTimesRemainPartialRatherThanFalseOrNow() {
        try (Fixture f = new Fixture()) {
            AuthorizedDevice third = new AuthorizedDevice("fixture-meter-c", "energy", "测试电表丙", "", true);
            f.devices = List.of(FIRST, SECOND, third);
            Map<String, Object> states = new LinkedHashMap<>();
            states.put(FIRST.id(), Map.of("totalActivePower", java.math.BigDecimal.ZERO));
            states.put(SECOND.id(), null);
            states.put(third.id(), Map.of("totalActivePower", java.math.BigDecimal.TEN));
            f.stateResult = new DeviceStateReadResult(states, null, List.of(), Map.of(FIRST.id(), f.clock.instant().toString()));
            assertPreparing(() -> f.directory.loadBatch(IDENTITY));
            f.directory.synchronizeDue();
            DeviceStateReadResult result = f.observations.read(IDENTITY, List.of(FIRST.id(), SECOND.id(), third.id()));
            assertEquals(ObservationStatusEnum.PARTIAL, result.status());
            assertEquals(List.of(SECOND.id()), result.missingDeviceIds());
            assertNull(result.states().get(SECOND.id()));
            assertNull(result.observedAt());
            assertEquals(Map.of(FIRST.id(), f.clock.instant().toString()), result.observedAtByDevice());
            assertEquals(java.math.BigDecimal.ZERO, ((Map<?, ?>) result.states().get(FIRST.id())).get("totalActivePower"));
            ObservationAdapter composed = ObservationAdapterFactory.composed(
                    request -> Mono.error(new AssertionError("ordinary dashboard read contacted source")),
                    f.localGateway(), identity -> Mono.just(Map.of()), null, f.directory.environmentHistory(), f.clock);
            Map<String, Object> dashboard = composed.read(new ObservationRequest(IDENTITY,
                    ObservationOperationEnum.DASHBOARD, Map.of("section", "energy"))).block().payload();
            Map<?, ?> quality = (Map<?, ?>) ((Map<?, ?>) dashboard.get("stateQuality")).get("energy");
            assertEquals("partial", quality.get("status"));
            assertNull(dashboard.get("observedAt"));
            assertEquals(result.observedAtByDevice(), quality.get("observedAtByDevice"));
            assertEquals(result.missingDeviceIds(), quality.get("missingDeviceIds"));
            assertEquals(result.cache(), quality.get("cache"));
            assertEquals(1, f.stateCalls.get());
        }
    }

    @Test void stateFailureIsAtomicAndDoesNotInvalidateAnOtherwiseCompleteDirectory() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            DeviceStateReadResult initial = f.observations.read(IDENTITY, List.of(FIRST.id()));
            f.devices = List.of(FIRST, SECOND);
            f.stateResult = new DeviceStateReadResult(Map.of(
                    FIRST.id(), Map.of("totalActivePower", java.math.BigDecimal.ONE),
                    SECOND.id(), Map.of("totalActivePower", new java.math.BigDecimal("1E+36"))), f.clock.instant().toString());
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals("READY", f.directory.status(IDENTITY).get("state"));
            assertEquals(List.of(FIRST.id(), SECOND.id()), ids(f.directory.read(IDENTITY)));
            DeviceStateReadResult result = f.observations.read(IDENTITY, List.of(FIRST.id(), SECOND.id()));
            assertEquals(initial.states().get(FIRST.id()), result.states().get(FIRST.id()));
            assertNull(result.states().get(SECOND.id()));
            assertEquals(initial.cache().get("updatedAt"), result.cache().get("updatedAt"));
            assertEquals("stale", result.cache().get("status"));
            assertEquals(ObservationStatusEnum.PARTIAL, result.status());
        }
    }

    @Test void lateOrUndatedSamplesDoNotOverwriteNewerStateOrRefreshItsCollectionTime() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            DeviceStateReadResult initial = f.observations.read(IDENTITY, List.of(FIRST.id()));
            for (String sample : new String[] { f.clock.instant().minusSeconds(1).toString(), null }) {
                f.clock.advance(61);
                f.stateResult = new DeviceStateReadResult(Map.of(FIRST.id(), Map.of("totalActivePower", java.math.BigDecimal.ONE)), sample);
                f.directory.synchronizeDue();
                DeviceStateReadResult result = f.observations.read(IDENTITY, List.of(FIRST.id()));
                assertEquals(initial.states(), result.states());
                assertEquals(initial.observedAt(), result.observedAt());
                assertEquals("stale", result.cache().get("status"));
                assertEquals(ObservationStatusEnum.PARTIAL, result.status());
                assertEquals(List.of(FIRST.id()), result.missingDeviceIds());
            }
        }
    }

    @Test void credentialRotationRequiresNewAuthorizationButReusesTheSamePhysicalFacts() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            var original = f.observations.read(IDENTITY, List.of(FIRST.id()));
            RequestIdentity rotated = identity("Bearer fixture-narrow", IDENTITY.tenantCode());
            assertPreparing(() -> f.observations.read(rotated, List.of(FIRST.id())));
            f.stateFailure = new IllegalStateException("a new credential must not force a new physical sample");
            f.directory.synchronizeDue();
            var reused = f.observations.read(rotated, List.of(FIRST.id()));
            assertEquals(original.states(), reused.states());
            assertEquals(original.cache(), reused.cache());
            assertEquals(1, f.stateCalls.get());
        }
    }

    @Test void sharedFactsNeverReplaceTheViewersOwnDeviceGrantOrTenantBoundary() {
        try (Fixture f = new Fixture()) {
            f.publishInitial();
            var original = f.observations.read(IDENTITY, List.of(FIRST.id()));
            RequestIdentity colleague = identity("Bearer fixture-other", IDENTITY.tenantCode());
            f.devices = List.of(SECOND);
            assertPreparing(() -> f.directory.loadBatch(colleague));
            f.directory.synchronizeDue();
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> f.observations.read(colleague, List.of(FIRST.id()))).httpStatus());
            assertEquals(original.states(), f.observations.read(IDENTITY, List.of(FIRST.id())).states());
            f.devices = List.of(FIRST);
            f.stateFailure = new IllegalStateException("other tenant has not published a sample");
            RequestIdentity otherTenant = identity("Bearer fixture-other", "fixture-other-tenant");
            assertPreparing(() -> f.directory.loadBatch(otherTenant));
            f.directory.synchronizeDue();
            assertEquals("DEVICE_STATE_UNAVAILABLE", assertThrows(BusinessException.class,
                    () -> f.observations.read(otherTenant, List.of(FIRST.id()))).code());
            f.stateFailure = new GatewayAuthorizationException(403);
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> f.observations.read(IDENTITY, List.of(FIRST.id()))).httpStatus());
        }
    }

    @Test void stateAndCapabilityReadsCheckCurrentMembershipWithoutContactingTheSource() {
        try (Fixture f = new Fixture()) {
            AuthorizedDevice light = new AuthorizedDevice("fixture-light", "lighting", "测试灯", "", false);
            f.devices = List.of(light);
            f.stateResult = new DeviceStateReadResult(Map.of(light.id(), false), f.clock.instant().toString());
            assertPreparing(() -> f.directory.loadBatch(IDENTITY));
            f.directory.synchronizeDue();
            new SchemaMigrations(f.jdbc.getDataSource()).ensureDeviceCandidateSets();
            f.jdbc.execute("ALTER TABLE device_candidate_sets ALTER COLUMN payload_json VARCHAR(1000000) NOT NULL");
            IotAdapter gateway = f.localGateway();
            DeviceCandidateSet set = gateway.findDeviceCandidates(IDENTITY,
                    new DeviceCandidateQuery("lighting", List.of(), "全部", List.of(), List.of(), null, null),
                    new DeviceCandidateBinding("fixture-state-conversation")).block();
            DeviceCapabilityRequest request = new DeviceCapabilityRequest(List.of(
                    new DeviceCapabilityRequest.DeviceCapabilitySelection(set.handle(), List.of(light.id()))));
            assertEquals(false, gateway.readDeviceStates(IDENTITY, request, "fixture-state-conversation").block().states().get(light.id()));
            assertEquals(List.of(Map.of("closeOrOpen", 0), Map.of("closeOrOpen", 1)),
                    gateway.inspectDeviceCapabilities(IDENTITY, request, "fixture-state-conversation").block().devices().getFirst().supportedCommands());
            assertEquals(1, f.calls.get());
            assertEquals(1, f.stateCalls.get());
            f.devices = List.of();
            f.clock.advance(61);
            f.directory.synchronizeDue();
            assertEquals("DEVICE_OUTSIDE_SCOPE", assertThrows(BusinessException.class,
                    () -> gateway.readDeviceStates(IDENTITY, List.of(light.id())).block()).code());
            assertEquals("CANDIDATE_SET_STALE", assertThrows(BusinessException.class,
                    () -> gateway.inspectDeviceCapabilities(IDENTITY, request, "fixture-state-conversation").block()).code());
            f.jdbc.execute("SHUTDOWN");
            assertEquals("IOT_SCOPE_UNAVAILABLE", assertThrows(BusinessException.class,
                    () -> gateway.readDeviceStates(IDENTITY, List.of(light.id())).block()).code());
            assertEquals(2, f.calls.get());
        }
    }

    private static void assertPreparing(org.junit.jupiter.api.function.Executable read) {
        assertEquals("IOT_SCOPE_PREPARING", assertThrows(BusinessException.class, read).code());
    }
    private static List<?> ids(Map<String, Object> body) {
        return ((List<?>) body.get("devices")).stream().map(row -> ((Map<?, ?>) row).get("id")).toList();
    }
    private static PrincipalInfo principal(String token, String tenant) {
        return new PrincipalInfo(token, tenant, token.endsWith("other") ? "fixture-other-principal" : "fixture-principal", "fixture-realm");
    }
    private static RequestIdentity identity(String token, String tenant) {
        return new RequestIdentity(token, tenant, IotSourceEnum.COMPANY, principal(token, tenant).ownerKey());
    }
    private static ReadSnapshots.Identity snapshotIdentity(RequestIdentity identity) {
        return new ReadSnapshots.Identity(identity.tenantCode(), identity.authorization(), identity.source().name(), identity.ownerKey());
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final ObjectMapper mapper = new ObjectMapper();
        final JdbcTemplate jdbc;
        final TransactionTemplate transactions;
        final MemoryBarrier barrier = new MemoryBarrier();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger stateCalls = new AtomicInteger();
        final AtomicInteger catalogCalls = new AtomicInteger();
        volatile List<DeviceCatalogSource.Entry> catalogDevices = List.of();
        volatile RuntimeException catalogFailure;
        volatile DeviceStateReadResult stateResult = new DeviceStateReadResult(
                Map.of(FIRST.id(), Map.of("totalActivePower", new java.math.BigDecimal("12345678901234567890.1234567890123456789"))),
                clock.instant().toString());
        volatile RuntimeException stateFailure;
        volatile List<AuthorizedDevice> devices = List.of(FIRST);
        volatile List<OfficeRegion> regions = List.of();
        volatile RuntimeException failure;
        volatile IotAdapter sourceOverride;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        final IotAdapter upstream = new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity request) {
                return Mono.fromCallable(() -> {
                    calls.incrementAndGet();
                    List<AuthorizedDevice> captured = devices;
                    if (entered != null) entered.countDown();
                    if (release != null && !release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture gate timed out");
                    if (failure != null) throw failure;
                    return AuthorizedDeviceScope.of(request, request.source(), captured);
                });
            }
            @Override public Mono<DeviceDirectoryBatch> collectDirectory(RequestIdentity request) {
                if (sourceOverride != null) return sourceOverride.collectDirectory(request);
                return loadAuthorizedDeviceScope(request).map(scope ->
                        new DeviceDirectoryBatch(scope, request.source() == IotSourceEnum.OFFICE ? regions : List.of()));
            }
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedEnergyScope(RequestIdentity request) {
                return Mono.just(AuthorizedDeviceScope.of(request, request.source(),
                        devices.stream().filter(device -> "energy".equals(device.kind())).toList()));
            }
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedSafetyScope(RequestIdentity request) {
                return Mono.fromCallable(() -> {
                    if (failure != null) throw failure;
                    return AuthorizedDeviceScope.of(request, request.source(),
                            devices.stream().filter(device -> "safety".equals(device.kind())).toList());
                });
            }
            @Override public Mono<DeviceStateReadResult> collectCurrentStates(RequestIdentity request, AuthorizedDeviceScope scope) {
                return Mono.fromCallable(() -> {
                    stateCalls.incrementAndGet();
                    if (stateFailure != null) throw stateFailure;
                    return stateResult;
                });
            }
        };
        final AccountPrincipalVerifier verifier = presented -> principal(presented.authorization(), presented.tenantCode());
        ReadSnapshots snapshots;
        DeviceDirectory directory;
        DeviceObservations observations;
        Fixture() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:company_directory_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            jdbc = new JdbcTemplate(source);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
            SchemaMigrations migrations = new SchemaMigrations(source);
            migrations.ensureLocalCompanyDirectory();
            migrations.ensureLocalCompanyDirectory();
            // H2 quotes bound Strings in JSON columns; MySQL parses them as JSON documents.
            // This fixture uses text; the isolated MySQL smoke verifies the production JSON column.
            jdbc.execute("ALTER TABLE local_company_devices ALTER COLUMN metadata_json VARCHAR(1000000) NOT NULL");
            jdbc.execute("ALTER TABLE local_device_states ALTER COLUMN extensions_json VARCHAR(1000000) NOT NULL");
            open();
        }
        void open() {
            snapshots = ReadSnapshots.create(clock, Runnable::run, Runnable::run, null, barrier, mapper, ReadSnapshots.Settings.defaults());
            directory = DeviceDirectory.jdbc(jdbc, transactions, mapper, upstream,
                    ObservationAdapterFactory.failClosed("fixture historical source unavailable"), verifier, snapshots, barrier,
                    "https://gateway.example.invalid", KEY, clock, request -> {
                        catalogCalls.incrementAndGet();
                        if (catalogFailure != null) throw catalogFailure;
                        return catalogDevices;
                    }, 20);
            observations = DeviceObservations.jdbc(jdbc, transactions, mapper, directory, "https://gateway.example.invalid", clock);
        }
        void restart() { directory.close(); snapshots.close(); open(); }
        IotAdapter localGateway() {
            return IotAdapterFactory.localReads(upstream, () -> directory, () -> observations, IotAdapterFactory.candidateSets(jdbc, mapper));
        }
        void publishInitial() { assertPreparing(() -> directory.read(IDENTITY)); directory.synchronizeDue(); assertEquals(List.of("fixture-meter-a"), ids(directory.read(IDENTITY))); }
        @Override public void close() { directory.close(); snapshots.close(); jdbc.execute("SHUTDOWN"); }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-15T03:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private static final class MemoryBarrier implements ReadSnapshots.Barrier {
        private final Map<Key, State> values = new LinkedHashMap<>();
        @Override public Checkpoint startInstance(String instance) { return new Checkpoint(true, "fixture-epoch"); }
        @Override public boolean markClean(String instance) { return true; }
        @Override public synchronized Optional<State> read(Key key) { return Optional.ofNullable(values.get(key)); }
        @Override public synchronized boolean write(Key key, State state) {
            State current = values.get(key);
            if (current != null && current.issuedAt().isAfter(state.issuedAt())) return false;
            values.put(key, state);
            return true;
        }
        @Override public synchronized boolean writeIfCurrent(Key key, String expected, State state) {
            State current = values.get(key);
            if (expected == null ? current != null : current == null || !expected.equals(current.generation())) return false;
            return write(key, state);
        }
    }
}
