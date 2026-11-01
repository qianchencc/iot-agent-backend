package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private final ObjectMapper mapper;
    private final IotAdapter iot;
    private final ActionCommandGateway actions;
    private final TaskExecutionIdentityStore identities;
    private final Clock clock;
    private final Duration scopeTimeout;

    public RealTaskExecutionGateway(ObjectMapper mapper, IotAdapter iot, ActionCommandGateway actions,
                                    TaskExecutionIdentityStore identities, Clock clock, Duration scopeTimeout) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        if (hasAc) return executeAcInspection(gatewayIdentity, scope, phase, task, request);
        if (hasActions) return executeActions(gatewayIdentity, scope, phase, task, request);
        return executeRead(gatewayIdentity, phase);
    }

    private TaskExecutionResult executeActions(RequestIdentity identity, AuthorizedDeviceScope scope,
                                               JsonNode phase, JsonNode task, TaskExecutionRequest request) {
        ArrayNode actions = mapper.createArrayNode();
        for (JsonNode action : phase.path("actions")) actions.add(action);
        List<String> ids = new ArrayList<>();
        for (JsonNode action : actions) ids.add(action.path("deviceId").asText());
        DeviceStateReadResult before = readStates(identity, ids);
        Map<String, Object> observed = before == null ? Map.of() : new LinkedHashMap<>(before.states());
        ObjectNode result = mapper.createObjectNode();
        ObjectNode devices = mapper.createObjectNode();
        boolean dispatchStarted = false;
        try {
            AuthorizedDeviceScope fresh = loadScope(identity);
            validateTaskAuthorization(task, phase, fresh);
            ActionDispatchResult dispatch = actions.dispatch(identity, fresh, actions.toString(), observed);
            dispatchStarted = dispatch.dispatchStarted();
            result.put("gateway", "customer").put("dispatchStarted", dispatchStarted);
            JsonNode gatewayResult = read(dispatch.resultJson());
            result.set("receipts", gatewayResult.path("receipts").deepCopy());
            result.set("readbacks", gatewayResult.path("readbacks").deepCopy());
            result.set("readback", gatewayResult.path("readback").deepCopy());
            result.set("observedStates", gatewayResult.path("observedStates").deepCopy());
            result.put("receiptOutcome", dispatch.receiptOutcome().name());
            result.put("gatewayStatus", dispatch.status().name());
            String outcome = devicesOutcome(actions, gatewayResult, devices);
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

    private TaskExecutionResult executeRead(RequestIdentity identity, JsonNode phase) {
        List<String> ids = new ArrayList<>();
        for (JsonNode device : phase.path("read").path("devices")) ids.add(device.path("id").asText());
        DeviceStateReadResult states = readStates(identity, ids);
        ObjectNode result = mapper.createObjectNode();
        ObjectNode devices = mapper.createObjectNode();
        int observed = 0, unavailable = 0;
        for (JsonNode device : phase.path("read").path("devices")) {
            String id = device.path("id").asText();
            Object state = states == null ? null : states.states().get(id);
            ObjectNode view = mapper.createObjectNode();
            view.put("deviceId", id).put("kind", device.path("kind").asText())
                    .put("name", device.path("name").asText(id))
                    .put("location", device.path("location").asText(""));
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
        String outcome = observed == ids.size() ? "COMPLETED" : observed > 0 ? "PARTIAL" : "FAILED";
        result.put("outcome", outcome).put("executedAt", clock.instant().toString())
                .put("dispatchStarted", false).put("observedAt", states == null ? null : states.observedAt());
        result.set("devices", mapper.valueToTree(devices));
        return new TaskExecutionResult(outcome, result.toString(), false);
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
        Map<String, Double> estimatedBaseline = new LinkedHashMap<>();
        boolean dispatchStarted = false;
        int adjusted = 0, uncertain = 0, failed = 0, satisfied = 0;

        ArrayNode targets = mapper.createArrayNode();
        if (adjusting) {
            for (JsonNode device : devicesNode) {
                Decision decision = decisions.get(device.path("id").asText());
                if (!"adjustable".equals(decision.disposition())) continue;
                ObjectNode action = mapper.createObjectNode();
                action.put("id", request.phaseId() + ":" + device.path("id").asText());
                action.put("deviceId", device.path("id").asText());
                action.put("kind", "ac");
                action.put("deviceName", device.path("name").asText(device.path("id").asText()));
                action.put("location", device.path("location").asText(""));
                action.putObject("command").put("temperature", target);
                targets.add(action);
            }
            if (!targets.isEmpty()) {
                // Re-read immediately before dispatch and yield if another operator changed the panel.
                DeviceStateReadResult latest = readStates(identity, ids);
                if (latest != null) {
                    for (JsonNode device : devicesNode) {
                        String id = device.path("id").asText();
                        if (!"adjustable".equals(decisions.get(id).disposition())) continue;
                        Decision current = classify(latest.states().get(id), target);
                        if (!"adjustable".equals(current.disposition()) || !sameCondition(initial.states().get(id), latest.states().get(id))) {
                            decisions.put(id, new Decision("not_dispatched", current.reason(), null));
                            removeAction(targets, id);
                        }
                    }
                }
                if (!targets.isEmpty()) {
                    try {
                        AuthorizedDeviceScope fresh = loadScope(identity);
                        validateTaskAuthorization(task, phase, fresh);
                        Map<String, Object> observed = latest == null ? Map.of()
                                : new LinkedHashMap<>(latest.states());
                        ActionDispatchResult dispatch = actions.dispatch(identity, fresh, targets.toString(), observed);
                        dispatchStarted = dispatch.dispatchStarted();
                        JsonNode gateway = read(dispatch.resultJson());
                        applyAcDispatch(decisions, devicesNode, targets, gateway, estimatedBaseline);
                    } catch (ActionDispatchException interrupted) {
                        throw new DispatchException("空调调温执行中断，部分指令结果未知", interrupted, interrupted.dispatchStarted());
                    } catch (ltd.qianc.iotagent.error.BusinessException unavailable) {
                        throw new DispatchException(unavailable.getMessage() == null ? "设备控制适配器不可用" : unavailable.getMessage(), false);
                    } catch (IllegalArgumentException rejected) {
                        throw new DispatchException(rejected.getMessage() == null ? "空调调温校验失败" : rejected.getMessage(), false);
                    }
                }
            }
        }

        ArrayNode deviceArray = mapper.createArrayNode();
        for (JsonNode device : devicesNode) {
            String id = device.path("id").asText();
            Decision decision = decisions.get(id);
            ObjectNode view = mapper.createObjectNode();
            view.put("deviceId", id).put("name", device.path("name").asText(id))
                    .put("location", device.path("location").asText(""));
            Object state = initial.states().get(id);
            JsonNode stateNode = state == null ? mapper.nullNode() : mapper.valueToTree(state);
            view.set("power", stateNode.path("power"));
            view.set("mode", stateNode.path("mode"));
            view.set("setpoint", stateNode.path("temperature"));
            view.put("reportedAt", stateNode.path("reportedAt").asText(null));
            view.put("observedAt", clock.instant().toString());
            view.put("disposition", decision.disposition());
            if (decision.reason() != null) view.put("reason", decision.reason());
            if (decision.outcome() != null) view.put("outcome", decision.outcome());
            if (decision.afterSetpoint() != null) view.put("afterSetpoint", decision.afterSetpoint());
            deviceArray.add(view);
            ObjectNode result = mapper.createObjectNode();
            result.put("deviceId", id).put("kind", "ac").put("name", device.path("name").asText(id))
                    .put("location", device.path("location").asText(""))
                    .put("outcome", decision.outcome() == null ? observationOutcome(decision.disposition()) : decision.outcome());
            if (decision.reason() != null) result.put("reason", decision.reason());
            result.set("observed", stateNode.deepCopy());
            if (decision.afterSetpoint() != null) result.put("afterSetpoint", decision.afterSetpoint());
            devices.set(id, result);
            switch (decision.disposition()) {
                case "adjusted", "verified" -> adjusted++;
                case "not_needed", "satisfied" -> satisfied++;
                case "unknown", "unconfirmed", "not_dispatched" -> uncertain++;
                default -> failed++;
            }
        }
        observations.set("devices", deviceArray);
        ObjectNode result = mapper.createObjectNode();
        String outcome = failed > 0 ? (adjusted > 0 ? "PARTIAL" : "FAILED")
                : uncertain > 0 ? (adjusted > 0 ? "PARTIAL" : "UNCONFIRMED")
                : "COMPLETED";
        result.put("outcome", outcome).put("executedAt", clock.instant().toString())
                .put("dispatchStarted", dispatchStarted).put("mode", adjusting ? "adjust_to_26" : "inspect")
                .put("target", target).put("adjustedDevices", adjusted);
        result.set("devices", mapper.valueToTree(devices));
        result.set("observations", mapper.createArrayNode().add(observations));
        ObjectNode estimate = acEnergyEstimate(task, phase, estimatedBaseline, adjusted);
        if (estimate != null) result.set("energyEstimate", estimate);
        return new TaskExecutionResult(outcome, result.toString(), dispatchStarted);
    }

    private void applyAcDispatch(Map<String, Decision> decisions, JsonNode devicesNode, ArrayNode targets,
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
                decisions.put(deviceId, new Decision("adjusted", "指令已受理，尚未取得平台实测回读", "unconfirmed"));
            } else {
                decisions.put(deviceId, new Decision("not_dispatched", "调温指令结果未知", "unknown"));
            }
        }
    }

    private void removeAction(ArrayNode targets, String deviceId) {
        for (int index = targets.size() - 1; index >= 0; index--) {
            if (deviceId.equals(targets.get(index).path("deviceId").asText())) targets.remove(index);
        }
    }

    private ObjectNode acEnergyEstimate(JsonNode task, JsonNode phase, Map<String, Double> baseline, int adjusted) {
        if (adjusted < 1 || baseline.isEmpty()) return null;
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
        estimate.putObject("baseline").put("averageSetpoint", Math.round(setpointSum / baseline.size() * 10) / 10.0)
                .put("target", target).put("averageRise", Math.round(deltaSum / baseline.size() * 10) / 10.0);
        estimate.putObject("assumptions").put("ratePerDegree", RATE_PER_DEGREE)
                .put("powerKw", POWER_KW).put("pricePerKwh", PRICE_PER_KWH);
        estimate.putObject("coverage").put("adjusted", adjusted).put("estimated", baseline.size());
        estimate.put("reason", "按调前设定温度与经验参数估算，非电表实测");
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
            return new Decision("unknown", "状态字段不足", "unavailable");
        }
        if (!"on".equals(value.path("power").asText())) return new Decision("not_needed", "设备未开机", "satisfied");
        if (!"cool".equals(value.path("mode").asText())) return new Decision("not_needed", "当前不是制冷模式", "satisfied");
        double setpoint = value.path("temperature").asDouble(Double.NaN);
        if (!Double.isFinite(setpoint) || setpoint < 16 || setpoint > 30) {
            return new Decision("unknown", "设定温度读数无效", "unavailable");
        }
        if (setpoint >= target) return new Decision("not_needed", "当前设定温度已不低于" + target + "℃", "satisfied");
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

    private static String observationOutcome(String disposition) {
        return switch (disposition) {
            case "adjusted", "verified" -> "verified";
            case "not_needed", "satisfied" -> "satisfied";
            case "not_dispatched" -> "not_dispatched";
            case "unknown" -> "unavailable";
            default -> disposition;
        };
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
        try { return mapper.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (RuntimeException error) { throw new DispatchException("任务执行内容无效", false); }
    }

    private record Decision(String disposition, String reason, String outcome, Double afterSetpoint, Double initialSetpoint) {
        Decision(String disposition, String reason, String outcome) { this(disposition, reason, outcome, null, null); }
        Decision(String disposition, String reason, String outcome, Double afterSetpoint) {
            this(disposition, reason, outcome, afterSetpoint, null);
        }
    }
}
