package ltd.qianc.iotagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Consumer-visible contract for the task detail lighting states ("现在状态"):
 * an authorized read never blocks the request thread on the gateway, a cold
 * snapshot reports preparation instead of unknown states, concurrent reads
 * coalesce and stay isolated per credential/source/device set, revocation and
 * de-authorization never leak held rows, and a completed phase can never reuse
 * the pre-execution observation.
 */
class TaskLightingStateSnapshotContractTest {
    private static final Instant NOW = Instant.parse("2026-11-05T08:00:00Z");
    private static final PrincipalInfo PRINCIPAL =
            new PrincipalInfo("Bearer lighting-reader", "TENANT", "member", "gateway");
    private static final String TASK1_ACTIONS =
            "{\"actions\":[{\"deviceId\":\"light-1\",\"kind\":\"lighting\"},"
                    + "{\"deviceId\":\"light-2\",\"kind\":\"lighting\"}]}";
    private static final String TASK2_ACTIONS =
            "{\"actions\":[{\"deviceId\":\"light-3\",\"kind\":\"lighting\"}]}";

    @Test
    void coldStateReportsPreparingAndHeldStateSurvivesSlowOrFailedStateRenewal() {
        try (Harness h = new Harness()) {
            h.prepare();
            Map<String, Object> cold = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(cold.get("status")).isEqualTo("preparing");
            assertThat(cold.get("states")).as("cold reads never fabricate state rows").isNull();
            assertThat(h.stateReads).as("the request thread never crawls upstream").isEmpty();

            h.runUpstream();
            Map<String, Object> warm = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(warm.get("observedAt")).isEqualTo("2026-11-05T07:59:00Z");
            assertThat(ids(warm)).containsExactly("light-1", "light-2");
            assertThat(state(warm, "light-1")).isEqualTo("on");
            assertThat(state(warm, "light-2")).isEqualTo("off");
            assertThat(warm.get("cache")).isInstanceOf(Map.class);

            // Only the state renewal fails here while the scope catalog stays
            // available: the held observation keeps serving without blocking,
            // now marked with its renewal failure.
            h.statesAvailable = false;
            h.clock.now = NOW.plusSeconds(61);
            Map<String, Object> held = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(held.get("observedAt")).isEqualTo("2026-11-05T07:59:00Z");
            assertThat(ids(held)).containsExactly("light-1", "light-2");
            h.runUpstream();
            Map<String, Object> afterFailure = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(ids(afterFailure)).containsExactly("light-1", "light-2");
            assertThat(state(afterFailure, "light-1")).isEqualTo("on");
            assertThat(((Map<?, ?>) afterFailure.get("cache")).get("lastError")).isNotNull();
        }
    }

    @Test
    void successiveColdReadsCoalesceIntoOneUpstreamStateLoad() {
        try (Harness h = new Harness()) {
            h.prepare();
            for (int index = 0; index < 3; index++) {
                assertThat(h.facade.lightingStates(PRINCIPAL, "task-1", "company").get("status"))
                        .isEqualTo("preparing");
            }
            assertThat(h.upstream).as("three reads share one background load").hasSize(1);
            assertThat(h.stateReads).isEmpty();
            h.runUpstream();
            assertThat(ids(h.facade.lightingStates(PRINCIPAL, "task-1", "company")))
                    .containsExactly("light-1", "light-2");
            assertThat(h.stateReads).hasSize(1);
        }
    }

    @Test
    void liveCredentialDenialRevokesHeldStatesSoNothingLeaksFromCache() {
        try (Harness h = new Harness()) {
            h.prepare();
            assertThat(h.facade.lightingStates(PRINCIPAL, "task-1", "company").get("status"))
                    .isEqualTo("preparing");
            h.runUpstream();
            assertThat(ids(h.facade.lightingStates(PRINCIPAL, "task-1", "company")))
                    .containsExactly("light-1", "light-2");

            // Held rows may serve while revalidation is in flight, but once the
            // live credential is denied the cached observation must not leak.
            h.clock.now = NOW.plusSeconds(61);
            h.upstreamDenial = new BusinessException("UNAUTHORIZED", "上游身份已失效", 401);
            assertThat(ids(h.facade.lightingStates(PRINCIPAL, "task-1", "company")))
                    .containsExactly("light-1", "light-2");
            h.runUpstream();
            assertThatThrownBy(() -> h.facade.lightingStates(PRINCIPAL, "task-1", "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.httpStatus()).isEqualTo(401));
        }
    }

    @Test
    void stateReadsAreIsolatedByCredentialSourceAndDeviceSet() {
        try (Harness h = new Harness()) {
            h.prepare();
            assertThat(h.facade.lightingStates(PRINCIPAL, "task-1", "company").get("status"))
                    .isEqualTo("preparing");
            h.runUpstream();

            PrincipalInfo rotated = new PrincipalInfo("Bearer rotated-reader", "TENANT", "member", "gateway");
            assertThatThrownBy(() -> h.facade.lightingStates(rotated, "task-1", "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_READ_PREPARING"));
            assertThatThrownBy(() -> h.facade.lightingStates(PRINCIPAL, "task-office", "office"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_READ_PREPARING"));
            assertThat(h.facade.lightingStates(PRINCIPAL, "task-2", "company").get("status"))
                    .isEqualTo("preparing");
            assertThat(ids(h.facade.lightingStates(PRINCIPAL, "task-1", "company")))
                    .containsExactly("light-1", "light-2");
        }
    }

    @Test
    void completedPhaseCannotReuseThePreExecutionStateSnapshot() {
        try (Harness h = new Harness()) {
            h.prepare();
            assertThat(h.facade.lightingStates(PRINCIPAL, "task-1", "company").get("status"))
                    .isEqualTo("preparing");
            h.runUpstream();
            assertThat(state(h.facade.lightingStates(PRINCIPAL, "task-1", "company"), "light-1"))
                    .isEqualTo("on");
            assertThat(h.stateReads).hasSize(1);

            // The phase executes: the durable execution fact must make the held
            // pre-execution observation unusable for the same device set.
            h.phases.set(List.of(phase("task-1", "phase-1", "COMPLETED", TASK1_ACTIONS,
                    "{\"devices\":{\"light-1\":{\"outcome\":\"turnedOff\"}}}",
                    NOW.minusSeconds(60), NOW.minusSeconds(50))));
            h.liveStates.set(Map.of(
                    "light-1", Map.of("state", "off", "reportedAt", "r-1b"),
                    "light-2", Map.of("state", "off", "reportedAt", "r-2b")));
            h.observedAt.set("2026-11-05T08:01:00Z");
            Map<String, Object> postExecution = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(postExecution.get("status"))
                    .as("execution makes the pre-execution snapshot cold").isEqualTo("preparing");
            assertThat(postExecution.get("states")).isNull();
            h.runUpstream();
            Map<String, Object> refreshed = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(refreshed.get("observedAt")).isEqualTo("2026-11-05T08:01:00Z");
            assertThat(state(refreshed, "light-1")).isEqualTo("off");
            assertThat(h.stateReads).hasSize(2);
        }
    }

    @Test
    void invisibleTasksStayInvisibleAndDeAuthorizedLightingFailsClosed() {
        try (Harness h = new Harness()) {
            h.prepare();
            PrincipalInfo foreign = new PrincipalInfo("Bearer foreign-tenant", "OTHER", "member", "gateway");
            assertThatThrownBy(() -> h.facade.lightingStates(foreign, "task-1", "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_NOT_FOUND"));
            assertThat(h.upstream).as("an invisible task queues no state work").isEmpty();

            assertThat(h.facade.lightingStates(PRINCIPAL, "task-1", "company").get("status"))
                    .isEqualTo("preparing");
            h.runUpstream();
            assertThat(ids(h.facade.lightingStates(PRINCIPAL, "task-1", "company")))
                    .containsExactly("light-1", "light-2");

            // A device re-purposed away from lighting de-authorizes the whole
            // read; previously held states must not leak back.
            h.devices.set(List.of(
                    new AuthorizedDevice("light-1", "energy", "灯一", "26楼", true),
                    new AuthorizedDevice("light-2", "lighting", "灯二", "26楼", false)));
            h.clock.now = NOW.plusSeconds(61);
            assertThat(ids(h.facade.lightingStates(PRINCIPAL, "task-1", "company")))
                    .containsExactly("light-1", "light-2");
            h.runUpstream();
            assertThatThrownBy(() -> h.facade.lightingStates(PRINCIPAL, "task-1", "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_NOT_FOUND"));
        }
    }

    // ------------------------------------------------------------ fixtures

    private static TaskPhaseStore.PhaseResult phase(String taskId, String phaseId, String status,
            String definitionJson, String resultJson, Instant startedAt, Instant endedAt) {
        return new TaskPhaseStore.PhaseResult(taskId, phaseId, NOW.minusSeconds(120), status,
                definitionJson, resultJson, startedAt, endedAt, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stateRows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("states");
    }

    private static List<String> ids(Map<String, Object> result) {
        return stateRows(result).stream().map(row -> String.valueOf(row.get("id"))).toList();
    }

    private static Object state(Map<String, Object> result, String deviceId) {
        return stateRows(result).stream().filter(row -> deviceId.equals(row.get("id")))
                .findFirst().orElseThrow().get("state");
    }

    private static final class Harness implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final Queue<Runnable> upstream = new ArrayDeque<>();
        final Queue<Runnable> storage = new ArrayDeque<>();
        final AtomicReference<List<AuthorizedDevice>> devices = new AtomicReference<>(List.of(
                new AuthorizedDevice("light-1", "lighting", "灯一", "26楼", false),
                new AuthorizedDevice("light-2", "lighting", "灯二", "26楼", false),
                new AuthorizedDevice("light-3", "lighting", "灯三", "26楼", false)));
        final AtomicReference<Map<String, Object>> liveStates = new AtomicReference<>(Map.of(
                "light-1", Map.of("state", "on", "reportedAt", "r-1"),
                "light-2", Map.of("state", "off", "reportedAt", "r-2"),
                "light-3", Map.of("state", "off", "reportedAt", "r-3")));
        final AtomicReference<String> observedAt = new AtomicReference<>("2026-11-05T07:59:00Z");
        final List<List<String>> stateReads = new ArrayList<>();
        final AtomicReference<List<TaskPhaseStore.PhaseResult>> phases = new AtomicReference<>(List.of(
                phase("task-1", "phase-1", "PENDING", TASK1_ACTIONS, null, null, null)));
        final TaskFactStore tasks = mock(TaskFactStore.class);
        final ReadSnapshots snapshots = ReadSnapshots.create(clock, upstream::add, storage::add,
                null, null, new ObjectMapper(), ReadSnapshots.Settings.defaults());
        final TaskFacade facade;
        boolean statesAvailable = true;
        BusinessException upstreamDenial;

        Harness() {
            IotAdapter iot = new IotAdapter() {
                @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                    if (upstreamDenial != null) return Mono.error(upstreamDenial);
                    return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), devices.get()));
                }

                @Override public Mono<DeviceStateReadResult> readDeviceStates(
                        RequestIdentity identity, List<String> deviceIds) {
                    if (upstreamDenial != null) return Mono.error(upstreamDenial);
                    if (!statesAvailable) return Mono.error(new IllegalStateException("state upstream unavailable"));
                    stateReads.add(List.copyOf(deviceIds));
                    return Mono.just(new DeviceStateReadResult(liveStates.get(), observedAt.get()));
                }
            };
            String owner = PRINCIPAL.ownerKey();
            when(tasks.find("task-1")).thenReturn(Optional.of(new TaskFact("task-1", 1, "SCHEDULED",
                    "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + owner
                            + "\",\"label\":\"关灯任务\",\"phases\":[" + TASK1_ACTIONS + "]}", "key-1")));
            when(tasks.find("task-2")).thenReturn(Optional.of(new TaskFact("task-2", 1, "SCHEDULED",
                    "{\"source\":\"company\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + owner
                            + "\",\"label\":\"会议室关灯\",\"phases\":[" + TASK2_ACTIONS + "]}", "key-2")));
            when(tasks.find("task-office")).thenReturn(Optional.of(new TaskFact("task-office", 1, "SCHEDULED",
                    "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"" + owner
                            + "\",\"label\":\"办公关灯\",\"phases\":[" + TASK1_ACTIONS + "]}", "key-o")));
            TaskPhaseStore phaseStore = mock(TaskPhaseStore.class);
            when(phaseStore.allPhases("task-1")).thenAnswer(invocation -> phases.get());
            when(phaseStore.allPhases("task-2")).thenReturn(List.of(
                    phase("task-2", "phase-2", "PENDING", TASK2_ACTIONS, null, null, null)));
            facade = new TaskFacade(new ObjectMapper(), tasks, mock(TaskProposalStore.class),
                    mock(TaskPlanStore.class), mock(TaskNotificationStore.class), mock(TaskScheduler.class),
                    phaseStore, iot, snapshots, clock);
        }

        void prepare() {
            try {
                facade.scope(PRINCIPAL, "company");
            } catch (BusinessException pending) {
                if (!"TASK_READ_PREPARING".equals(pending.code())) throw pending;
            }
            runUpstream();
        }

        void runUpstream() {
            while (!upstream.isEmpty()) upstream.remove().run();
        }

        @Override public void close() {
            snapshots.close();
        }
    }

    private static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public Instant instant() {
            return now;
        }
        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        @Override public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
