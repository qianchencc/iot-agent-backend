package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDispatchResult;
import ltd.qianc.iotagent.action.ActionDraftStatusEnum;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.internal.RealTaskExecutionGateway;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer-visible lifecycle regressions migrated from the Runtime: restart
 * never re-dispatches unknown or elapsed work, authorization/binding
 * invalidation pauses the plan and cancels later phases without recurrence,
 * Office acceptance never masquerades as readback verification, general
 * control keeps the per-device cancel/window guard with bounded hub-ordered
 * workers, terminal aggregation keeps MISSED/SATISFIED/INTERRUPTED distinct,
 * notification persistence failure is compensated durably, and protected
 * execution identities are cleaned once the task result is durable.
 */
class TaskLifecycleRecoveryContractTest {
    private static final Instant NOW = Instant.parse("2026-11-04T02:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------------------------------------------------------- gateway: office vs company acceptance

    @Test
    void officeAcceptedReceiptWithoutReadbackIsCompletedButNeverVerified() {
        States states = new States(IotSourceEnum.OFFICE);
        states.register("light-1", "lighting");
        states.script("light-1", Map.of("power", "off"));
        Actions actions = new Actions();
        actions.result = "{\"receipts\":[{\"actionId\":\"phase-1:light-1\",\"outcome\":\"ACCEPTED\"}],"
                + "\"readbacks\":[{\"actionId\":\"phase-1:light-1\",\"outcome\":\"unconfirmed\"}]}";
        TaskExecutionGateway gateway = gateway(states, actions, new Guard());

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request("office",
                "{\"at\":\"" + NOW + "\",\"actions\":[" + action("light-1", "lighting") + "]}",
                task("office", null)));

        JsonNode payload = parse(result.payloadJson());
        assertThat(result.outcome()).as("Office受理即成功，但设备事实仍是unconfirmed").isEqualTo("COMPLETED");
        assertThat(payload.path("requiresVerification").asBoolean()).isFalse();
        assertThat(payload.path("devices").path("light-1").path("outcome").asText())
                .isEqualTo("unconfirmed");
        assertThat(payload.path("devices").path("light-1").path("receipt").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void companyAcceptedReceiptWithoutReadbackStaysUnconfirmed() {
        States states = new States(IotSourceEnum.COMPANY);
        states.register("light-1", "lighting");
        states.script("light-1", Map.of("power", "off"));
        Actions actions = new Actions();
        actions.result = "{\"receipts\":[{\"actionId\":\"phase-1:light-1\",\"outcome\":\"ACCEPTED\"}],"
                + "\"readbacks\":[{\"actionId\":\"phase-1:light-1\",\"outcome\":\"unconfirmed\"}]}";
        TaskExecutionGateway gateway = gateway(states, actions, new Guard());

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request("company",
                "{\"at\":\"" + NOW + "\",\"requiresVerification\":true,\"actions\":["
                        + action("light-1", "lighting") + "]}",
                task("company", null)));

        assertThat(result.outcome()).as("Company要求回读核验，仅受理不算完成").isEqualTo("UNCONFIRMED");
    }

    @Test
    void generalControlRechecksCancelWindowPerDeviceAndKeepsHubOrder() {
        States states = new States(IotSourceEnum.OFFICE);
        states.register("light-1", "lighting");
        states.register("light-2", "lighting");
        states.script("light-1", "hub-shared", Map.of("power", "off"));
        states.script("light-2", "hub-shared", Map.of("power", "off"));
        Actions actions = new Actions();
        actions.result = "{\"receipts\":[{\"actionId\":\"phase-1:%s\",\"outcome\":\"ACCEPTED\"}],"
                + "\"readbacks\":[{\"actionId\":\"phase-1:%s\",\"outcome\":\"confirmed\"}]}";
        Guard guard = new Guard();
        guard.dispatchableAfter = 1;
        TaskExecutionGateway gateway = gateway(states, actions, guard);

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request("office",
                "{\"at\":\"" + NOW + "\",\"actions\":["
                        + action("light-1", "lighting") + "," + action("light-2", "lighting") + "]}",
                task("office", null)));

        JsonNode payload = parse(result.payloadJson());
        assertThat(actions.dispatchedDeviceIds).hasSize(1);
        assertThat(payload.path("devices").path("light-2").path("outcome").asText()).isEqualTo("not_dispatched");
        assertThat(payload.path("devices").path("light-2").path("reason").asText()).contains("任务已停止");
        assertThat(result.outcome()).isEqualTo("PARTIAL");
    }

    @Test
    void companyScopeLossFailsBeforeDispatchWithInvalidationKind() {
        States states = new States(IotSourceEnum.COMPANY);
        states.register("light-1", "lighting");
        states.script("light-1", Map.of("power", "off"));
        States revoked = new States(IotSourceEnum.COMPANY) {
            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                return Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.COMPANY, List.of()));
            }
        };
        Actions actions = new Actions();
        TaskExecutionGateway gateway = gateway(revoked, actions, new Guard());

        assertThatThrownBy(() -> gateway.execute(request("company",
                "{\"at\":\"" + NOW + "\",\"requiresVerification\":true,\"actions\":["
                        + action("light-1", "lighting") + "]}",
                task("company", null))))
                .isInstanceOfSatisfying(TaskExecutionGateway.DispatchException.class, failed -> {
                    assertThat(failed.dispatchStarted()).isFalse();
                    assertThat(failed.reasonCode())
                            .isEqualTo(TaskExecutionGateway.DispatchException.AUTHORIZATION_REVOKED);
                });
        assertThat(actions.dispatchedDeviceIds).isEmpty();
    }

    @Test
    void officeScopeLossKeepsPerDeviceMissingOutcomeWithoutFailingOthers() {
        States states = new States(IotSourceEnum.OFFICE) {
            @Override
            public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
                List<AuthorizedDevice> onlySecond = List.of(
                        new AuthorizedDevice("light-2", "lighting", "light-2", "", false,
                                null, null, "hub-2", null, List.of(), List.of(), Map.of()));
                return Mono.just(AuthorizedDeviceScope.of(identity, IotSourceEnum.OFFICE, onlySecond));
            }
        };
        states.script("light-1", Map.of("power", "off"));
        states.script("light-2", Map.of("power", "off"));
        Actions actions = new Actions();
        actions.result = "{\"receipts\":[{\"actionId\":\"phase-1:light-2\",\"outcome\":\"ACCEPTED\"}],"
                + "\"readbacks\":[{\"actionId\":\"phase-1:light-2\",\"outcome\":\"confirmed\"}]}";
        TaskExecutionGateway gateway = gateway(states, actions, new Guard());

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request("office",
                "{\"at\":\"" + NOW + "\",\"actions\":["
                        + action("light-1", "lighting") + "," + action("light-2", "lighting") + "]}",
                task("office", null)));

        JsonNode payload = parse(result.payloadJson());
        assertThat(payload.path("devices").path("light-1").path("outcome").asText()).isEqualTo("missing");
        assertThat(payload.path("devices").path("light-1").path("reason").asText()).contains("授权目录");
        assertThat(payload.path("devices").path("light-2").path("outcome").asText()).isEqualTo("verified");
        assertThat(result.outcome()).isEqualTo("PARTIAL");
    }

    @Test
    void upstreamIdentityRejectionCarriesIdentityRejectedKind() {
        States states = new States(IotSourceEnum.COMPANY);
        states.register("light-1", "lighting");
        states.script("light-1", Map.of("power", "off"));
        Actions actions = new Actions() {
            @Override
            public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                                 String selectedActionsJson) {
                throw new GatewayAuthorizationException(401);
            }
        };
        TaskExecutionGateway gateway = gateway(states, actions, new Guard());

        assertThatThrownBy(() -> gateway.execute(request("company",
                "{\"at\":\"" + NOW + "\",\"requiresVerification\":true,\"actions\":["
                        + action("light-1", "lighting") + "]}",
                task("company", null))))
                .isInstanceOfSatisfying(TaskExecutionGateway.DispatchException.class, failed ->
                        assertThat(failed.reasonCode())
                                .isEqualTo(TaskExecutionGateway.DispatchException.IDENTITY_REJECTED));
    }

    // ---------------------------------------------------------- scanner: restart and invalidation

    @Test
    void restartMissesElapsedPlanPendingAndClosesExecutingWithoutRedispatch() {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-plan", "phase-elapsed", NOW.minusSeconds(3600), "PENDING"));
        phases.add(phase("task-plan", "phase-running", NOW.minusSeconds(60), "EXECUTING"));
        phases.add(phase("task-legacy", "phase-late", NOW.minusSeconds(1200), "PENDING"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-plan", "SCHEDULED", "{\"source\":\"company\",\"tenantCode\":\"TENANT\","
                + "\"ownerKey\":\"owner-1\",\"sourcePlanId\":\"plan-1\"}"));
        tasks.put(task("task-legacy", "SCHEDULED", "{\"source\":\"office\",\"tenantCode\":\"TENANT\","
                + "\"ownerKey\":\"owner-2\"}"));
        CountingGateway gateway = new CountingGateway();
        MemoryNotifications notifications = new MemoryNotifications();
        MemoryIdentities identities = new MemoryIdentities();
        identities.saveForTask("task-plan", identity());
        identities.saveForTask("task-legacy", identity());
        TaskScanner scanner = scanner(phases, tasks, gateway, notifications, null, identities);

        int recovered = scanner.recover();

        assertThat(recovered).isEqualTo(3);
        assertThat(gateway.executions).isEmpty();
        assertThat(phases.status("phase-elapsed")).isEqualTo("MISSED");
        assertThat(phases.status("phase-running")).isEqualTo("INTERRUPTED");
        assertThat(phases.status("phase-late")).isEqualTo("MISSED");
        assertThat(tasks.find("task-plan").orElseThrow().status()).isEqualTo("INTERRUPTED");
        assertThat(tasks.find("task-legacy").orElseThrow().status()).isEqualTo("MISSED");
        assertThat(notifications.byTask.keySet()).containsExactlyInAnyOrder("task-plan", "task-legacy");
        assertThat(identities.deleted).containsExactlyInAnyOrder("task-plan", "task-legacy");
        // A later poll must not dispatch the recovered work again.
        scanner.scanOnce();
        assertThat(gateway.executions).isEmpty();
    }

    @Test
    void authorizationInvalidationPausesPlanCancelsLaterPhasesAndNeverRecurs() {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-1", "phase-1", NOW, "PENDING"));
        phases.add(phase("task-1", "phase-2", NOW.plusSeconds(600), "PENDING"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-1", "SCHEDULED", "{\"source\":\"company\",\"tenantCode\":\"TENANT\","
                + "\"ownerKey\":\"owner-1\",\"sourcePlanId\":\"plan-1\"}"));
        InvalidatingGateway gateway = new InvalidatingGateway(
                TaskExecutionGateway.DispatchException.AUTHORIZATION_REVOKED, "任务设备授权目录已变化");
        MemoryNotifications notifications = new MemoryNotifications();
        MemoryPlans plans = new MemoryPlans();
        TaskScanner scanner = scanner(phases, tasks, gateway, notifications, plans, new MemoryIdentities());

        scanner.scanOnce();

        assertThat(gateway.executions).containsExactly("phase-1");
        assertThat(plans.paused).containsExactly(new String[] {"plan-1", "任务设备授权目录已变化"});
        assertThat(phases.status("phase-1")).isEqualTo("FAILED");
        assertThat(phases.status("phase-2")).isEqualTo("CANCELLED");
        assertThat(phases.failureReason("phase-2")).contains("周期计划已停用");
        assertThat(tasks.find("task-1").orElseThrow().status()).isEqualTo("FAILED");
        assertThat(notifications.byTask.keySet()).containsExactly("task-1");
        assertThat(parse(notifications.byTask.get("task-1").payloadJson()).path("reason").asText())
                .contains("周期计划已停用");
        scanner.scanOnce();
        assertThat(gateway.executions).containsExactly("phase-1");
    }

    @Test
    void invalidationWithoutPlanStillCancelsLaterPhasesOfTheTask() {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-1", "phase-1", NOW, "PENDING"));
        phases.add(phase("task-1", "phase-2", NOW.plusSeconds(600), "PENDING"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-1", "SCHEDULED", "{\"source\":\"company\",\"tenantCode\":\"TENANT\","
                + "\"ownerKey\":\"owner-1\"}"));
        InvalidatingGateway gateway = new InvalidatingGateway(
                TaskExecutionGateway.DispatchException.BINDING_CHANGED, "任务控制绑定已变化");
        MemoryNotifications notifications = new MemoryNotifications();
        TaskScanner scanner = scanner(phases, tasks, gateway, notifications, new MemoryPlans(),
                new MemoryIdentities());

        scanner.scanOnce();

        assertThat(phases.status("phase-2")).isEqualTo("CANCELLED");
        assertThat(phases.failureReason("phase-2")).contains("任务后续阶段已停止");
        assertThat(notifications.byTask.keySet()).containsExactly("task-1");
    }

    @Test
    void terminalAggregationKeepsSatisfiedMissedAndInterruptedDistinct() {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-sat", "phase-sat", NOW, "PENDING"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-sat", "SCHEDULED", "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"o\"}"));
        // All-satisfied single phase: aggregate must stay COMPLETED and phase SATISFIED.
        TaskExecutionGateway satisfied = request -> new TaskExecutionGateway.TaskExecutionResult(
                "SATISFIED", "{\"outcome\":\"satisfied\"}", false);
        MemoryNotifications notifications = new MemoryNotifications();
        TaskScanner scanner = scanner(phases, tasks, satisfied, notifications, null, new MemoryIdentities());

        scanner.scanOnce();

        assertThat(phases.status("phase-sat")).isEqualTo("SATISFIED");
        assertThat(tasks.find("task-sat").orElseThrow().status()).isEqualTo("COMPLETED");

        // Mixed interrupted+failed phases: the interrupted fact wins the aggregate.
        MemoryPhases mixed = new MemoryPhases();
        mixed.add(phase("task-mixed", "phase-mixed", NOW, "PENDING"));
        mixed.outcomes.put("task-mixed", List.of("INTERRUPTED", "FAILED"));
        MemoryTasks mixedTasks = new MemoryTasks();
        mixedTasks.put(task("task-mixed", "SCHEDULED",
                "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"o\"}"));
        TaskExecutionGateway interrupting = request -> {
            throw new TaskExecutionGateway.DispatchException("连接中断", true);
        };
        TaskScanner mixedScanner = scanner(mixed, mixedTasks, interrupting, notifications, null,
                new MemoryIdentities());

        mixedScanner.scanOnce();

        assertThat(mixed.status("phase-mixed")).isEqualTo("INTERRUPTED");
        assertThat(mixedTasks.find("task-mixed").orElseThrow().status()).isEqualTo("INTERRUPTED");
    }

    @Test
    void intervalTaskStaysExecutingUntilWindowEndsThenMaturationFinalizes() {
        MutableClock clock = new MutableClock(NOW);
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-int", "phase-int", NOW, "PENDING"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-int", "SCHEDULED", "{\"source\":\"company\",\"tenantCode\":\"TENANT\","
                + "\"ownerKey\":\"o\",\"interval\":{\"startsAt\":\"" + NOW + "\","
                + "\"endsAt\":\"" + NOW.plusSeconds(3600) + "\"}}"));
        CountingGateway gateway = new CountingGateway();
        MemoryNotifications notifications = new MemoryNotifications();
        MemoryIdentities identities = new MemoryIdentities();
        identities.saveForTask("task-int", identity());
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway, notifications,
                (checkpoint, identity) -> Optional.empty(), null, null, null, identities, null, null, null, clock);

        scanner.scanOnce();

        assertThat(phases.status("phase-int")).isEqualTo("COMPLETED");
        assertThat(tasks.find("task-int").orElseThrow().status())
                .as("Runtime reconcileTask：区间内任务必须真实 EXECUTING，不以 SCHEDULED 假作执行中")
                .isEqualTo("EXECUTING");
        assertThat(notifications.byTask).isEmpty();
        assertThat(identities.deleted).isEmpty();

        clock.advanceTo(NOW.plusSeconds(3601));
        phases.ready.add("task-int");
        scanner.scanOnce();

        assertThat(tasks.find("task-int").orElseThrow().status())
                .as("区间结束后的轮询必须发现并聚合").isEqualTo("COMPLETED");
        assertThat(notifications.byTask.keySet()).containsExactly("task-int");
        assertThat(identities.deleted).containsExactly("task-int");
    }

    @Test
    void notificationPersistenceFailureIsCompensatedFromDurableQueue() {
        MemoryPhases phases = new MemoryPhases();
        phases.add(phase("task-1", "phase-1", NOW, "PENDING"));
        MemoryTasks tasks = new MemoryTasks();
        tasks.put(task("task-1", "SCHEDULED", "{\"source\":\"office\",\"tenantCode\":\"TENANT\",\"ownerKey\":\"o\"}"));
        CountingGateway gateway = new CountingGateway();
        FailingNotifications notifications = new FailingNotifications();
        MemoryNotificationRetry retries = new MemoryNotificationRetry();
        TaskScanner scanner = new TaskScanner(phases, tasks, gateway, notifications,
                (checkpoint, identity) -> Optional.empty(), null, null, retries,
                new MemoryIdentities(), null, null, null, Clock.fixed(NOW, ZoneOffset.UTC));

        scanner.scanOnce();

        assertThat(retries.marked).containsExactly("task-1");
        assertThat(tasks.find("task-1").orElseThrow().status()).isEqualTo("COMPLETED");

        notifications.fail = false;
        retries.tasks.add(tasks.find("task-1").orElseThrow());
        scanner.scanOnce();

        assertThat(notifications.recorded).containsExactly("task-1");
        assertThat(retries.marked).isEmpty();
    }

    // ------------------------------------------------------------------ fakes

    private static TaskExecutionGateway gateway(States states, ActionCommandGateway actions, TaskDispatchGuard guard) {
        MemoryIdentities identities = new MemoryIdentities();
        identities.saveForTask("task-1", identity());
        return TaskExecutionGatewayFactory.real(MAPPER, states, actions, identities, guard,
                (identity, type, from, to, cursor) -> new TaskSafetyLedgerReader.Page(List.of(), true, null),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5), () -> null);
    }

    private static TaskScanner scanner(MemoryPhases phases, MemoryTasks tasks, TaskExecutionGateway gateway,
                                       MemoryNotifications notifications, MemoryPlans plans,
                                       TaskExecutionIdentityStore identities) {
        return new TaskScanner(phases, tasks, gateway, notifications,
                (checkpoint, identity) -> Optional.empty(), null, null, null,
                identities, plans, null, null, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TaskExecutionGateway.TaskExecutionRequest request(String source, String phaseJson, String taskJson) {
        return new TaskExecutionGateway.TaskExecutionRequest("task-1", "phase-1", source,
                phaseJson, NOW, taskJson);
    }

    private static String task(String source, String endsAt) {
        String interval = endsAt == null ? "" : "\"interval\":{\"endsAt\":\"" + endsAt + "\"},";
        return "{\"tenantCode\":\"tenant-1\",\"ownerKey\":\"owner-1\",\"source\":\"" + source + "\"," + interval
                + "\"objective\":\"检查\"}";
    }

    private static String action(String id, String kind) {
        return "{\"id\":\"phase-1:" + id + "\",\"deviceId\":\"" + id + "\",\"kind\":\"" + kind + "\","
                + "\"deviceName\":\"" + id + "\",\"location\":\"\",\"command\":{\"power\":\"off\"}}";
    }

    private static TaskFact task(String taskId, String status, String payload) {
        return new TaskFact(taskId, 1, status, payload, taskId);
    }

    private static TaskPhaseStore.TaskPhase phase(String taskId, String phaseId, Instant dueAt, String status) {
        return new TaskPhaseStore.TaskPhase(taskId, phaseId, 1, "company", "{\"actions\":[]}", dueAt, status);
    }

    private static TaskExecutionIdentityStore.TaskExecutionIdentity identity() {
        return new TaskExecutionIdentityStore.TaskExecutionIdentity("tenant-1", "owner-1", "Bearer token");
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static class States implements IotAdapter {
        private final Map<String, Deque<Map<String, Object>>> sequences = new LinkedHashMap<>();
        private final Map<String, AuthorizedDevice> catalog = new LinkedHashMap<>();
        private final IotSourceEnum source;

        States(IotSourceEnum source) {
            this.source = source;
        }

        void register(String deviceId, String kind) {
            catalog.put(deviceId, new AuthorizedDevice(deviceId, kind, deviceId, "", false,
                    null, null, "hub-" + deviceId, null, List.of(), List.of(), Map.of()));
        }

        void script(String deviceId, Map<String, Object> value) {
            script(deviceId, null, value);
        }

        void script(String deviceId, String hub, Map<String, Object> value) {
            sequences.put(deviceId, new ArrayDeque<>(Arrays.asList(value)));
            if (!catalog.containsKey(deviceId)) register(deviceId, "lighting");
            if (hub != null) {
                AuthorizedDevice previous = catalog.get(deviceId);
                catalog.put(deviceId, new AuthorizedDevice(deviceId, previous.kind(), deviceId, "", false,
                        null, null, hub, null, List.of(), List.of(), Map.of()));
            }
        }

        @Override
        public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
            return Mono.just(AuthorizedDeviceScope.of(identity, source, List.copyOf(catalog.values())));
        }

        @Override
        public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
            Map<String, Object> current = new LinkedHashMap<>();
            for (String id : deviceIds) {
                Deque<Map<String, Object>> sequence = sequences.get(id);
                if (sequence == null || sequence.isEmpty()) continue;
                current.put(id, sequence.size() > 1 ? sequence.poll() : sequence.peek());
            }
            return Mono.just(new DeviceStateReadResult(current, NOW.toString()));
        }
    }

    private static class Actions implements ActionCommandGateway {
        private final List<List<String>> dispatchedDeviceIds =
                java.util.Collections.synchronizedList(new ArrayList<>());
        protected String result = "{\"receipts\":[],\"readbacks\":[]}";

        @Override
        public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                             String selectedActionsJson) {
            List<String> ids = new ArrayList<>();
            for (JsonNode action : parse(selectedActionsJson)) ids.add(action.path("deviceId").asText());
            dispatchedDeviceIds.add(ids);
            String resolved = result.contains("%s")
                    ? String.format(result, ids.get(0), ids.get(0)) : result;
            return new ActionDispatchResult(ActionDraftStatusEnum.ACCEPTED, resolved, true);
        }
    }

    private static final class Guard implements TaskDispatchGuard {
        private int dispatchableAfter = Integer.MAX_VALUE;
        private int checked;

        @Override
        public Set<String> reservedDevices(String taskId) {
            return new LinkedHashSet<>();
        }

        @Override
        public synchronized boolean dispatchable(String taskId, String phaseId, Instant now, Instant windowEnd) {
            return checked++ < dispatchableAfter;
        }

        @Override
        public boolean reserve(String taskId, String phaseId, String deviceId, Instant now, Instant windowEnd) {
            return true;
        }

        @Override
        public void yield(String taskId, String phaseId, String deviceId, Instant at) { }
    }

    private static final class CountingGateway implements TaskExecutionGateway {
        private final List<String> executions = new ArrayList<>();

        @Override
        public TaskExecutionResult execute(TaskExecutionRequest request) {
            executions.add(request.phaseId());
            return new TaskExecutionResult("COMPLETED", "{\"outcome\":\"completed\"}", false);
        }
    }

    private static final class InvalidatingGateway implements TaskExecutionGateway {
        private final List<String> executions = new ArrayList<>();
        private final String kind;
        private final String reason;

        InvalidatingGateway(String kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        @Override
        public TaskExecutionResult execute(TaskExecutionRequest request) {
            executions.add(request.phaseId());
            throw new TaskExecutionGateway.DispatchException(reason, false, kind);
        }
    }

    private static final class MemoryPhases implements TaskPhaseStore {
        private final Map<String, TaskPhase> byPhase = new LinkedHashMap<>();
        private final Map<String, String> failureReasons = new LinkedHashMap<>();
        private final Map<String, List<String>> outcomes = new LinkedHashMap<>();

        void add(TaskPhase phase) {
            byPhase.put(phase.phaseId(), phase);
        }

        String status(String phaseId) {
            TaskPhase phase = byPhase.get(phaseId);
            return phase == null ? null : phase.status();
        }

        String failureReason(String phaseId) {
            return failureReasons.get(phaseId);
        }

        @Override
        public List<TaskPhase> due(Instant now, int limit) {
            List<TaskPhase> due = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (!"PENDING".equals(phase.status()) || phase.dueAt().isAfter(now)) continue;
                if (due.size() >= limit) break;
                due.add(phase);
            }
            return due;
        }

        @Override
        public boolean claim(String phaseId, long expectedVersion, Instant claimedAt) {
            TaskPhase phase = byPhase.get(phaseId);
            if (phase == null || phase.version() != expectedVersion || !"PENDING".equals(phase.status())) {
                return false;
            }
            byPhase.put(phaseId, new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), "EXECUTING"));
            return true;
        }

        @Override
        public Optional<TaskPhase> find(String phaseId) {
            return Optional.ofNullable(byPhase.get(phaseId));
        }


        final List<String> ready = new ArrayList<>();

        @Override
        public List<String> readyToFinalize(Instant now, int limit) {
            return List.copyOf(ready);
        }
        @Override
        public boolean finish(String phaseId, long expectedVersion, String status, String payloadJson, Instant endedAt) {
            TaskPhase phase = byPhase.get(phaseId);
            if (phase == null || phase.version() != expectedVersion) return false;
            byPhase.put(phaseId, new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), status));
            JsonNode reason = parse(payloadJson);
            if (reason.hasNonNull("reason")) failureReasons.put(phaseId, reason.path("reason").asText());
            return true;
        }

        @Override
        public int recoverExecuting(Instant at) {
            return recoverInterrupted(at).size();
        }

        @Override
        public List<TaskPhase> recoverInterrupted(Instant at) {
            List<TaskPhase> recovered = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (!"EXECUTING".equals(phase.status())) continue;
                byPhase.put(phase.phaseId(), new TaskPhase(phase.taskId(), phase.phaseId(),
                        phase.version() + 1, phase.source(), phase.payloadJson(), phase.dueAt(), "INTERRUPTED"));
                failureReasons.put(phase.phaseId(), "执行中断，部分指令结果未知");
                recovered.add(byPhase.get(phase.phaseId()));
            }
            return recovered;
        }

        @Override
        public boolean miss(String phaseId, Instant at, String reason) {
            TaskPhase phase = byPhase.get(phaseId);
            if (phase == null || !"PENDING".equals(phase.status())) return false;
            byPhase.put(phaseId, new TaskPhase(phase.taskId(), phase.phaseId(), phase.version() + 1,
                    phase.source(), phase.payloadJson(), phase.dueAt(), "MISSED"));
            failureReasons.put(phaseId, reason);
            return true;
        }

        @Override
        public List<TaskPhase> missElapsed(Instant at) {
            List<TaskPhase> missed = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (!"PENDING".equals(phase.status()) || !phase.dueAt().isBefore(at)) continue;
                missed.add(phase);
            }
            for (TaskPhase phase : missed) {
                byPhase.put(phase.phaseId(), new TaskPhase(phase.taskId(), phase.phaseId(),
                        phase.version() + 1, phase.source(), phase.payloadJson(), phase.dueAt(), "MISSED"));
                failureReasons.put(phase.phaseId(), "服务重启时检查点已过，未下发");
            }
            return missed;
        }

        @Override
        public int cancelPending(String taskId, String reason, Instant at) {
            int cancelled = 0;
            for (TaskPhase phase : byPhase.values()) {
                if (!taskId.equals(phase.taskId()) || !"PENDING".equals(phase.status())) continue;
                byPhase.put(phase.phaseId(), new TaskPhase(phase.taskId(), phase.phaseId(),
                        phase.version() + 1, phase.source(), phase.payloadJson(), phase.dueAt(), "CANCELLED"));
                failureReasons.put(phase.phaseId(), reason);
                cancelled++;
            }
            return cancelled;
        }

        @Override
        public boolean allTerminal(String taskId) {
            return byPhase.values().stream()
                    .noneMatch(phase -> taskId.equals(phase.taskId())
                            && ("PENDING".equals(phase.status()) || "EXECUTING".equals(phase.status())));
        }

        @Override
        public List<String> outcomes(String taskId) {
            List<String> stored = outcomes.get(taskId);
            if (stored != null) return stored;
            List<String> values = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (taskId.equals(phase.taskId())) values.add(phase.status());
            }
            return values;
        }

        @Override
        public List<PhaseResult> terminalPhases(String taskId) {
            List<PhaseResult> results = new ArrayList<>();
            for (TaskPhase phase : byPhase.values()) {
                if (!taskId.equals(phase.taskId())) continue;
                results.add(new PhaseResult(phase.taskId(), phase.phaseId(), phase.dueAt(), phase.status(),
                        phase.payloadJson(), "{\"outcome\":\"completed\"}", NOW, NOW,
                        failureReasons.get(phase.phaseId())));
            }
            return results;
        }
    }

    private static final class MemoryTasks implements TaskFactStore {
        private final Map<String, TaskFact> values = new LinkedHashMap<>();

        void put(TaskFact task) {
            values.put(task.taskId(), task);
        }

        @Override
        public TaskFact save(TaskFact task) {
            values.put(task.taskId(), task);
            return task;
        }

        @Override
        public Optional<TaskFact> find(String taskId) {
            return Optional.ofNullable(values.get(taskId));
        }

        @Override
        public Optional<TaskFact> findByIdempotencyKey(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<TaskFact> updateIfVersion(TaskFact replacement, long expectedVersion) {
            TaskFact current = values.get(replacement.taskId());
            if (current == null || current.version() != expectedVersion) return Optional.empty();
            values.put(replacement.taskId(), replacement);
            return Optional.of(replacement);
        }
    }

    private static class MemoryNotifications implements TaskNotificationStore {
        private final Map<String, TaskNotificationFact> byTask = new LinkedHashMap<>();

        @Override
        public TaskNotificationFact record(TaskNotificationFact notification) {
            byTask.putIfAbsent(notification.taskId(), notification);
            return byTask.get(notification.taskId());
        }

        @Override
        public Optional<TaskNotificationFact> find(String notificationId) {
            return byTask.values().stream()
                    .filter(fact -> fact.notificationId().equals(notificationId))
                    .findFirst();
        }
        @Override
        public List<TaskNotificationFact> list(String tenantCode, String recipientKey, String source,
                                               int limit, String before) {
            return List.copyOf(byTask.values());
        }

    }

    private static final class FailingNotifications extends MemoryNotifications {
        private boolean fail = true;
        private final List<String> recorded = new ArrayList<>();

        @Override
        public TaskNotificationFact record(TaskNotificationFact notification) {
            if (fail) throw new IllegalStateException("inbox unavailable");
            recorded.add(notification.taskId());
            return super.record(notification);
        }
    }

    private static final class MemoryPlans implements TaskPlanStore {
        private final List<String[]> paused = new ArrayList<>();

        @Override
        public TaskPlanFact save(TaskPlanFact plan) {
            return plan;
        }

        @Override
        public Optional<TaskPlanFact> find(String planId) {
            return Optional.empty();
        }

        @Override
        public Optional<TaskPlanFact> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public Optional<TaskPlanFact> pauseForInvalidation(String planId, String reason, Instant now) {
            paused.add(new String[] {planId, reason});
            return Optional.of(new TaskPlanFact(planId, 2, "PAUSED", "{}", planId));
        }
    }

    private static final class MemoryIdentities implements TaskExecutionIdentityStore {
        private final Map<String, TaskExecutionIdentityStore.TaskExecutionIdentity> values = new LinkedHashMap<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public void saveForTask(String taskId, TaskExecutionIdentityStore.TaskExecutionIdentity identity) {
            values.put(taskId, identity);
        }

        @Override
        public void saveForPlan(String planId, TaskExecutionIdentityStore.TaskExecutionIdentity identity) {
        }

        @Override
        public Optional<TaskExecutionIdentityStore.TaskExecutionIdentity> loadForTask(String taskId) {
            return Optional.ofNullable(values.get(taskId));
        }

        @Override
        public void deleteForTask(String taskId) {
            deleted.add(taskId);
            values.remove(taskId);
        }
    }

    private static final class MemoryNotificationRetry implements TaskNotificationRetryStore {
        private final List<TaskFact> tasks = new ArrayList<>();
        private final List<String> marked = new ArrayList<>();

        @Override
        public void markPending(String taskId, String error) {
            marked.add(taskId);
        }

        @Override
        public List<TaskFact> pending(int limit) {
            return List.copyOf(tasks);
        }

        @Override
        public void succeeded(String taskId) {
            marked.remove(taskId);
            tasks.removeIf(task -> task.taskId().equals(taskId));
        }

        @Override
        public void failed(String taskId, String error) {
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceTo(Instant at) {
            now = at;
        }

        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}
