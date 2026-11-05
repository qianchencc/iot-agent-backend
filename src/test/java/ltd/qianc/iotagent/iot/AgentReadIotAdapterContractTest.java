package ltd.qianc.iotagent.iot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AgentReadIotAdapterContractTest {
    private static final RequestIdentity COMPANY =
            new RequestIdentity("Bearer company", "TENANT", IotSourceEnum.COMPANY);
    private static final AuthorizedDevice METER =
            new AuthorizedDevice("meter-1", "energy", "一号表", "26楼", true);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void companyCatalogReusesTheExistingCompanyDevicesEntry() {
        ReadSnapshots snapshots = snapshots(Runnable::run);
        AtomicInteger upstreamCalls = new AtomicInteger();
        IotAdapter source = identity -> {
            upstreamCalls.incrementAndGet();
            return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), List.of(METER)));
        };
        snapshots.read(identity(COMPANY), "COMPANY_DEVICES", Map.of(), ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("source", "company", "devices", List.of(Map.of(
                        "id", "meter-1", "kind", "energy", "name", "一号表", "location", "26楼"))));

        IotAdapter agentReads = IotAdapterFactory.agentReads(source, snapshots, new ObjectMapper());
        AuthorizedDeviceScope scope = agentReads.loadAuthorizedDeviceScope(COMPANY).block();

        assertEquals("meter-1", scope.devices().getFirst().id());
        assertEquals(0, upstreamCalls.get());
        snapshots.close();
    }

    @Test
    void coldCompanyCatalogPreparesInTheExistingEntryAndNeverCallsUpstreamOnTheCaller() {
        Queue<Runnable> jobs = new ArrayDeque<>();
        ReadSnapshots snapshots = snapshots(jobs::add);
        AtomicInteger upstreamCalls = new AtomicInteger();
        IotAdapter source = identity -> {
            upstreamCalls.incrementAndGet();
            return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), List.of(METER)));
        };
        IotAdapter agentReads = IotAdapterFactory.agentReads(source, snapshots, new ObjectMapper());


        BusinessException preparing = assertThrows(BusinessException.class,
                () -> agentReads.loadAuthorizedDeviceScope(COMPANY).block());
        assertEquals("IOT_SCOPE_PREPARING", preparing.code());
        assertEquals(0, upstreamCalls.get());

        while (!jobs.isEmpty()) jobs.remove().run();
        assertEquals("meter-1", agentReads.loadAuthorizedDeviceScope(COMPANY).block().devices().getFirst().id());
        assertEquals(1, upstreamCalls.get());
        assertEquals("meter-1", agentReads.loadAuthorizedDeviceScope(COMPANY).block().devices().getFirst().id());
        assertEquals(1, upstreamCalls.get());
        snapshots.close();
    }
    @Test
    void candidateDiscoveryReusesTheExistingCandidateEntry() {
        ReadSnapshots snapshots = snapshots(Runnable::run);
        AtomicInteger upstreamCalls = new AtomicInteger();
        DeviceCandidateQuery query = new DeviceCandidateQuery(
                "energy", List.of(), null, List.of(), List.of(), "一号表", null);
        DeviceCandidateSet candidate = new DeviceCandidateSet(
                "candidate-1", query, List.of(METER), true, 1);
        IotAdapter source = new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), List.of(METER)));
            }
            @Override public Mono<DeviceCandidateSet> findDeviceCandidates(
                    RequestIdentity identity, DeviceCandidateQuery ignored) {
                upstreamCalls.incrementAndGet();
                return Mono.just(candidate);
            }
        };
        Map<String, Object> candidateKey = new ObjectMapper().convertValue(
                query, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        snapshots.read(identity(COMPANY), "DEVICE_CANDIDATES", candidateKey,
                ReadSnapshots.PolicyEnum.CURRENT, candidate::publicView);

        IotAdapter agentReads = IotAdapterFactory.agentReads(source, snapshots, new ObjectMapper());
        DeviceCandidateSet result = agentReads.findDeviceCandidates(COMPANY, query).block();

        assertEquals("candidate-1", result.candidateSetId());
        assertEquals("meter-1", result.devices().getFirst().id());
        assertEquals(0, upstreamCalls.get());
        snapshots.close();
    }

    @Test
    void deviceStateReadsReuseTheExistingDeviceStatesEntry() {
        ReadSnapshots snapshots = snapshots(Runnable::run);
        AtomicInteger upstreamCalls = new AtomicInteger();
        IotAdapter source = new IotAdapter() {
            @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), List.of(METER)));
            }
            @Override public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> ids) {
                upstreamCalls.incrementAndGet();
                return Mono.just(new DeviceStateReadResult(Map.of("meter-1", Boolean.TRUE), "2026-11-05T10:00:00Z"));
            }
        };
        snapshots.read(identity(COMPANY), "DEVICE_STATES", Map.of("deviceIds", List.of("meter-1")),
                ReadSnapshots.PolicyEnum.CURRENT,
                () -> Map.of("states", Map.of("meter-1", Boolean.FALSE), "observedAt", "2026-11-05T10:00:00Z"));

        IotAdapter agentReads = IotAdapterFactory.agentReads(source, snapshots, new ObjectMapper());
        DeviceStateReadResult result = agentReads.readDeviceStates(COMPANY, List.of("meter-1")).block();

        assertEquals(Boolean.FALSE, result.states().get("meter-1"));
        assertEquals(0, upstreamCalls.get());
        snapshots.close();
    }

    private static ReadSnapshots snapshots(java.util.concurrent.Executor upstream) {
        return ReadSnapshots.create(CLOCK, upstream, Runnable::run, null, null,
                new ObjectMapper(), ReadSnapshots.Settings.defaults());
    }

    private static ReadSnapshots.Identity identity(RequestIdentity identity) {
        return new ReadSnapshots.Identity(identity.tenantCode(), identity.authorization(),
                identity.source().name(), identity.ownerKey());
    }
}
