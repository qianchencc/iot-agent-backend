package com.ehe.iotagent.task;

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
import com.ehe.iotagent.auth.PrincipalInfo;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.DeviceStateReadResult;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.readmodel.ReadSnapshots;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Task current states preserve local observation quality and never replace durable execution facts. */
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
    void partialLocalStateRetainsUnknownSampleTimeAndTheMissingDevice() {
        try (Harness h = new Harness()) {
            h.observedAt.set(null);
            Map<String, Object> states = new java.util.LinkedHashMap<>();
            states.put("light-1", Map.of("state", "on"));
            states.put("light-2", null);
            h.liveStates.set(states);
            Map<String, Object> result = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(result.get("status")).isEqualTo("partial");
            assertThat(result.get("observedAt")).isNull();
            assertThat(result.get("missingDeviceIds")).isEqualTo(List.of("light-2"));
            assertThat(state(result, "light-1")).isEqualTo("on");
            assertThat(state(result, "light-2")).isNull();
        }
    }

    @Test
    void aCompletedPhaseDoesNotTurnItsCommandTargetIntoCurrentDeviceState() {
        try (Harness h = new Harness()) {
            h.phases.set(List.of(phase("task-1", "phase-1", "COMPLETED", TASK1_ACTIONS,
                    "{\"devices\":{\"light-1\":{\"outcome\":\"turnedOff\"}}}",
                    NOW.minusSeconds(60), NOW.minusSeconds(50))));
            Map<String, Object> beforeSync = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(state(beforeSync, "light-1")).isEqualTo("on");
            assertThat(beforeSync.get("observedAt")).isEqualTo("2026-11-05T07:59:00Z");
            h.liveStates.set(Map.of("light-1", Map.of("state", "off"), "light-2", Map.of("state", "off")));
            h.observedAt.set("2026-11-05T08:01:00Z");
            Map<String, Object> afterSync = h.facade.lightingStates(PRINCIPAL, "task-1", "company");
            assertThat(state(afterSync, "light-1")).isEqualTo("off");
            assertThat(afterSync.get("observedAt")).isEqualTo("2026-11-05T08:01:00Z");
        }
    }

    @Test
    void denialAndDatabaseFailureDoNotExposeHeldCurrentStates() {
        try (Harness h = new Harness()) {
            assertThat(ids(h.facade.lightingStates(PRINCIPAL, "task-1", "company")))
                    .containsExactly("light-1", "light-2");
            h.upstreamDenial = new BusinessException("UNAUTHORIZED", "测试授权已失效", 401);
            assertThatThrownBy(() -> h.facade.lightingStates(PRINCIPAL, "task-1", "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.httpStatus()).isEqualTo(401));
            h.upstreamDenial = null;
            h.statesAvailable = false;
            assertThatThrownBy(() -> h.facade.lightingStates(PRINCIPAL, "task-1", "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_LIGHTING_STATES_UNAVAILABLE"));
        }
    }

    @Test
    void foreignTasksAndRemovedOrRepurposedDevicesDoNotExposeState() {
        try (Harness h = new Harness()) {
            PrincipalInfo foreign = new PrincipalInfo("Bearer foreign-tenant", "OTHER", "member", "gateway");
            assertThatThrownBy(() -> h.facade.lightingStates(foreign, "task-1", "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_NOT_FOUND"));
            PrincipalInfo rotated = new PrincipalInfo("Bearer rotated-reader", "TENANT", "member", "gateway");
            assertThatThrownBy(() -> h.facade.lightingStates(rotated, "task-1", "company"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.code()).isEqualTo("TASK_READ_PREPARING"));
            assertThat(ids(h.facade.lightingStates(PRINCIPAL, "task-1", "company")))
                    .containsExactly("light-1", "light-2");
            h.devices.set(List.of(
                    new AuthorizedDevice("light-1", "energy", "灯一", "26楼", true),
                    new AuthorizedDevice("light-2", "lighting", "灯二", "26楼", false)));
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
                @Override public Mono<com.ehe.iotagent.iot.DeviceDirectoryBatch> readDirectory(RequestIdentity identity) {
                    if (upstreamDenial != null) return Mono.error(upstreamDenial);
                    if (!PRINCIPAL.authorization().equals(identity.authorization())
                            || identity.source() != com.ehe.iotagent.iot.IotSourceEnum.COMPANY) {
                        return Mono.error(new BusinessException("IOT_SCOPE_PREPARING", "Fixture directory pending", 503));
                    }
                    return Mono.just(new com.ehe.iotagent.iot.DeviceDirectoryBatch(
                            AuthorizedDeviceScope.of(identity, identity.source(), devices.get()), List.of()));
                }

                @Override public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                    if (upstreamDenial != null) return Mono.error(upstreamDenial);
                    return Mono.just(AuthorizedDeviceScope.of(identity, identity.source(), devices.get()));
                }

                @Override public Mono<DeviceStateReadResult> readDeviceStates(
                        RequestIdentity identity, List<String> deviceIds) {
                    if (upstreamDenial != null) return Mono.error(upstreamDenial);
                    if (!statesAvailable) return Mono.error(new IllegalStateException("local database unavailable"));
                    stateReads.add(List.copyOf(deviceIds));
                    Map<String, Object> selected = new java.util.LinkedHashMap<>();
                    for (String id : deviceIds) selected.put(id, liveStates.get().get(id));
                    return Mono.just(new DeviceStateReadResult(selected, observedAt.get()));
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
