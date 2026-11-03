package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.action.ActionDispatchException;
import ltd.qianc.iotagent.action.ActionDispatchResult;
import ltd.qianc.iotagent.action.ActionDraftStatus;
import ltd.qianc.iotagent.action.ActionReceiptOutcome;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskExecutionGateway;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStore;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskDispatchGuard;
import ltd.qianc.iotagent.task.TaskSafetyLedgerReader;

/**
 * Production-shape task adapter. It reloads the protected execution identity and
 * a fresh authorized catalog before any read or write, distinguishes accepted
 * receipts from platform readback, and never substitutes a fixture or local
 * device protocol.
 */
public final class RealTaskExecutionGateway implements TaskExecutionGateway {
    private static final List<Integer> AC_CADENCES = TaskProposalNormalizer.AC_CADENCES;
    private static final int AC_DEFAULT_TARGET = 26;
    private static final double RATE_PER_DEGREE = 0.07;
    private static final double POWER_KW = 2.5;
    private static final double PRICE_PER_KWH = 0.8;
    private static final double MIN_ESTIMATE_KWH = 0.1;
    private static final ZoneId SAFETY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int SAFETY_MAX_PAGES = 20;

    private final ObjectMapper mapper;
    private final IotAdapter iot;
    private final ActionCommandGateway actions;
    private final TaskExecutionIdentityStore identities;
    private final TaskDispatchGuard guard;
    private final TaskSafetyLedgerReader safetyLedger;
    private final Clock clock;
    private final Duration scopeTimeout;
    private final java.util.function.Supplier<ltd.qianc.iotagent.task.TaskCheckpointRunner> checkpoints;

    public RealTaskExecutionGateway(ObjectMapper mapper, IotAdapter iot, ActionCommandGateway actions,
                                    TaskExecutionIdentityStore identities, TaskDispatchGuard guard,
                                    TaskSafetyLedgerReader safetyLedger, Clock clock, Duration scopeTimeout,
                                    java.util.function.Supplier<ltd.qianc.iotagent.task.TaskCheckpointRunner> checkpoints) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.safetyLedger = Objects.requireNonNull(safetyLedger, "safetyLedger");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.scopeTimeout = scopeTimeout == null || scopeTimeout.isNegative() || scopeTimeout.isZero()
                ? Duration.ofSeconds(30) : scopeTimeout;
    }

    @Override
    public TaskExecutionResult execute(TaskExecutionRequest request) {
        JsonNode phase = read(request.payloadJson());
        JsonNode task = read(request.taskPayloadJson());
        TaskExecutionIdentityStore.TaskExecutionIdentity identity;
        try {
            identity = identities.loadForTask(request.taskId())
                    .orElseThrow(() -> new DispatchException("任务托管执行身份缺失，无法核验授权", false));
        } catch (DispatchException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DispatchException("任务托管执行身份不可用，拒绝执行", failure, false);
        }
        if (!identity.tenantCode().equals(task.path("tenantCode").asText(""))
                || !identity.ownerKey().equals(task.path("ownerKey").asText(""))) {
            throw new DispatchException("任务托管身份与任务归属不一致，拒绝执行", false);
        }
        RequestIdentity gatewayIdentity;
        try {
            gatewayIdentity = new RequestIdentity(identity.authorization(), identity.tenantCode(),
                    IotSource.parse(request.source()), identity.ownerKey());
        } catch (RuntimeException invalid) {
            throw new DispatchException("任务执行身份或来源无效", false);
        }

        JsonNode read = phase.path("read");
        JsonNode actionsNode = phase.path("actions");
        boolean hasActions = actionsNode.isArray() && !actionsNode.isEmpty();
        boolean hasAc = read.path("ac").isObject();
        boolean hasReadDevices = read.path("devices").isArray() && !read.path("devices").isEmpty();
        if (task.path("reportRequest").isObject() && !hasActions && !hasAc && !hasReadDevices) {
            ObjectNode result = mapper.createObjectNode();
            result.put("outcome", "COMPLETED").put("executedAt", clock.instant().toString())
                    .put("dispatchStarted", false);
            result.set("reportRequest", task.path("reportRequest").deepCopy());
            return new TaskExecutionResult("COMPLETED", result.toString(), false);
        }
        if (!hasActions && !hasAc && !hasReadDevices) {
            throw new DispatchException("任务阶段没有可执行的读取或动作", false);
        }

        AuthorizedDeviceScope scope = loadScope(gatewayIdentity);
        validateTaskAuthorization(task, phase, scope);
        if (read.path("agent").isObject()) {
            var checkpoint = checkpoints.get();
            if (checkpoint == null) throw new DispatchException("只读检查点 Agent Engine 未配置", false);
            List<String> meters = new ArrayList<>();
            for (JsonNode device : read.path("devices")) {
                String id = device.path("id").asText();
                AuthorizedDevice meter = scope.device(id).orElseThrow(() -> new DispatchException("电表授权已变化", false));
                if (!"energy".equals(meter.kind())) throw new DispatchException("检查点只能读取已确认的电表", false);
                meters.add(id);
            }
            try {
                return checkpoint.execute(gatewayIdentity, request, meters, mapper.convertValue(read.path("agent"), Map.class),
                        () -> guard.dispatchable(request.taskId(), request.phaseId(), clock.instant(), null));
            } catch (RuntimeException failure) {
                throw new DispatchException("只读检查点未完成；没有下发设备控制", failure, false);
            }
        }
        if (hasAc) return executeAcInspection(gatewayIdentity, scope, phase, task, request);
        if (hasActions) return executeActions(gatewayIdentity, scope, phase, task, request);
        return executeRead(gatewayIdentity, scope, phase, request);
    }

    private TaskExecutionResult executeActions(RequestIdentity identity, AuthorizedDeviceScope scope,
                                               JsonNode phase, JsonNode task, TaskExecutionRequest request) {
        ArrayNode selected = mapper.createArrayNode();
        for (JsonNode action : phase.path("actions")) selected.add(action);
        List<String> ids = new ArrayList<>();
        for (JsonNode action : selected) ids.add(action.path("deviceId").asText());
        DeviceStateReadResult before = readStates(identity, ids);
        Map<String, Object> observed = before == null ? Map.of() : new LinkedHashMap<>(before.states());
        ObjectNode result = mapper.createObjectNode();
        ObjectNode devices = mapper.createObjectNode();
        boolean dispatchStarted = false;
        try {
            AuthorizedDeviceScope fresh = loadScope(identity);
            validateTaskAuthorization(task, phase, fresh);
            ActionDispatchResult dispatch = actions.dispatch(identity, fresh, selected.toString(), observed);
            dispatchStarted = dispatch.dispatchStarted();
            result.put("gateway", "customer").put("dispatchStarted", dispatchStarted);
            JsonNode gatewayResult = read(dispatch.resultJson());
            result.set("receipts", gatewayResult.path("receipts").deepCopy());
            result.set("readbacks", gatewayResult.path("readbacks").deepCopy());
            result.set("readback", gatewayResult.path("readback").deepCopy());
            result.set("observedStates", gatewayResult.path("observedStates").deepCopy());
            result.put("receiptOutcome", dispatch.receiptOutcome().name());
            result.put("gatewayStatus", dispatch.status().name());
            String outcome = devicesOutcome(selected, gatewayResult, devices);
            result.put("outcome", outcome).put("executedAt", clock.instant().toString());
            result.set("devices", mapper.valueToTree(devices));
            return new TaskExecutionResult(outcome, result.toString(), dispatchStarted);
        } catch (ActionDispatchException interrupted) {
            throw new DispatchException("设备指令执行中断，部分结果未知", interrupted, interrupted.dispatchStarted());
        } catch (ltd.qianc.iotagent.error.BusinessException unavailable) {
            throw new DispatchException(unavailable.getMessage() == null ? "设备控制适配器不可用" : unavailable.getMessage(), false);
        } catch (IllegalArgumentException rejected) {
            throw new DispatchException(rejected.getMessage() == null ? "设备指令校验失败" : rejected.getMessage(), false);
        }
    }

    private String devicesOutcome(ArrayNode actions, JsonNode gatewayResult, ObjectNode devices) {
        Map<String, String> receiptByAction = new LinkedHashMap<>();
        Map<String, String> readbackById = new LinkedHashMap<>();
        Map<String, JsonNode> readbackNode = new LinkedHashMap<>();
        for (JsonNode receipt : gatewayResult.path("receipts")) {
            receiptByAction.put(receipt.path("actionId").asText(""), receipt.path("outcome").asText("UNKNOWN"));
        }
        for (JsonNode readback : gatewayResult.path("readbacks")) {
            String id = readback.path("actionId").asText("");
            readbackById.put(id, readback.path("outcome").asText("unconfirmed"));
            readbackNode.put(id, readback);
        }
        JsonNode observedStates = gatewayResult.path("observedStates");
        int verified = 0, uncertain = 0, failed = 0, satisfied = 0;
        for (JsonNode action : actions) {
            String actionId = action.path("id").asText(action.path("deviceId").asText());
            String deviceId = action.path("deviceId").asText();
            String receipt = receiptByAction.getOrDefault(actionId, "UNKNOWN");
            String readback = readbackById.getOrDefault(actionId,
                    gatewayResult.path("readback").path(actionId).asText("unconfirmed"));
            String outcome;
            String reason = null;
            if ("ALREADY_SATISFIED".equals(receipt)) {
                outcome = "satisfied";
                satisfied++;
            } else if ("REJECTED".equals(receipt)) {
                outcome = "rejected";
                reason = "上游拒绝设备指令";
                failed++;
            } else if ("ACCEPTED".equals(receipt) && "confirmed".equals(readback)) {
                outcome = "verified";
                verified++;
            } else if ("ACCEPTED".equals(receipt)) {
                outcome = "unconfirmed";
                reason = "指令已受理，尚未取得平台实测回读";
                uncertain++;
            } else {
                outcome = "unknown";
                reason = "设备指令结果未知";
                uncertain++;
            }
            ObjectNode device = mapper.createObjectNode();
            device.put("deviceId", deviceId).put("kind", action.path("kind").asText())
                    .put("name", action.path("deviceName").asText(action.path("name").asText(deviceId)))
                    .put("location", action.path("location").asText(""))
                    .put("outcome", outcome);
            if (reason != null) device.put("reason", reason);
            device.set("target", action.path("command").deepCopy());
            device.put("receipt", receipt);
            device.put("readback", readback);
            JsonNode observed = observedStates.path(deviceId);
            if (!observed.isMissingNode() && !observed.isNull()) device.set("observed", observed.deepCopy());
            JsonNode readbackValue = readbackNode.get(actionId);
            if (readbackValue != null && readbackValue.has("observedAt")) {
                device.put("observedAt", readbackValue.path("observedAt").asText());
            }
            devices.set(deviceId, device);
        }
        if (failed > 0) return verified + satisfied > 0 ? "PARTIAL" : "FAILED";
        if (uncertain > 0) return verified + satisfied > 0 ? "PARTIAL" : "UNCONFIRMED";
        return "COMPLETED";
    }

    private TaskExecutionResult executeRead(RequestIdentity identity, AuthorizedDeviceScope scope,
                                            JsonNode phase, TaskExecutionRequest request) {
        JsonNode devicesNode = phase.path("read").path("devices");
        List<String> stateIds = new ArrayList<>();
        List<JsonNode> safetyDevices = new ArrayList<>();
        for (JsonNode device : devicesNode) {
            if ("safety".equalsIgnoreCase(device.path("kind").asText(""))) safetyDevices.add(device);
            else stateIds.add(device.path("id").asText());
        }
        DeviceStateReadResult states = stateIds.isEmpty()
                ? new DeviceStateReadResult(Map.of(), clock.instant().toString())
                : readStates(identity, stateIds);
        ObjectNode result = mapper.createObjectNode();
        ObjectNode devices = mapper.createObjectNode();
        ArrayNode observationBlocks = mapper.createArrayNode();
        int observed = 0, unavailable = 0, total = 0;
        for (JsonNode device : devicesNode) {
            if (!"safety".equalsIgnoreCase(device.path("kind").asText(""))) continue;
            total++;
            String id = device.path("id").asText();
            ObjectNode view = mapper.createObjectNode();
            view.put("deviceId", id).put("kind", "safety")
                    .put("name", device.path("name").asText(id))
                    .put("location", device.path("location").asText(""));
            view.put("outcome", "read_failed").put("reason", "安全台账读取失败");
            unavailable++;
            devices.set(id, view);
        }
        for (String id : stateIds) {
            total++;
            Object state = states == null ? null : states.states().get(id);
            ObjectNode view = mapper.createObjectNode();
            JsonNode definition = readDevice(devicesNode, id);
            view.put("deviceId", id).put("kind", definition.path("kind").asText())
                    .put("name", definition.path("name").asText(id))
                    .put("location", definition.path("location").asText(""));
            if (state == null) {
                view.put("outcome", "unavailable").put("reason", "上游未返回该设备的有效读数");
                unavailable++;
            } else {
                view.put("outcome", "observed");
                view.set("observed", mapper.valueToTree(state));
                if (states.observedAtByDevice().containsKey(id)) view.put("observedAt", states.observedAtByDevice().get(id));
                observed++;
            }
            devices.set(id, view);
        }
        if (!safetyDevices.isEmpty()) {
            // Safety devices are judged on real ledger history inside the
            // requested window, never inferred from current device state.
            SafetyWindowRead window = readSafetyWindow(identity, phase, request, safetyDevices, scope);
            for (JsonNode device : safetyDevices) {
                String id = device.path("id").asText();
                ObjectNode view = (ObjectNode) devices.get(id);
                if (view == null) continue;
                boolean failed = window.failedDevices().contains(id);
                if (!failed) {
                    view.remove("reason");
                    view.put("outcome", "observed");
                    view.putObject("alerts").put("inWindow", window.alertsByDevice().getOrDefault(id, 0));
                    unavailable--;
                    observed++;
                } else {
                    view.put("reason", window.reasons().getOrDefault(id, "安全台账读取失败"));
                }
            }
            if (window.block() != null) observationBlocks.add(window.block());
        }
        String outcome = observed == total ? "COMPLETED" : observed > 0 ? "PARTIAL" : "FAILED";
        result.put("outcome", outcome).put("executedAt", clock.instant().toString())
                .put("dispatchStarted", false).put("observedAt", states == null ? null : states.observedAt());
        result.set("devices", mapper.valueToTree(devices));
        result.set("observations", observationBlocks);
        return new TaskExecutionResult(outcome, result.toString(), false);
    }

    private static JsonNode readDevice(JsonNode devicesNode, String id) {
        for (JsonNode device : devicesNode) {
            if (id.equals(device.path("id").asText())) return device;
        }
        return mapperMissing();
    }
    /** Runtime window semantics: calendar windows are half-open on the scheduled-day anchor; trailing windows end now. */
    private SafetyWindowRead readSafetyWindow(RequestIdentity identity, JsonNode phase,
                                              TaskExecutionRequest request, List<JsonNode> safetyDevices,
                                              AuthorizedDeviceScope scope) {
        JsonNode readNode = phase.path("read");
        String alertWindow = readNode.path("alertWindow").asText("");
        boolean calendar = readNode.has("alertWindow") && !readNode.path("alertWindow").isNull();
        JsonNode alertDays = readNode.get("alertDays");
        if (calendar && !("previous_day".equals(alertWindow) || "previous_week".equals(alertWindow))) {
            throw new DispatchException("安全告警窗口参数无效", false);
        }
        if (alertDays != null && !alertDays.isNull()
                && (calendar || !alertDays.isIntegralNumber() || alertDays.asInt() < 1 || alertDays.asInt() > 365)) {
            throw new DispatchException("安全告警窗口参数无效", false);
        }
        boolean previousWeek = "previous_week".equals(alertWindow);
        int days = previousWeek ? 7 : calendar ? 1
                : alertDays == null || alertDays.isNull() ? 7 : alertDays.asInt();
        LocalDate plannedDay = request.dueAt().atZone(SAFETY_ZONE).toLocalDate();
        Instant to = calendar
                ? plannedDay.atStartOfDay(SAFETY_ZONE).toInstant()
                        .minus(Duration.ofDays(previousWeek ? (plannedDay.getDayOfWeek().getValue() + 6) % 7 : 0))
                : clock.instant();
        Instant from = to.minus(Duration.ofDays(days));

        Map<String, List<TaskSafetyLedgerReader.Alert>> alertsByType = new LinkedHashMap<>();
        Map<String, String> typeFailures = new LinkedHashMap<>();
        Map<String, Boolean> typeComplete = new LinkedHashMap<>();
        for (String type : new String[] {"smoke", "gas", "water"}) {
            try {
                List<TaskSafetyLedgerReader.Alert> alerts = new ArrayList<>();
                String cursor = null;
                boolean complete = false;
                for (int page = 0; page < SAFETY_MAX_PAGES; page++) {
                    TaskSafetyLedgerReader.Page result = safetyLedger.read(identity, type, from, to, cursor);
                    alerts.addAll(result.alerts());
                    if (result.nextCursor() == null) {
                        complete = result.coverageComplete();
                        break;
                    }
                    cursor = result.nextCursor();
                }
                alertsByType.put(type, alerts);
                typeComplete.put(type, complete);
            } catch (RuntimeException failure) {
                typeFailures.put(type, failure.getMessage() == null ? "安全台账读取失败" : failure.getMessage());
            }
        }
        Set<String> deviceIds = new LinkedHashSet<>();
        for (JsonNode device : safetyDevices) deviceIds.add(device.path("id").asText());
        List<TaskSafetyLedgerReader.Alert> windowAlerts = new ArrayList<>();
        for (List<TaskSafetyLedgerReader.Alert> alerts : alertsByType.values()) windowAlerts.addAll(alerts);
        windowAlerts.sort((left, right) -> right.occurredAt().compareTo(left.occurredAt()));
        List<TaskSafetyLedgerReader.Alert> rows = new ArrayList<>();
        Map<String, Integer> alertsByDevice = new LinkedHashMap<>();
        Map<String, Integer> byTypeCount = new LinkedHashMap<>();
        for (TaskSafetyLedgerReader.Alert alert : windowAlerts) {
            Instant occurred = parseInstant(alert.occurredAt());
            if (occurred == null) continue;
            boolean inWindow = !occurred.isBefore(from)
                    && (calendar ? occurred.isBefore(to) : !occurred.isAfter(to));
            if (!inWindow || !deviceIds.contains(alert.deviceId())) continue;
            rows.add(alert);
            alertsByDevice.merge(alert.deviceId(), 1, Integer::sum);
            byTypeCount.merge(alert.subtype() == null ? "unknown" : alert.subtype(), 1, Integer::sum);
        }
        boolean coverageComplete = typeFailures.isEmpty()
                && typeComplete.values().stream().allMatch(Boolean::booleanValue);
        Set<String> failedTypes = new LinkedHashSet<>(typeFailures.keySet());
        typeComplete.forEach((type, complete) -> {
            if (!complete) failedTypes.add(type);
        });
        Map<String, String> reasons = new LinkedHashMap<>();
        for (JsonNode device : safetyDevices) {
            String id = device.path("id").asText();
            String subtype = safetySubtype(scope, id);
            if (subtype != null) {
                // A gas sensor stays observed when only the smoke ledger failed;
                // its own category is what its outcome depends on.
                if (failedTypes.contains(subtype)) reasons.put(id, "对应安全告警类别读取失败或分页未完成");
            } else if (!coverageComplete) {
                reasons.put(id, "安全告警统计范围不完整");
            }
        }
        ObjectNode block = null;
        if (!alertsByType.isEmpty()) {
            block = mapper.createObjectNode();
            block.put("type", "alerts").put("windowDays", days);
            block.putObject("window").put("from", from.toString()).put("to", to.toString())
                    .put("timezone", "Asia/Shanghai");
            block.put("total", rows.size());
            block.put("uniqueDevices", alertsByDevice.size());
            ObjectNode types = block.putObject("byType");
            byTypeCount.forEach(types::put);
            ObjectNode perDevice = block.putObject("byDevice");
            alertsByDevice.forEach(perDevice::put);
            block.put("coverageComplete", coverageComplete);
            block.put("summaryStatus", coverageComplete ? "complete" : "partial");
            ArrayNode failures = block.putArray("failures");
            typeFailures.forEach((type, message) -> failures.addObject().put("type", type).put("message", message));
            if (!windowAlerts.isEmpty()) {
                TaskSafetyLedgerReader.Alert latest = windowAlerts.get(0);
                block.putObject("latest").put("time", latest.occurredAt())
                        .put("deviceName", evidenceText(latest, "deviceName"))
                        .put("type", latest.subtype() == null ? "" : latest.subtype())
                        .put("report", evidenceText(latest, "report"));
            } else {
                block.putNull("latest");
            }
            if (!rows.isEmpty()) {
                TaskSafetyLedgerReader.Alert latest = rows.get(0);
                block.putObject("windowLatest").put("time", latest.occurredAt())
                        .put("deviceName", evidenceText(latest, "deviceName"))
                        .put("type", latest.subtype() == null ? "" : latest.subtype())
                        .put("report", evidenceText(latest, "report"));
            } else {
                block.putNull("windowLatest");
            }
            ArrayNode records = block.putArray("records");
            for (TaskSafetyLedgerReader.Alert alert : rows.subList(0, Math.min(8, rows.size()))) {
                records.add(mapper.valueToTree(alert.evidence()));
            }
        }
        return new SafetyWindowRead(reasons.keySet(), reasons, alertsByDevice, block);
    }

    private String safetySubtype(AuthorizedDeviceScope scope, String deviceId) {
        AuthorizedDevice device = scope.device(deviceId).orElse(null);
        if (device == null) return null;
        Object raw = device.metadata() == null ? null : device.metadata().get("subtype");
        if (raw == null) return null;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "smoke", "gas", "water" -> value;
            case "flooding" -> "water";
            default -> value.contains("烟") ? "smoke"
                    : value.contains("燃") || value.contains("气") ? "gas"
                    : value.contains("水") ? "water"
                    : null;
        };
    }

    private static String evidenceText(TaskSafetyLedgerReader.Alert alert, String field) {
        Object value = alert.evidence().get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private record SafetyWindowRead(Set<String> failedDevices, Map<String, String> reasons,
                                    Map<String, Integer> alertsByDevice, ObjectNode block) {
    }

    private static JsonNode mapperMissing() {
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private TaskExecutionResult executeAcInspection(RequestIdentity identity, AuthorizedDeviceScope scope,
                                                    JsonNode phase, JsonNode task, TaskExecutionRequest request) {
        JsonNode ac = phase.path("read").path("ac");
        JsonNode devicesNode = phase.path("read").path("devices");
        if (!devicesNode.isArray() || devicesNode.isEmpty()) {
            throw new DispatchException("空调巡检缺少目标设备", false);
        }
        int target = ac.path("target").asInt(ac.path("action").path("setpoint").asInt(AC_DEFAULT_TARGET));
        boolean adjusting = "adjust_to_26".equals(ac.path("mode").asText("inspect"));
        List<String> ids = new ArrayList<>();
        for (JsonNode device : devicesNode) ids.add(device.path("id").asText());
        DeviceStateReadResult initial = readStates(identity, ids);
        if (initial == null) return failedAc(devicesNode, "读取空调状态失败", false);
        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (JsonNode device : devicesNode) {
            String id = device.path("id").asText();
            decisions.put(id, classify(initial.states().get(id), target));
        }
        ObjectNode devices = mapper.createObjectNode();
        ObjectNode observations = mapper.createObjectNode();
        observations.put("type", "ac_inspection").put("mode", adjusting ? "adjust_to_26" : "inspect")
                .put("observedAt", clock.instant().toString());
        observations.putObject("criteria").put("power", "on").put("mode", "cool")
                .putObject("setpoint").put("operator", "lt").put("value", target);
        Map<String, Double> adjustedBaseline = new LinkedHashMap<>();
        Map<String, Double> potentialBaseline = new LinkedHashMap<>();
        boolean dispatchStarted = false;
        Instant taskWindowEnd = windowEnd(task);

        if (adjusting) {
            Set<String> reserved = durableReservations(request.taskId());
            for (JsonNode device : devicesNode) {
                String id = device.path("id").asText();
                if (!"adjustable".equals(decisions.get(id).disposition())) continue;
                if (reserved.contains(id)) {
                    decisions.put(id, new Decision("not_dispatched", "本任务此前已尝试或已让行该设备，不自动重复调温", "not_dispatched"));
                    continue;
                }
                // Task dispatchability, the device's live condition and the
                // authorization are rechecked immediately before this single
                // dispatch; the durable reservation is taken first so no later
                // checkpoint, concurrent scanner or restart can repeat it.
                if (!guard.dispatchable(request.taskId(), request.phaseId(), clock.instant(), taskWindowEnd)) {
                    decisions.put(id, new Decision("not_dispatched", "任务已停止或有效时段已结束", "not_dispatched"));
                    guard.yield(request.taskId(), request.phaseId(), id, clock.instant());
                    continue;
                }
                DeviceStateReadResult latest = readStates(identity, List.of(id));
                Decision current = classify(latest.states().get(id), target);
                if ("unknown".equals(current.disposition())) {
                    decisions.put(id, new Decision("not_dispatched", "下发前状态无法判断，本区间让行", "not_dispatched"));
                    guard.yield(request.taskId(), request.phaseId(), id, clock.instant());
                    continue;
                }
                if (!sameCondition(initial.states().get(id), latest.states().get(id))) {
                    decisions.put(id, new Decision("not_dispatched", "设备参数已变化，本区间让行其它操作", "not_dispatched"));
                    guard.yield(request.taskId(), request.phaseId(), id, clock.instant());
                    continue;
                }
                if (!"adjustable".equals(current.disposition())) {
                    decisions.put(id, new Decision("satisfied", "当前无需调整", "satisfied"));
                    guard.yield(request.taskId(), request.phaseId(), id, clock.instant());
                    continue;
                }
                if (!guard.reserve(request.taskId(), request.phaseId(), id, clock.instant(), taskWindowEnd)) {
                    decisions.put(id, new Decision("not_dispatched", "任务已停止或有效时段已结束", "not_dispatched"));
                    continue;
                }
                ArrayNode single = mapper.createArrayNode();
                ObjectNode action = mapper.createObjectNode();
                action.put("id", request.phaseId() + ":" + id);
                action.put("deviceId", id);
                action.put("kind", "ac");
                action.put("deviceName", device.path("name").asText(id));
                action.put("location", device.path("location").asText(""));
                action.putObject("command").put("temperature", target);
                single.add(action);
                try {
                    AuthorizedDeviceScope fresh = loadScope(identity);
                    validateTaskAuthorization(task, phase, fresh);
                    Map<String, Object> observed = new LinkedHashMap<>();
                    observed.put(id, latest.states().get(id));
                    ActionDispatchResult dispatch = actions.dispatch(identity, fresh, single.toString(), observed);
                    dispatchStarted |= dispatch.dispatchStarted();
                    applyAcDispatch(decisions, single, read(dispatch.resultJson()), adjustedBaseline);
                } catch (ActionDispatchException interrupted) {
                    throw new DispatchException("空调调温执行中断，部分指令结果未知", interrupted, interrupted.dispatchStarted());
                } catch (ltd.qianc.iotagent.error.BusinessException unavailable) {
                    throw new DispatchException(unavailable.getMessage() == null ? "设备控制适配器不可用" : unavailable.getMessage(), false);
                } catch (IllegalArgumentException rejected) {
                    throw new DispatchException(rejected.getMessage() == null ? "空调调温校验失败" : rejected.getMessage(), false);
                }
            }
        }

        ArrayNode deviceArray = mapper.createArrayNode();
        int adjusted = 0, success = 0, unconfirmed = 0, total = 0;
        for (JsonNode device : devicesNode) {
            String id = device.path("id").asText();
            Decision decision = decisions.get(id);
            Object state = initial.states().get(id);
            JsonNode stateNode = state == null ? mapper.nullNode() : mapper.valueToTree(state);
            String outcome = deviceOutcome(decision, adjusting);
            total++;
            if ("verified".equals(outcome)) adjusted++;
            if (isSuccessOutcome(outcome)) success++;
            else if ("unconfirmed".equals(outcome) || "unknown".equals(outcome)) unconfirmed++;
            if (!adjusting && "adjustable".equals(decision.disposition())
                    && decision.initialSetpoint() != null) {
                potentialBaseline.put(id, decision.initialSetpoint());
            }
            ObjectNode view = mapper.createObjectNode();
            view.put("deviceId", id).put("name", device.path("name").asText(id))
                    .put("location", device.path("location").asText(""));
            view.set("power", stateNode.path("power"));
            view.set("mode", stateNode.path("mode"));
            view.set("setpoint", stateNode.path("temperature"));
            view.put("reportedAt", stateNode.path("reportedAt").asText(null));
            view.put("observedAt", clock.instant().toString());
            view.put("disposition", decision.disposition());
            if (decision.reason() != null) view.put("reason", decision.reason());
            view.put("outcome", outcome);
            if (decision.afterSetpoint() != null) view.put("afterSetpoint", decision.afterSetpoint());
            deviceArray.add(view);
            ObjectNode result = mapper.createObjectNode();
            result.put("deviceId", id).put("kind", "ac").put("name", device.path("name").asText(id))
                    .put("location", device.path("location").asText(""))
                    .put("outcome", outcome);
            result.put("disposition", decision.disposition());
            if (decision.reason() != null) result.put("reason", decision.reason());
            result.set("observed", stateNode.deepCopy());
            if (decision.afterSetpoint() != null) result.put("afterSetpoint", decision.afterSetpoint());
            devices.set(id, result);
        }
        observations.set("devices", deviceArray);
        ObjectNode result = mapper.createObjectNode();
        // An accepted receipt without platform readback is never success; the
        // whole checkpoint can only be COMPLETED when every device ended in an
        // observed, verified or satisfied outcome.
        String outcome = success == total ? "COMPLETED"
                : success > 0 ? "PARTIAL"
                : unconfirmed == total ? "UNCONFIRMED"
                : unconfirmed > 0 ? "PARTIAL"
                : "FAILED";
        result.put("outcome", outcome).put("executedAt", clock.instant().toString())
                .put("dispatchStarted", dispatchStarted).put("mode", adjusting ? "adjust_to_26" : "inspect")
                .put("target", target).put("adjustedDevices", adjusted);
        result.set("devices", mapper.valueToTree(devices));
        result.set("observations", mapper.createArrayNode().add(observations));
        ObjectNode estimate = adjusting
                ? acEnergyEstimate(task, phase, adjustedBaseline, adjusted, false)
                : acEnergyEstimate(task, phase, potentialBaseline, 0, true);
        if (estimate != null) result.set("energyEstimate", estimate);
        return new TaskExecutionResult(outcome, result.toString(), dispatchStarted);
    }

    private Set<String> durableReservations(String taskId) {
        try {
            return guard.reservedDevices(taskId);
        } catch (RuntimeException unavailable) {
            throw new DispatchException("任务调温预约事实暂不可读", unavailable, false);
        }
    }

    private Instant windowEnd(JsonNode task) {
        JsonNode endsAt = task.path("interval").path("endsAt");
        if (!endsAt.isTextual()) return null;
        try {
            return Instant.parse(endsAt.asText());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean isSuccessOutcome(String outcome) {
        return "verified".equals(outcome) || "satisfied".equals(outcome) || "observed".equals(outcome);
    }

    /** Per-device outcome: dispatch results win; pure reads are honest observations. */
    private static String deviceOutcome(Decision decision, boolean adjusting) {
        if (decision.outcome() != null) return decision.outcome();
        return switch (decision.disposition()) {
            case "unknown" -> "unavailable";
            case "not_needed" -> adjusting ? "satisfied" : "observed";
            // An adjusting checkpoint that somehow kept its classification never
            // verified anything; count it uncertain instead of success.
            default -> adjusting ? "unknown" : "observed";
        };
    }

    private void applyAcDispatch(Map<String, Decision> decisions, ArrayNode targets,
                                 JsonNode gateway, Map<String, Double> baseline) {
        Map<String, String> receiptByAction = new LinkedHashMap<>();
        Map<String, String> readbackByAction = new LinkedHashMap<>();
        Map<String, JsonNode> readbacks = new LinkedHashMap<>();
        for (JsonNode receipt : gateway.path("receipts")) {
            receiptByAction.put(receipt.path("actionId").asText(""), receipt.path("outcome").asText("UNKNOWN"));
        }
        for (JsonNode readback : gateway.path("readbacks")) {
            String id = readback.path("actionId").asText("");
            readbackByAction.put(id, readback.path("outcome").asText("unconfirmed"));
            readbacks.put(id, readback);
        }
        for (JsonNode action : targets) {
            String actionId = action.path("id").asText();
            String deviceId = action.path("deviceId").asText();
            String receipt = receiptByAction.getOrDefault(actionId, "UNKNOWN");
            String readback = readbackByAction.getOrDefault(actionId, "unconfirmed");
            JsonNode readbackNode = readbacks.get(actionId);
            Double after = null;
            if (readbackNode != null) {
                JsonNode state = readbackNode.path("observedState");
                if (state.path("temperature").isNumber()) after = state.path("temperature").asDouble();
            }
            Decision previous = decisions.get(deviceId);
            if ("ALREADY_SATISFIED".equals(receipt)) {
                decisions.put(deviceId, new Decision("satisfied", "当前无需调整", "satisfied"));
            } else if ("REJECTED".equals(receipt)) {
                decisions.put(deviceId, new Decision("not_dispatched", "上游拒绝调温指令", "rejected"));
            } else if ("ACCEPTED".equals(receipt) && "confirmed".equals(readback)) {
                decisions.put(deviceId, new Decision("adjusted", null, "verified", after));
                if (previous.initialSetpoint() != null) baseline.put(deviceId, previous.initialSetpoint());
            } else if ("ACCEPTED".equals(receipt)) {
                decisions.put(deviceId, new Decision("unconfirmed", "指令已受理，尚未取得平台实测回读", "unconfirmed"));
            } else {
                decisions.put(deviceId, new Decision("not_dispatched", "调温指令结果未知", "unknown"));
            }
        }
    }


    private ObjectNode acEnergyEstimate(JsonNode task, JsonNode phase, Map<String, Double> baseline,
                                        int adjusted, boolean potential) {
        if (baseline.isEmpty() || (!potential && adjusted < 1)) return null;
        JsonNode interval = task.path("interval");
        if (!interval.isObject() || !interval.path("endsAt").isTextual()) return null;
        Instant end;
        try {
            end = Instant.parse(interval.path("endsAt").asText());
        } catch (RuntimeException invalid) {
            return null;
        }
        Instant start = clock.instant();
        if (!end.isAfter(start)) return null;
        double kwh = 0;
        double setpointSum = 0;
        double deltaSum = 0;
        int target = phase.path("read").path("ac").path("target").asInt(AC_DEFAULT_TARGET);
        for (Map.Entry<String, Double> entry : baseline.entrySet()) {
            double delta = target - entry.getValue();
            if (delta <= 0) continue;
            double hours = Math.max(0, Duration.between(start, end).toSeconds() / 3600.0);
            kwh += delta * RATE_PER_DEGREE * POWER_KW * hours;
            setpointSum += entry.getValue();
            deltaSum += delta;
        }
        if (kwh < MIN_ESTIMATE_KWH) return null;
        ObjectNode estimate = mapper.createObjectNode();
        estimate.put("status", "estimated").put("basis", "assumption").put("estimated", true)
                .put("value", Math.round(kwh * 10) / 10.0).put("unit", "度")
                .put("costValue", Math.round(kwh * PRICE_PER_KWH * 10) / 10.0).put("costUnit", "元")
                .put("from", start.toString()).put("to", end.toString()).put("deviceCount", baseline.size());
        if (potential) estimate.put("potential", true);
        estimate.putObject("baseline").put("averageSetpoint", Math.round(setpointSum / baseline.size() * 10) / 10.0)
                .put("target", target).put("averageRise", Math.round(deltaSum / baseline.size() * 10) / 10.0);
        estimate.putObject("assumptions").put("ratePerDegree", RATE_PER_DEGREE)
                .put("powerKw", POWER_KW).put("pricePerKwh", PRICE_PER_KWH);
        estimate.putObject("coverage").put("adjusted", adjusted).put("estimated", baseline.size());
        estimate.put("reason", potential
                ? "巡检未调温，按当前设定温度与经验参数估算潜在节约，非电表实测"
                : "按调前设定温度与经验参数估算，非电表实测");
        estimate.put("calculation", "kWh = Σ(target - beforeSetpoint) × ratePerDegree × powerKw × hours");
        return estimate;
    }

    private TaskExecutionResult failedAc(JsonNode devices, String reason, boolean dispatchStarted) {
        ObjectNode result = mapper.createObjectNode();
        result.put("outcome", "FAILED").put("reason", reason).put("executedAt", clock.instant().toString())
                .put("dispatchStarted", dispatchStarted);
        ObjectNode values = mapper.createObjectNode();
        for (JsonNode device : devices) {
            values.putObject(device.path("id").asText()).put("deviceId", device.path("id").asText())
                    .put("kind", "ac").put("name", device.path("name").asText())
                    .put("outcome", "unavailable").put("reason", reason);
        }
        result.set("devices", values);
        return new TaskExecutionResult("FAILED", result.toString(), dispatchStarted);
    }

    private Decision classify(Object state, int target) {
        JsonNode value = state == null ? mapper.nullNode() : mapper.valueToTree(state);
        if (!value.isObject() || !value.path("power").isTextual() || !value.path("mode").isTextual()
                || !value.path("temperature").isNumber()) {
            return new Decision("unknown", "状态字段不足", null);
        }
        if (!"on".equals(value.path("power").asText())) return new Decision("not_needed", "设备未开机", null);
        if (!"cool".equals(value.path("mode").asText())) return new Decision("not_needed", "当前不是制冷模式", null);
        double setpoint = value.path("temperature").asDouble(Double.NaN);
        if (!Double.isFinite(setpoint) || setpoint < 16 || setpoint > 30) {
            return new Decision("unknown", "设定温度读数无效", null);
        }
        if (setpoint >= target) return new Decision("not_needed", "当前设定温度已不低于" + target + "℃", null);
        return new Decision("adjustable", "已开机、制冷且设定温度低于" + target + "℃", null, null, setpoint);
    }

    private static boolean sameCondition(Object left, Object right) {
        if (left == null || right == null) return false;
        ObjectMapper mapper = new ObjectMapper();
        JsonNode a = mapper.valueToTree(left);
        JsonNode b = mapper.valueToTree(right);
        if (!a.isObject() || !b.isObject()) return false;
        return a.path("power").asText("").equals(b.path("power").asText(""))
                && a.path("mode").asText("").equals(b.path("mode").asText(""))
                && Double.compare(a.path("temperature").asDouble(Double.NaN), b.path("temperature").asDouble(Double.NaN)) == 0;
    }


    private AuthorizedDeviceScope loadScope(RequestIdentity identity) {
        try {
            AuthorizedDeviceScope scope = iot.loadAuthorizedDeviceScope(identity).block(scopeTimeout);
            if (scope == null || scope.source() != identity.source()) {
                throw new DispatchException("读取任务设备授权范围失败", false);
            }
            return scope;
        } catch (DispatchException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DispatchException("读取任务设备授权范围失败", failure, false);
        }
    }

    private DeviceStateReadResult readStates(RequestIdentity identity, List<String> ids) {
        if (ids.isEmpty()) return new DeviceStateReadResult(Map.of(), clock.instant().toString());
        try {
            DeviceStateReadResult result = iot.readDeviceStates(identity, ids).block(scopeTimeout);
            if (result == null) throw new DispatchException("读取设备状态失败", false);
            return result;
        } catch (DispatchException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new DispatchException("读取设备状态失败", failure, false);
        }
    }

    private void validateTaskAuthorization(JsonNode task, JsonNode phase, AuthorizedDeviceScope scope) {
        String catalog = task.path("catalogFingerprint").asText("");
        String binding = task.path("bindingFingerprint").asText("");
        if (!catalog.isBlank() && !catalog.equals(scope.catalogFingerprint())) {
            throw new DispatchException("任务设备授权目录已变化，本阶段未执行", false);
        }
        if (!binding.isBlank() && !binding.equals(scope.bindingFingerprint())) {
            throw new DispatchException("任务控制绑定已变化，本阶段未执行", false);
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode action : phase.path("actions")) ids.add(action.path("deviceId").asText());
        for (JsonNode device : phase.path("read").path("devices")) ids.add(device.path("id").asText());
        for (String id : ids) {
            AuthorizedDevice device = scope.device(id).orElseThrow(() ->
                    new DispatchException("任务目标已不在当前授权范围", false));
            if (device.observationOnly() && hasAction(phase, id)) {
                throw new DispatchException("任务目标已变为只读设备", false);
            }
            JsonNode action = actionFor(phase, id);
            if (action != null) {
                if (!device.kind().equalsIgnoreCase(action.path("kind").asText())) {
                    throw new DispatchException("任务动作与当前设备类型不一致", false);
                }
                if (!bindingMatches(action, device)) {
                    throw new DispatchException("任务控制绑定已变化，本阶段未执行", false);
                }
            }
            JsonNode read = readFor(phase, id);
            if (read != null && read.has("kind") && !device.kind().equalsIgnoreCase(read.path("kind").asText())) {
                throw new DispatchException("任务只读设备类型已变化", false);
            }
        }
    }

    private static boolean hasAction(JsonNode phase, String deviceId) {
        return actionFor(phase, deviceId) != null;
    }

    private static JsonNode actionFor(JsonNode phase, String deviceId) {
        for (JsonNode action : phase.path("actions")) {
            if (deviceId.equals(action.path("deviceId").asText())) return action;
        }
        return null;
    }

    private static JsonNode readFor(JsonNode phase, String deviceId) {
        for (JsonNode device : phase.path("read").path("devices")) {
            if (deviceId.equals(device.path("id").asText())) return device;
        }
        return null;
    }

    private static boolean bindingMatches(JsonNode value, AuthorizedDevice device) {
        String expected = text(value, "bindingFingerprint", "binding_fingerprint", "deviceBindingFingerprint");
        if (expected != null && !expected.equals(device.bindingFingerprint())) return false;
        return matchesOptional(value, "controlRef", device.controlRef())
                && matchesOptional(value, "officeDeviceRef", device.officeDeviceRef())
                && matchesOptional(value, "hubDeviceId", device.hubDeviceId())
                && matchesOptional(value, "smartAreaCode", device.smartAreaCode());
    }

    private static boolean matchesOptional(JsonNode value, String field, String actual) {
        if (!value.has(field)) return true;
        String expected = value.path(field).isNull() ? null : value.path(field).asText(null);
        return Objects.equals(expected, actual);
    }

    private static String text(JsonNode value, String... names) {
        for (String name : names) {
            JsonNode item = value.get(name);
            if (item != null && item.isTextual() && !item.asText().isBlank()) return item.asText();
        }
        return null;
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) throw new DispatchException("任务执行内容为空", false);
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new DispatchException("任务执行内容无效", false); }
    }

    private record Decision(String disposition, String reason, String outcome, Double afterSetpoint, Double initialSetpoint) {
        Decision(String disposition, String reason, String outcome) { this(disposition, reason, outcome, null, null); }
        Decision(String disposition, String reason, String outcome, Double afterSetpoint) {
            this(disposition, reason, outcome, afterSetpoint, null);
        }
    }
}
