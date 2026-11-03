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
import ltd.qianc.iotagent.action.ActionDraftStatus;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskDispatchGuard;
import ltd.qianc.iotagent.task.TaskExecutionGateway;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStore;
import ltd.qianc.iotagent.task.TaskSafetyLedgerReader;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioral regressions for ticket 10/12 execution defects: unconfirmed
 * receipts never count as completion, a cancel or operator change between two
 * devices prevents the later dispatch, per-device reservations survive across
 * checkpoints and restarts, inspect-only reads are honest observations, and
 * safety checkpoints read real ledger windows with partial-failure honesty.
 */
class RealTaskExecutionGatewayBehaviorTest {
    private static final Instant NOW = Instant.parse("2026-11-02T02:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void acceptedWithoutReadbackIsUnconfirmedAndNeverCompleted() {
        States states = new States();
        states.script("ac-1", state("on", "cool", 20));
        Actions actions = new Actions();
        actions.result = "{\"receipts\":[{\"actionId\":\"phase-1:ac-1\",\"outcome\":\"ACCEPTED\"}],"
                + "\"readbacks\":[{\"actionId\":\"phase-1:ac-1\",\"outcome\":\"unconfirmed\"}]}";
        TaskExecutionGateway gateway = gateway(states, actions, new Guard());

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request(
                "{\n\"read\":{\n\"ac\":{\"mode\":\"adjust_to_26\",\"target\":26},\n\"devices\":["
                        + device("ac-1", "ac") + "]}}",
                task("2026-11-02T12:00:00Z")));

        JsonNode payload = parse(result.payloadJson());
        assertThat(result.outcome()).isEqualTo("UNCONFIRMED");
        assertThat(payload.path("adjustedDevices").asInt()).isZero();
        assertThat(payload.path("devices").path("ac-1").path("disposition").asText()).isEqualTo("unconfirmed");
        assertThat(payload.path("devices").path("ac-1").path("outcome").asText()).isEqualTo("unconfirmed");
        assertThat(payload.path("energyEstimate").isMissingNode()).isTrue();
        assertThat(actions.dispatchedDeviceIds).containsExactly(List.of("ac-1"));
    }

    @Test
    void cancelBetweenTwoDevicesPreventsTheLaterDispatch() {
        States states = new States();
        states.script("ac-1", state("on", "cool", 20));
        states.script("ac-2", state("on", "cool", 20));
        Actions actions = new Actions();
        actions.result = "{\"receipts\":[{\"actionId\":\"%s\",\"outcome\":\"ACCEPTED\"}],"
                + "\"readbacks\":[{\"actionId\":\"%s\",\"outcome\":\"confirmed\","
                + "\"observedState\":{\"temperature\":26}}]}";
        Guard guard = new Guard();
        guard.dispatchableAfterReservations = 1;
        TaskExecutionGateway gateway = gateway(states, actions, guard);

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request(
                "{\n\"read\":{\n\"ac\":{\"mode\":\"adjust_to_26\",\"target\":26},\n\"devices\":["
                        + device("ac-1", "ac") + "," + device("ac-2", "ac") + "]}}",
                task("2026-11-02T12:00:00Z")));

        JsonNode payload = parse(result.payloadJson());
        assertThat(actions.dispatchedDeviceIds).hasSize(1);
        assertThat(actions.dispatchedDeviceIds.get(0)).isEqualTo(List.of("ac-1"));
        assertThat(payload.path("devices").path("ac-1").path("outcome").asText()).isEqualTo("verified");
        assertThat(payload.path("devices").path("ac-2").path("outcome").asText()).isEqualTo("not_dispatched");
        assertThat(payload.path("devices").path("ac-2").path("reason").asText()).contains("任务已停止");
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(guard.reserved).containsExactlyInAnyOrder("ac-1", "ac-2");
    }

    @Test
    void operatorPanelChangeBeforeDispatchYieldsThatDeviceOnly() {
        States states = new States();
        states.script("ac-1", state("on", "cool", 20));
        // Initial classification read sees 20; the immediate pre-dispatch read sees
        // the operator's fresh 26.
        states.script("ac-2", state("on", "cool", 20), state("on", "cool", 26));
        Actions actions = new Actions();
        actions.result = "{\"receipts\":[{\"actionId\":\"%s\",\"outcome\":\"ACCEPTED\"}],"
                + "\"readbacks\":[{\"actionId\":\"%s\",\"outcome\":\"confirmed\","
                + "\"observedState\":{\"temperature\":26}}]}";
        TaskExecutionGateway gateway = gateway(states, actions, new Guard());

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request(
                "{\n\"read\":{\n\"ac\":{\"mode\":\"adjust_to_26\",\"target\":26},\n\"devices\":["
                        + device("ac-1", "ac") + "," + device("ac-2", "ac") + "]}}",
                task("2026-11-02T12:00:00Z")));

        JsonNode payload = parse(result.payloadJson());
        assertThat(actions.dispatchedDeviceIds).containsExactly(List.of("ac-1"));
        assertThat(payload.path("devices").path("ac-2").path("outcome").asText()).isEqualTo("not_dispatched");
        assertThat(payload.path("devices").path("ac-2").path("reason").asText()).contains("让行");
        assertThat(result.outcome()).isEqualTo("PARTIAL");
    }

    @Test
    void reservationSurvivesAcrossCheckpointsAndRestarts() {
        States states = new States();
        states.script("ac-1", state("on", "cool", 20));
        states.script("ac-2", state("on", "cool", 20));
        Actions actions = new Actions();
        actions.result = "{\"receipts\":[{\"actionId\":\"%s\",\"outcome\":\"ACCEPTED\"}],"
                + "\"readbacks\":[{\"actionId\":\"%s\",\"outcome\":\"confirmed\","
                + "\"observedState\":{\"temperature\":26}}]}";
        Guard guard = new Guard();
        guard.reserved.add("ac-1");
        TaskExecutionGateway gateway = gateway(states, actions, guard);

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request(
                "{\n\"read\":{\n\"ac\":{\"mode\":\"adjust_to_26\",\"target\":26},\n\"devices\":["
                        + device("ac-1", "ac") + "," + device("ac-2", "ac") + "]}}",
                task("2026-11-02T12:00:00Z")));

        JsonNode payload = parse(result.payloadJson());
        assertThat(actions.dispatchedDeviceIds).containsExactly(List.of("ac-2"));
        assertThat(payload.path("devices").path("ac-1").path("outcome").asText()).isEqualTo("not_dispatched");
        assertThat(payload.path("devices").path("ac-1").path("reason").asText()).contains("不自动重复调温");
        assertThat(payload.path("devices").path("ac-2").path("outcome").asText()).isEqualTo("verified");
        assertThat(result.outcome()).isEqualTo("PARTIAL");
    }

    @Test
    void inspectOnlyBelowTargetIsObservedSuccessWithTruthfulPotentialEstimate() {
        States states = new States();
        states.script("ac-1", state("on", "cool", 20));
        Actions actions = new Actions();
        TaskExecutionGateway gateway = gateway(states, actions, new Guard());

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request(
                "{\n\"read\":{\n\"ac\":{\"mode\":\"inspect\",\"target\":26},\n\"devices\":["
                        + device("ac-1", "ac") + "]}}",
                task("2026-11-02T12:00:00Z")));

        JsonNode payload = parse(result.payloadJson());
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        assertThat(payload.path("devices").path("ac-1").path("outcome").asText()).isEqualTo("observed");
        assertThat(payload.path("devices").path("ac-1").path("disposition").asText()).isEqualTo("adjustable");
        assertThat(payload.path("adjustedDevices").asInt()).isZero();
        assertThat(actions.dispatchedDeviceIds).isEmpty();
        JsonNode estimate = payload.path("energyEstimate");
        assertThat(estimate.isObject()).isTrue();
        assertThat(estimate.path("potential").asBoolean()).isTrue();
        assertThat(estimate.path("coverage").path("adjusted").asInt()).isZero();
        assertThat(estimate.path("reason").asText()).contains("未调温");
    }

    @Test
    void inspectUnreadableDeviceKeepsUnavailableDistinction() {
        States states = new States();
        states.script("ac-1", Map.of("power", "on"));
        TaskExecutionGateway gateway = gateway(states, new Actions(), new Guard());

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request(
                "{\n\"read\":{\n\"ac\":{\"mode\":\"inspect\",\"target\":26},\n\"devices\":["
                        + device("ac-1", "ac") + "]}}",
                task("2026-11-02T12:00:00Z")));

        JsonNode payload = parse(result.payloadJson());
        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(payload.path("devices").path("ac-1").path("outcome").asText()).isEqualTo("unavailable");
        assertThat(payload.path("devices").path("ac-1").path("disposition").asText()).isEqualTo("unknown");
    }

    @Test
    void safetyWindowIncludesInWindowRecordsExcludesOthersAndReportsPartialFailure() {
        States states = new States();
        states.script("meter-1", Map.of("value", 5));
        states.register("safe-1", "safety", "gas");
        states.register("safe-2", "safety", "smoke");
        SafetyLedger ledger = new SafetyLedger();
        ledger.pages.put("gas", List.of(new TaskSafetyLedgerReader.Page(List.of(
                alert("e-1", "safe-1", "gas", NOW.minus(Duration.ofHours(1)).toString()),
                alert("e-2", "safe-1", "gas", NOW.minus(Duration.ofDays(8)).toString()),
                alert("e-3", "safe-other", "gas", NOW.minus(Duration.ofHours(2)).toString())), true, null)));
        ledger.pages.put("smoke", null);
        ledger.pages.put("water", List.of(new TaskSafetyLedgerReader.Page(List.of(), true, null)));
        TaskExecutionGateway gateway = gateway(states, new Actions(), new Guard(), ledger);

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(request(
                "{\n\"read\":{\n\"devices\":["
                        + device("safe-1", "safety") + "," + device("safe-2", "safety") + ","
                        + device("meter-1", "energy") + "]}}",
                task(null)));

        JsonNode payload = parse(result.payloadJson());
        assertThat(result.outcome()).isEqualTo("PARTIAL");
        assertThat(payload.path("devices").path("safe-1").path("outcome").asText()).isEqualTo("observed");
        assertThat(payload.path("devices").path("safe-1").path("alerts").path("inWindow").asInt()).isEqualTo(1);
        assertThat(payload.path("devices").path("safe-2").path("outcome").asText()).isEqualTo("read_failed");
        assertThat(payload.path("devices").path("safe-2").path("reason").asText()).contains("对应安全告警类别");
        assertThat(payload.path("devices").path("meter-1").path("outcome").asText()).isEqualTo("observed");
        JsonNode alerts = payload.path("observations").get(0);
        assertThat(alerts.path("type").asText()).isEqualTo("alerts");
        assertThat(alerts.path("total").asInt()).isEqualTo(1);
        assertThat(alerts.path("byType").path("gas").asInt()).isEqualTo(1);
        assertThat(alerts.path("coverageComplete").asBoolean()).isFalse();
        assertThat(alerts.path("summaryStatus").asText()).isEqualTo("partial");
        assertThat(alerts.path("failures").get(0).path("type").asText()).isEqualTo("smoke");
        assertThat(alerts.path("windowDays").asInt()).isEqualTo(7);
    }

    @Test
    void safetyCalendarWindowAnchorsOnScheduledDayNotDispatchTime() {
        States states = new States();
        states.register("safe-1", "safety", "gas");
        SafetyLedger ledger = new SafetyLedger();
        ledger.windows = new ArrayList<>();
        // A Nov 1 19:00 Beijing checkpoint is dispatched late on Nov 2.
        // Its previous-day window remains Oct 31, including start and excluding end.
        ledger.pages.put("gas", List.of(new TaskSafetyLedgerReader.Page(List.of(
                alert("e-1", "safe-1", "gas", "2026-10-31T12:00:00Z"),
                alert("e-start", "safe-1", "gas", "2026-10-30T16:00:00Z"),
                alert("e-end", "safe-1", "gas", "2026-10-31T16:00:00Z"),
                alert("e-late", "safe-1", "gas", "2026-11-01T12:00:00Z")), true, null)));
        ledger.pages.put("smoke", List.of(new TaskSafetyLedgerReader.Page(List.of(), true, null)));
        ledger.pages.put("water", List.of(new TaskSafetyLedgerReader.Page(List.of(), true, null)));
        TaskExecutionGateway gateway = gateway(states, new Actions(), new Guard(), ledger);

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(new TaskExecutionGateway.TaskExecutionRequest(
                "task-1", "phase-1", "company",
                "{\n\"read\":{\n\"alertWindow\":\"previous_day\",\n\"devices\":["
                        + device("safe-1", "safety") + "]}}",
                NOW.minus(Duration.ofDays(1)).plus(Duration.ofHours(9)), task(null)));

        JsonNode payload = parse(result.payloadJson());
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        JsonNode window = payload.path("observations").get(0).path("window");
        assertThat(window.path("from").asText()).isEqualTo("2026-10-30T16:00:00Z");
        assertThat(window.path("to").asText()).isEqualTo("2026-10-31T16:00:00Z");
        assertThat(payload.path("observations").get(0).path("total").asInt()).isEqualTo(2);
    }

    @Test
    void previousWeekWindowIsThePreviousNaturalWeekRightOpenOnTheScheduledDay() {
        States states = new States();
        states.register("safe-1", "safety", "gas");
        SafetyLedger ledger = new SafetyLedger();
        // Scheduled Shanghai Wednesday 2026-10-28 (dueAt 2026-10-27T19:00:00Z), dispatched
        // days late on Nov 2: previous_week is [Mon Oct 19 00:00, Mon Oct 26 00:00) Shanghai,
        // start included, end excluded, still anchored on the scheduled day.
        ledger.pages.put("gas", List.of(new TaskSafetyLedgerReader.Page(List.of(
                alert("w-in-start", "safe-1", "gas", "2026-10-18T16:00:00Z"),
                alert("w-mid", "safe-1", "gas", "2026-10-21T02:00:00Z"),
                alert("w-at-end", "safe-1", "gas", "2026-10-25T16:00:00Z"),
                alert("w-after-end", "safe-1", "gas", "2026-10-25T16:00:01Z"),
                alert("w-before-start", "safe-1", "gas", "2026-10-18T15:59:59Z")), true, null)));
        ledger.pages.put("smoke", List.of(new TaskSafetyLedgerReader.Page(List.of(), true, null)));
        ledger.pages.put("water", List.of(new TaskSafetyLedgerReader.Page(List.of(), true, null)));
        TaskExecutionGateway gateway = gateway(states, new Actions(), new Guard(), ledger);

        TaskExecutionGateway.TaskExecutionResult result = gateway.execute(new TaskExecutionGateway.TaskExecutionRequest(
                "task-1", "phase-1", "company",
                "{\n\"read\":{\n\"alertWindow\":\"previous_week\",\n\"devices\":["
                        + device("safe-1", "safety") + "]}}",
                Instant.parse("2026-10-27T19:00:00Z"), task(null)));

        JsonNode payload = parse(result.payloadJson());
        assertThat(result.outcome()).isEqualTo("COMPLETED");
        JsonNode alerts = payload.path("observations").get(0);
        assertThat(alerts.path("window").path("from").asText()).isEqualTo("2026-10-18T16:00:00Z");
        assertThat(alerts.path("window").path("to").asText()).isEqualTo("2026-10-25T16:00:00Z");
        assertThat(alerts.path("total").asInt()).isEqualTo(2);
        assertThat(payload.path("devices").path("safe-1").path("alerts").path("inWindow").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ fakes

    private static TaskExecutionGateway gateway(States states, Actions actions, Guard guard) {
        return gateway(states, actions, guard, new SafetyLedger());
    }

    private static TaskExecutionGateway gateway(States states, Actions actions, Guard guard, SafetyLedger ledger) {
        return TaskExecutionGatewayFactory.real(MAPPER, states, actions, new Identities(),
                guard, ledger, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5), () -> null);
    }

    private static final class Identities implements TaskExecutionIdentityStore {
        @Override
        public void saveForTask(String taskId, TaskExecutionIdentity identity) { }
        @Override
        public void saveForPlan(String planId, TaskExecutionIdentity identity) { }
        @Override
        public Optional<TaskExecutionIdentity> loadForTask(String taskId) {
            return Optional.of(new TaskExecutionIdentity("tenant-1", "owner-1", "token"));
        }
    }

    private static TaskExecutionGateway.TaskExecutionRequest request(String phaseJson, String taskJson) {
        return new TaskExecutionGateway.TaskExecutionRequest("task-1", "phase-1", "company",
                phaseJson, NOW, taskJson);
    }

    private static String task(String endsAt) {
        String interval = endsAt == null ? "" : "\"interval\":{\"endsAt\":\"" + endsAt + "\"},";
        return "{\"tenantCode\":\"tenant-1\",\"ownerKey\":\"owner-1\",\"source\":\"company\"," + interval
                + "\"objective\":\"检查\"}";
    }

    private static String device(String id, String kind) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"name\":\"" + id + "\",\"location\":\"\"}";
    }

    private static Map<String, Object> state(String power, String mode, int temperature) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("power", power);
        value.put("mode", mode);
        value.put("temperature", temperature);
        return value;
    }

    private static TaskSafetyLedgerReader.Alert alert(String eventId, String deviceId, String subtype, String occurredAt) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("eventId", eventId);
        evidence.put("deviceId", deviceId);
        evidence.put("deviceName", deviceId);
        evidence.put("alarm", true);
        return new TaskSafetyLedgerReader.Alert(eventId, deviceId, subtype, occurredAt, evidence);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    private static final class States implements IotAdapter {
        private final Map<String, Deque<Map<String, Object>>> sequences = new LinkedHashMap<>();
        private final Map<String, AuthorizedDevice> catalog = new LinkedHashMap<>();

        void script(String deviceId, Map<String, Object>... values) {
            sequences.put(deviceId, new ArrayDeque<>(Arrays.asList(values)));
            catalog.putIfAbsent(deviceId, fixed(deviceId, deviceId.startsWith("meter-") ? "energy" : "ac", null));
        }

        void register(String deviceId, String kind, String subtype) {
            catalog.put(deviceId, fixed(deviceId, kind, subtype));
        }

        private static AuthorizedDevice fixed(String id, String kind, String subtype) {
            return new AuthorizedDevice(id, kind, id, "", "safety".equals(kind),
                    null, null, null, null, List.of(), List.of(),
                    subtype == null ? Map.of() : Map.of("subtype", subtype));
        }

        @Override
        public Mono<AuthorizedDeviceScope> loadAuthorizedDeviceScope(RequestIdentity identity) {
            return Mono.just(AuthorizedDeviceScope.of(identity, IotSource.COMPANY, List.copyOf(catalog.values())));
        }

        @Override
        public Mono<DeviceStateReadResult> readDeviceStates(RequestIdentity identity, List<String> deviceIds) {
            Map<String, Object> current = new LinkedHashMap<>();
            for (String id : deviceIds) {
                Deque<Map<String, Object>> sequence = sequences.get(id);
                if (sequence == null) continue;
                Map<String, Object> value = sequence.size() > 1 ? sequence.poll() : sequence.peek();
                if (value != null) current.put(id, value);
            }
            return Mono.just(new DeviceStateReadResult(current, NOW.toString()));
        }
    }

    private static final class Actions implements ActionCommandGateway {
        private final List<List<String>> dispatchedDeviceIds = new ArrayList<>();
        private String result = "{\"receipts\":[],\"readbacks\":[]}";

        @Override
        public ActionDispatchResult dispatch(RequestIdentity identity, AuthorizedDeviceScope scope,
                                             String selectedActionsJson) {
            List<String> ids = new ArrayList<>();
            for (JsonNode action : parse(selectedActionsJson)) ids.add(action.path("deviceId").asText());
            dispatchedDeviceIds.add(ids);
            String resolved = result.contains("%s")
                    ? String.format(result, "phase-1:" + ids.get(0), "phase-1:" + ids.get(0)) : result;
            return new ActionDispatchResult(ActionDraftStatus.ACCEPTED, resolved, true);
        }
    }

    private static final class Guard implements TaskDispatchGuard {
        private final Set<String> reserved = new LinkedHashSet<>();
        private boolean dispatchable = true;
        private int dispatchableAfterReservations = Integer.MAX_VALUE;

        @Override
        public Set<String> reservedDevices(String taskId) {
            return new LinkedHashSet<>(reserved);
        }

        @Override
        public boolean dispatchable(String taskId, String phaseId, Instant now, Instant windowEnd) {
            return dispatchable && reserved.size() < dispatchableAfterReservations;
        }

        @Override
        public boolean reserve(String taskId, String phaseId, String deviceId, Instant now, Instant windowEnd) {
            if (!dispatchable(taskId, phaseId, now, windowEnd)) return false;
            if (reserved.contains(deviceId)) return false;
            reserved.add(deviceId);
            return true;
        }

        @Override
        public void yield(String taskId, String phaseId, String deviceId, Instant at) {
            reserved.add(deviceId);
        }
    }

    private static final class SafetyLedger implements TaskSafetyLedgerReader {
        private final Map<String, List<Page>> pages = new LinkedHashMap<>();
        private List<String[]> windows = new ArrayList<>();

        @Override
        public Page read(RequestIdentity identity, String ledgerType, Instant from, Instant to, String cursor) {
            if (windows != null) windows.add(new String[] {ledgerType, from.toString(), to.toString()});
            List<Page> available = pages.get(ledgerType);
            if (available == null) {
                throw new ltd.qianc.iotagent.error.BusinessException("SAFETY_LEDGER_READ_FAILED", "安全台账读取失败", 503);
            }
            return available.isEmpty() ? new Page(List.of(), true, null) : available.get(0);
        }
    }
}
