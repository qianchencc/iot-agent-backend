package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskRecurrence;

/**
 * Deep normalization for user-confirmable task proposals. It validates the
 * schedule/checkpoint structure, expands AC inspection checkpoints on the
 * server, resolves every device against the current authorized catalog and
 * keeps credentials out of the public task payload.
 */
public final class TaskProposalNormalizer {
    public static final List<Integer> AC_CADENCES = List.of(15, 30, 60);
    public static final int AC_MAX_CHECKPOINTS = 48;
    private static final Duration AC_MAX_WINDOW = Duration.ofHours(24);
    private static final int AC_DEFAULT_TARGET = 26;
    private static final int AC_MIN_TARGET = 16;
    private static final int AC_MAX_TARGET = 30;
    private static final Set<String> REPORT_KINDS = Set.of("energy", "safety", "ac_inspection");
    private static final Set<String> REPORT_COMPARISONS = Set.of("previousPeriod", "yearOverYear");
    private static final Set<String> REPORT_LEVELS = Set.of("all", "floor", "location", "nameMatch", "selection");
    private static final Set<String> REPORT_GRAINS = Set.of("day", "week", "month");
    private static final Set<String> SAFETY_TYPES = Set.of("smoke", "gas", "flooding");

    private final ObjectMapper mapper;
    private final IotAdapter iot;
    private final Clock clock;
    private final Duration scopeTimeout;

    public TaskProposalNormalizer(ObjectMapper mapper, IotAdapter iot, Clock clock, Duration scopeTimeout) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.iot = iot;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scopeTimeout = scopeTimeout == null || scopeTimeout.isNegative() || scopeTimeout.isZero()
                ? Duration.ofSeconds(30) : scopeTimeout;
    }

    public String normalizeProposal(PrincipalInfo principal, String source, String payloadJson) {
        return normalize(principal, source, payloadJson, false);
    }

    public String normalizePlan(PrincipalInfo principal, String source, String payloadJson) {
        return normalize(principal, source, payloadJson, true);
    }

    private String normalize(PrincipalInfo principal, String source, String payloadJson, boolean plan) {
        checkPrincipal(principal);
        String normalizedSource = source(source);
        JsonNode parsed = read(payloadJson);
        if (!parsed.isObject()) throw invalid("任务内容无效");
        ObjectNode root = (ObjectNode) parsed.deepCopy();
        TaskTargetResolver.materialize(mapper, iot, scopeTimeout, principal, normalizedSource, root);
        if (plan && !root.path("repeat").isObject()) throw invalid("周期计划必须包含重复规则");

        ObjectNode reportRequest = validateReportRequest(root.get("reportRequest"));
        ObjectNode repeat = normalizeRepeat(root.get("repeat"));
        ObjectNode interval = normalizeInterval(root.get("interval"));
        ArrayNode phases = normalizePhases(root, interval, repeat, reportRequest, normalizedSource);
        if (phases == null) throw invalid("任务内容无效");

        if (reportRequest != null) root.set("reportRequest", reportRequest);
        else root.remove("reportRequest");
        if (root.path("candidateSetIds").isArray() && !root.path("candidateSetIds").isEmpty()
                && !hasDeviceReferences(phases)) {
            throw new BusinessException("TASK_CANDIDATE_SET_UNRESOLVED",
                    "任务候选集尚未展开为服务端核验的具体设备，请先重新查询候选设备", 409);
        }
        if (repeat != null) root.set("repeat", repeat);
        else root.remove("repeat");
        if (interval != null) root.set("interval", interval);
        else root.remove("interval");

        expandAcCheckpoints(phases, interval, repeat);
        expandGenericInterval(phases, interval, root, repeat);
        validateOrdering(phases);
        if (reportRequest != null && hasActions(phases)) {
            throw new BusinessException("REPORT_TASK_CONTROL_UNSUPPORTED", "报表任务不能同时下发设备控制", 400);
        }
        if (repeat != null) {
            prepareRecurring(phases, interval, repeat);
            validateOrdering(phases);
            root.put("taskKind", "recurring");
        } else if (interval != null) {
            root.put("taskKind", hasConditional(phases) ? "conditional" : "interval");
            dropElapsedIntervalPhases(phases, interval);
            if (phases.isEmpty()) throw new BusinessException("TASK_TIME_PASSED", "任务时间已过，请重新选择执行时间", 409);
        } else {
            if (phases.size() > 2) throw invalid("无区间任务最多包含两个执行阶段");
            Instant at = Instant.parse(phases.get(0).path("at").asText());
            // Runtime ten-minute rule (pi-runner/app.ts): a windowless checkpoint
            // stays schedulable for ten minutes after its moment; later phases
            // are already strictly after it via validateOrdering.
            if (at.isBefore(clock.instant().minus(Duration.ofMinutes(10)))) {
                throw new BusinessException("TASK_TIME_PASSED", "任务时间已过，请重新选择执行时间", 409);
            }
            root.put("taskKind", hasConditional(phases) ? "conditional" : "one_time");
        }
        if (plan && repeat == null) throw invalid("周期计划必须包含重复规则");
        root.put("checkpointCount", phases.size());
        root.set("phases", phases);
        root.put("tenantCode", principal.tenantCode());
        root.put("ownerKey", principal.ownerKey());
        root.put("source", normalizedSource);
        root.put("normalizedAt", clock.instant().toString());
        if (root.path("title").asText("").isBlank()) {
            String label = root.path("label").asText("").trim();
            root.put("title", label.isBlank() ? "定时任务" : label);
        }
        authorizeDevices(root, phases, principal, normalizedSource);
        return TaskPayloadSafety.withoutCredentials(mapper, root).toString();
    }

    private ArrayNode normalizePhases(ObjectNode root, ObjectNode interval, ObjectNode repeat,
                                      ObjectNode reportRequest, String source) {
        JsonNode raw = root.get("phases");
        if (raw == null || raw.isMissingNode() || raw.isNull()) {
            JsonNode actions = root.get("actions");
            JsonNode read = root.get("read");
            if ((actions == null || !actions.isArray()) && (read == null || !read.isObject()) && reportRequest == null) {
                return null;
            }
            String at = firstText(root, "at", "executeAt", "startsAt");
            if (at == null && interval != null) at = interval.path("startsAt").asText(null);
            if (at == null && repeat != null) throw invalid("周期任务缺少时间锚点");
            if (at == null) throw invalid("任务缺少执行时间");
            ObjectNode phase = mapper.createObjectNode();
            phase.put("at", at);
            phase.set("actions", actions != null && actions.isArray() ? actions : mapper.createArrayNode());
            if (read != null && read.isObject()) phase.set("read", read);
            raw = mapper.createArrayNode().add(phase);
            root.set("phases", raw);
        }
        if (!raw.isArray() || raw.isEmpty()) throw invalid("任务阶段无效");
        ArrayNode phases = mapper.createArrayNode();
        for (JsonNode value : raw) {
            if (!value.isObject()) throw invalid("任务阶段结构无效");
            ObjectNode phase = (ObjectNode) value.deepCopy();
            Instant at = instant(phase.path("at").asText(null), "任务阶段时间无效");
            phase.put("at", at.toString());
            JsonNode actions = phase.get("actions");
            if (actions == null || actions.isMissingNode() || actions.isNull()) {
                if (phase.path("read").isMissingNode() && !phase.path("reportRequest").isObject()) {
                    throw invalid("任务阶段缺少动作或只读检查");
                }
                phase.set("actions", mapper.createArrayNode());
            } else if (!actions.isArray()) {
                throw invalid("任务阶段动作无效");
            }
            normalizeActions(phase, source);
            normalizeRead(phase);
            // A checkpoint controls devices or observes them — never both. The execution
            // gateway dispatches exactly one of the two, so accepting the mix would
            // silently discard half of the user's intent after confirmation.
            if (phase.path("actions").size() > 0
                    && (phase.path("read").isObject() || phase.path("reportRequest").isObject())) {
                throw invalid("设备控制与只读检查不能在同一阶段混用，请拆分为不同的检查点");
            }
            if (phase.has("requiresVerification") && !phase.get("requiresVerification").isBoolean()) {
                throw invalid("任务阶段核验标记无效");
            }
            if (!phase.has("requiresVerification") && phase.path("actions").size() > 0) {
                phase.put("requiresVerification", "company".equals(source));
            }
            phases.add(phase);
        }
        return phases;
    }

    private void normalizeActions(ObjectNode phase, String source) {
        ArrayNode actions = (ArrayNode) phase.path("actions");
        for (JsonNode value : actions) {
            if (!value.isObject()) throw invalid("任务动作无效");
            ObjectNode action = (ObjectNode) value;
            String deviceId = action.path("deviceId").asText("").trim();
            if (deviceId.isBlank()) throw invalid("任务动作缺少设备");
            String kind = action.path("kind").asText("").trim().toLowerCase(Locale.ROOT);
            if (!kind.isBlank() && !Set.of("lighting", "access", "ac").contains(kind)) {
                throw invalid("设备类型不可控");
            }
            JsonNode command = action.get("command");
            if (command == null || !command.isObject() || command.isEmpty()) {
                JsonNode ac = action.get("ac");
                JsonNode closeOrOpen = action.get("closeOrOpen");
                JsonNode controlType = action.get("controlType");
                if (ac != null && ac.isObject()) {
                    if (!kind.isBlank() && !"ac".equals(kind)) throw invalid("任务动作参数与设备类型不一致");
                    kind = "ac";
                    command = ac;
                } else if (closeOrOpen != null) {
                    if (!kind.isBlank() && !"lighting".equals(kind)) throw invalid("任务动作参数与设备类型不一致");
                    kind = "lighting";
                    command = lightingCommand(closeOrOpen);
                } else if (controlType != null) {
                    if (!kind.isBlank() && !"access".equals(kind)) throw invalid("任务动作参数与设备类型不一致");
                    kind = "access";
                    command = mapper.createObjectNode().put("controlType", controlType.asInt(-1));
                }
            }
            if (command != null && command.isObject()) {
                ObjectNode commandObject = (ObjectNode) command;
                if ("lighting".equals(kind)) {
                    commandObject = canonicalLightingCommand(commandObject);
                }
                action.set("command", commandObject);
                if (!kind.isBlank()) validateCommand(kind, commandObject);
            } else if (!kind.isBlank()) {
                throw invalid("任务动作参数无效");
            }
            action.put("deviceId", deviceId);
            if (!kind.isBlank()) action.put("kind", kind); else action.remove("kind");
            action.put("id", action.path("id").asText(deviceId));
            action.remove("controlBinding");
            action.remove("bindingFingerprint");
        }
    }

    private ObjectNode lightingCommand(JsonNode closeOrOpen) {
        if (closeOrOpen == null || !closeOrOpen.isIntegralNumber()) throw invalid("照明指令参数无效");
        int value = closeOrOpen.asInt(-1);
        if (value != 0 && value != 1) throw invalid("照明指令参数无效");
        return mapper.createObjectNode().put("closeOrOpen", value);
    }

    private ObjectNode canonicalLightingCommand(ObjectNode command) {
        if (command.has("closeOrOpen")) {
            if (command.size() != 1 || !command.path("closeOrOpen").isIntegralNumber()) throw invalid("照明指令参数无效");
            int value = command.path("closeOrOpen").asInt(-1);
            if (value != 0 && value != 1) throw invalid("照明指令参数无效");
            return command;
        }
        String power = command.path("power").asText("");
        if (command.size() == 1 && Set.of("on", "off").contains(power)) {
            return mapper.createObjectNode().put("closeOrOpen", "on".equals(power) ? 1 : 0);
        }
        throw invalid("照明指令参数无效");
    }

    private void validateCommand(String kind, ObjectNode command) {
        switch (kind) {
            case "lighting" -> {
                if (command.size() != 1 || !command.has("closeOrOpen")
                        || !command.path("closeOrOpen").isIntegralNumber()) {
                    throw invalid("照明指令参数无效");
                }
                int value = command.path("closeOrOpen").asInt(-1);
                if (value != 0 && value != 1) throw invalid("照明指令参数无效");
            }
            case "access" -> {
                if (command.size() != 1 || command.path("controlType").asInt(-1) != 2) {
                    throw invalid("门禁指令仅支持开门确认指令");
                }
            }
            case "ac" -> {
                if (command.isEmpty()) throw invalid("空调指令参数无效");
                List<String> keys = new ArrayList<>();
                command.fieldNames().forEachRemaining(keys::add);
                for (String key : keys) {
                    JsonNode value = command.get(key);
                    switch (key) {
                        case "power" -> {
                            if (!Set.of("on", "off").contains(value.asText(""))) throw invalid("空调电源参数无效");
                        }
                        case "mode" -> {
                            if (!Set.of("cool", "heat", "dry", "fan").contains(value.asText(""))) throw invalid("空调模式参数无效");
                        }
                        case "fanSpeed" -> {
                            if (!Set.of("low", "high").contains(value.asText(""))) throw invalid("空调风速参数无效");
                        }
                        case "temperature" -> {
                            if (!value.isIntegralNumber() || value.asInt() < AC_MIN_TARGET || value.asInt() > AC_MAX_TARGET) {
                                throw invalid("空调温度仅支持" + AC_MIN_TARGET + "–" + AC_MAX_TARGET + "℃的整数");
                            }
                        }
                        default -> throw invalid("空调指令参数无效");
                    }
                }
                if ("off".equals(command.path("power").asText("")) && command.size() > 1) {
                    throw invalid("关闭空调时不能同时设置其他参数");
                }
            }
            default -> throw invalid("设备类型不可控");
        }
    }

    private void normalizeRead(ObjectNode phase) {
        JsonNode read = phase.get("read");
        if (read == null || read.isMissingNode() || read.isNull()) return;
        if (!read.isObject()) throw invalid("只读检查参数无效");
        ObjectNode value = (ObjectNode) read;
        for (String key : iterable(value.fieldNames())) {
            if (!Set.of("deviceIds", "devices", "alertWindow", "alertDays", "ac", "agent").contains(key)) {
                throw invalid("只读检查包含不支持的字段：" + key);
            }
        }
        boolean alerts = value.has("alertWindow") || value.has("alertDays");
        if ((alerts ? 1 : 0) + (value.has("ac") ? 1 : 0) + (value.has("agent") ? 1 : 0) > 1) {
            throw invalid("告警、空调与智能只读指令不能在同一检查点混用");
        }
        if (value.has("alertWindow") && (value.has("alertDays")
                || !Set.of("previous_day", "previous_week").contains(value.path("alertWindow").asText()))) {
            throw invalid("安全告警窗口参数无效");
        }
        if (value.has("alertDays") && (!value.path("alertDays").isIntegralNumber()
                || value.path("alertDays").asInt() < 1 || value.path("alertDays").asInt() > 365)) {
            throw invalid("安全告警窗口参数无效");
        }
        if (value.has("agent")) normalizeCheckpointAssignment(value);
        JsonNode ac = value.get("ac");
        if (ac != null && ac.isObject()) value.set("ac", normalizeAcSpec((ObjectNode) ac));
        JsonNode devices = value.get("devices");
        if (devices != null && devices.isArray()) {
            for (JsonNode device : devices) {
                if (!device.isObject() || device.path("id").asText("").isBlank()) throw invalid("只读设备范围无效");
            }
        }
        JsonNode deviceIds = value.get("deviceIds");
        if (deviceIds != null && (!deviceIds.isArray()
                || deviceIds.isEmpty())) throw invalid("只读设备范围无效");
    }

    private void normalizeCheckpointAssignment(ObjectNode read) {
        if (!(read.get("agent") instanceof ObjectNode agent)
                || !agent.path("objective").isTextual() || agent.path("objective").asText().isBlank()
                || agent.path("objective").asText().length() > 2000
                || !Set.of("result", "report").contains(agent.path("output").asText())) {
            throw invalid("只读指令需要明确目标与result/report输出类型");
        }
        for (String key : iterable(agent.fieldNames())) {
            if (!Set.of("objective", "output", "spec").contains(key)) throw invalid("只读指令包含不支持的字段");
        }
        if (agent.has("spec")) {
            JsonNode spec = agent.get("spec");
            if (!spec.isObject() || spec.size() != 2 || !spec.path("comparisons").isArray()) {
                throw invalid("只读统计要求须包含period与comparisons");
            }
            ObjectNode report = mapper.createObjectNode().put("kind", "energy");
            report.set("period", spec.path("period"));
            report.set("comparisons", spec.path("comparisons"));
            ObjectNode validated = validateReportRequest(report);
            ObjectNode normalized = mapper.createObjectNode();
            normalized.set("period", validated.path("period"));
            normalized.set("comparisons", validated.path("comparisons"));
            agent.set("spec", normalized);
        }
    }

    private ObjectNode normalizeAcSpec(ObjectNode ac) {
        ObjectNode spec = ac.deepCopy();
        String mode = spec.path("mode").asText("");
        if (mode.isBlank()) mode = spec.path("action").isObject() ? "adjust_to_26" : "inspect";
        if (!Set.of("inspect", "adjust_to_26").contains(mode)) throw invalid("空调检查模式无效");
        int target = AC_DEFAULT_TARGET;
        if (spec.path("action").path("setpoint").isIntegralNumber()) {
            target = spec.path("action").path("setpoint").asInt();
        } else if (spec.path("target").isIntegralNumber()) {
            target = spec.path("target").asInt();
        }
        if (target < AC_MIN_TARGET || target > AC_MAX_TARGET) {
            throw invalid("空调目标温度仅支持" + AC_MIN_TARGET + "–" + AC_MAX_TARGET + "℃的整数");
        }
        if (spec.has("cadenceMinutes")
                && (!spec.path("cadenceMinutes").isIntegralNumber()
                || !AC_CADENCES.contains(spec.path("cadenceMinutes").asInt()))) {
            throw invalid("空调检查频率仅支持每15、30或60分钟一次");
        }
        ObjectNode condition = mapper.createObjectNode();
        condition.put("power", "on").put("mode", "cool");
        condition.putObject("setpoint").put("operator", "lt").put("value", target);
        spec.put("mode", mode).put("target", target);
        spec.set("condition", condition);
        if ("adjust_to_26".equals(mode)) spec.putObject("action").put("setpoint", target);
        else spec.remove("action");
        return spec;
    }

    private void expandGenericInterval(ArrayNode phases, ObjectNode interval, ObjectNode root, ObjectNode repeat) {
        if (interval == null || phases.size() != 1) return;
        ObjectNode template = (ObjectNode) phases.get(0);
        if (template.path("read").path("ac").has("cadenceMinutes")) return;
        Integer cadence = cadence(root, template, interval);
        if (cadence == null) return;
        if (cadence < 1 || cadence > 1440) throw invalid("任务检查频率无效");
        Instant start = Instant.parse(interval.path("startsAt").asText());
        Instant end = Instant.parse(interval.path("endsAt").asText());
        List<Instant> times = new ArrayList<>();
        for (Instant at = start.plus(Duration.ofMinutes(cadence)); !at.isAfter(end); at = at.plus(Duration.ofMinutes(cadence))) {
            times.add(at);
            if (times.size() > 1000) throw invalid("任务检查点过多，请缩短区间或降低频率");
        }
        if (times.isEmpty()) throw invalid("任务区间短于检查频率");
        ArrayNode expanded = mapper.createArrayNode();
        for (Instant at : times) {
            ObjectNode copy = template.deepCopy();
            copy.put("at", at.toString());
            copy.remove("cadenceMinutes");
            if (copy.path("read").isObject()) ((ObjectNode) copy.path("read")).remove("cadenceMinutes");
            expanded.add(copy);
        }
        phases.removeAll();
        phases.addAll(expanded);
    }

    private Integer cadence(ObjectNode root, ObjectNode phase, ObjectNode interval) {
        JsonNode value = root.get("cadenceMinutes");
        if (value == null || value.isNull()) value = interval.get("cadenceMinutes");
        if (value == null || value.isNull()) value = phase.get("cadenceMinutes");
        if (value == null || value.isNull()) value = phase.path("read").get("cadenceMinutes");
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) throw invalid("任务检查频率无效");
        return value.asInt();
    }

    private void expandAcCheckpoints(ArrayNode phases, ObjectNode interval, ObjectNode repeat) {
        ObjectNode template = null;
        int cadence = 0;
        for (JsonNode phase : phases) {
            JsonNode ac = phase.path("read").path("ac");
            if (!ac.isObject()) continue;
            if (ac.has("cadenceMinutes")) cadence = ac.path("cadenceMinutes").asInt();
            if (template == null) template = (ObjectNode) phase;
            else if (cadence > 0) {
                // Already-expanded proposals are verified against the server expansion below.
            }
        }
        if (template == null) return;
        if (cadence == 0) {
            if (interval != null) {
                // A window without a cadence stays the user's decision; the
                // server never fills a default frequency, so clarification is
                // returned instead of saving a misleading proposal.
                throw new BusinessException("AC_CADENCE_REQUIRED", "空调区间巡检需要明确15、30或60分钟的检查频率", 400);
            }
            long acPhases = 0;
            for (JsonNode phase : phases) if (phase.path("read").path("ac").isObject()) acPhases++;
            if (repeat != null && acPhases > 0) {
                throw new BusinessException("AC_WINDOW_REQUIRED", "空调周期检查需要明确的监测窗口起止时间与检查频率", 400);
            }
            // Single check vs window check (Runtime pi-runner): without a window
            // an AC condition is exactly one checkpoint; checkpoint time lists
            // are only produced by the server from an interval plus cadence.
            if (acPhases > 0 && phases.size() > 1) {
                throw invalid("空调检查必须给出监测窗口的开始和结束时间，或明确只检查一次");
            }
            return;
        }
        if (interval == null || interval.path("startsAt").asText("").equals(interval.path("endsAt").asText(""))) {
            throw new BusinessException("AC_WINDOW_REQUIRED", "空调检查频率需要明确的监测窗口起止时间", 400);
        }
        Instant start = Instant.parse(interval.path("startsAt").asText());
        Instant end = Instant.parse(interval.path("endsAt").asText());
        Duration duration = Duration.between(start, end);
        if (duration.compareTo(AC_MAX_WINDOW) > 0) {
            throw new BusinessException("AC_WINDOW_TOO_LONG", "空调监测窗口最长24小时，超出的部分请拆成周期计划", 400);
        }
        long checkpointCount = duration.toMinutes() / cadence;
        if (checkpointCount < 1) throw new BusinessException("AC_WINDOW_TOO_SHORT", "空调监测窗口短于检查频率，请延长窗口或降低频率", 400);
        if (checkpointCount > AC_MAX_CHECKPOINTS) {
            throw new BusinessException("AC_CHECKPOINTS_EXCEEDED",
                    "单次最多" + AC_MAX_CHECKPOINTS + "个检查点，请缩短窗口或降低检查频率（例如每30或60分钟）", 400);
        }
        List<Instant> expected = new ArrayList<>();
        for (int index = 1; index <= checkpointCount; index++) expected.add(start.plus(Duration.ofMinutes((long) cadence * index)));
        if (phases.size() > 1) {
            if (phases.size() != expected.size()) throw invalid("使用检查频率时只提交一个空调检查点模板，检查点由服务器生成");
            for (int index = 0; index < phases.size(); index++) {
                JsonNode phase = phases.get(index);
                if (!phase.path("read").path("ac").isObject()
                        || !Instant.parse(phase.path("at").asText()).equals(expected.get(index))) {
                    throw invalid("使用检查频率时只提交一个空调检查点模板，检查点由服务器生成");
                }
            }
            return;
        }
        ArrayNode expanded = mapper.createArrayNode();
        for (Instant at : expected) {
            ObjectNode copy = ((ObjectNode) phases.get(0)).deepCopy();
            copy.put("at", at.toString());
            expanded.add(copy);
        }
        phases.removeAll();
        phases.addAll(expanded);
    }

    private void prepareRecurring(ArrayNode phases, ObjectNode interval, ObjectNode repeat) {
        ObjectNode rule = repeat;
        Instant anchor = interval != null && !interval.path("startsAt").asText("").isBlank()
                ? Instant.parse(interval.path("startsAt").asText())
                : Instant.parse(phases.get(0).path("at").asText());
        TaskRecurrence.RepeatRule parsed;
        try {
            parsed = repeatRule(rule, TaskRecurrence.localDate(anchor));
        } catch (RuntimeException error) {
            throw invalid(error.getMessage() == null ? "重复规则无效" : error.getMessage());
        }
        if (preserveCurrentAcWindow(phases, interval)) return;
        LocalDate next = TaskRecurrence.next(parsed, anchor, clock.instant());
        if (next == null) throw new BusinessException("TASK_TIME_PASSED", "截止日前没有未来发生", 409);
        LocalDate anchorDate = TaskRecurrence.localDate(anchor);
        long days = java.time.temporal.ChronoUnit.DAYS.between(anchorDate, next);
        if (days == 0) return;
        shift(phases, interval, days);
    }

    private boolean preserveCurrentAcWindow(ArrayNode phases, ObjectNode interval) {
        if (interval == null || interval.path("startsAt").asText("").equals(interval.path("endsAt").asText(""))) return false;
        JsonNode ac = phases.get(0).path("read").path("ac");
        if (!ac.isObject() || !ac.has("cadenceMinutes")) return false;
        Instant now = clock.instant();
        Instant start = Instant.parse(interval.path("startsAt").asText());
        Instant end = Instant.parse(interval.path("endsAt").asText());
        if (now.isBefore(start) || !now.isBefore(end)) return false;
        for (JsonNode phase : phases) {
            if (Instant.parse(phase.path("at").asText()).isAfter(now)) return true;
        }
        return false;
    }

    private void dropElapsedIntervalPhases(ArrayNode phases, ObjectNode interval) {
        Instant start = Instant.parse(interval.path("startsAt").asText());
        Instant end = Instant.parse(interval.path("endsAt").asText());
        if (!end.isAfter(clock.instant())) {
            throw new BusinessException("TASK_TIME_PASSED", "任务时间区间已过，请重新选择时间", 409);
        }
        if (phases.size() <= 1) {
            Instant at = Instant.parse(phases.get(0).path("at").asText());
            if (!at.isAfter(clock.instant())) throw new BusinessException("TASK_TIME_PASSED", "任务时间已过，请重新选择执行时间", 409);
            return;
        }
        if (start.isAfter(clock.instant())) return;
        ArrayNode remaining = mapper.createArrayNode();
        for (JsonNode phase : phases) {
            if (Instant.parse(phase.path("at").asText()).isAfter(clock.instant())) remaining.add(phase);
        }
        phases.removeAll();
        phases.addAll(remaining);
    }

    private void shift(ArrayNode phases, ObjectNode interval, long days) {
        if (interval != null) {
            interval.put("startsAt", Instant.parse(interval.path("startsAt").asText())
                    .plus(Duration.ofDays(days)).toString());
            interval.put("endsAt", Instant.parse(interval.path("endsAt").asText())
                    .plus(Duration.ofDays(days)).toString());
        }
        for (JsonNode phase : phases) {
            ((ObjectNode) phase).put("at", Instant.parse(phase.path("at").asText())
                    .plus(Duration.ofDays(days)).toString());
        }
    }

    private void validateOrdering(ArrayNode phases) {
        Instant previous = null;
        for (JsonNode phase : phases) {
            Instant at = Instant.parse(phase.path("at").asText());
            if (previous != null && !at.isAfter(previous)) throw invalid("任务阶段时间必须严格递增");
            previous = at;
        }
    }

    private void authorizeDevices(ObjectNode root, ArrayNode phases, PrincipalInfo principal, String source) {
        boolean referenced = false;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (JsonNode phase : phases) {
            for (JsonNode action : phase.path("actions")) {
                ids.add(action.path("deviceId").asText());
                referenced = true;
            }
            for (JsonNode device : phase.path("read").path("devices")) {
                ids.add(device.path("id").asText());
                referenced = true;
            }
            JsonNode deviceIds = phase.path("read").path("deviceIds");
            if (deviceIds.isArray() && !deviceIds.isEmpty()) {
                referenced = true;
                for (JsonNode id : deviceIds) ids.add(id.asText(""));
            }
        }
        if (!referenced) return;
        if (iot == null) throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        IotSource requested;
        try { requested = IotSource.parse(source); }
        catch (RuntimeException invalid) { throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400); }
        AuthorizedDeviceScope scope;
        try {
            scope = iot.loadAuthorizedDeviceScope(new RequestIdentity(
                    principal.authorization(), principal.tenantCode(), requested, TaskFacade.ownerKey(principal)))
                    .block(scopeTimeout);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        }
        if (scope == null || scope.source() != requested || scope.devices() == null) {
            throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "当前设备授权范围暂不可用", 503);
        }
        LinkedHashSet<String> regions = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) throw invalid("任务目标设备无效");
            AuthorizedDevice device = scope.device(id).orElseThrow(() ->
                    new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "任务目标已不在当前授权范围", 403));
            regions.addAll(device.officeIds());
            if (device.observationOnly() && hasAction(phases, id)) {
                throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "只读设备不能作为控制目标", 403);
            }
        }
        for (JsonNode phase : phases) {
            for (JsonNode actionNode : phase.path("actions")) {
                ObjectNode action = (ObjectNode) actionNode;
                AuthorizedDevice device = scope.device(action.path("deviceId").asText()).orElseThrow();
                String kind = action.path("kind").asText("").trim();
                if (kind.isBlank()) {
                    kind = device.kind();
                } else if (!device.kind().equalsIgnoreCase(kind)) {
                    throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "任务动作与设备类型不匹配", 403);
                }
                JsonNode commandNode = action.get("command");
                if (commandNode == null || !commandNode.isObject()) throw invalid("任务动作参数无效");
                ObjectNode command = (ObjectNode) commandNode;
                if ("lighting".equalsIgnoreCase(kind)) command = canonicalLightingCommand(command);
                validateCommand(kind.toLowerCase(Locale.ROOT), command);
                action.put("kind", device.kind()).put("deviceName", device.name())
                        .put("location", device.installAddress()).put("controlBinding", device.controlRef());
                action.set("command", command);
                putBinding(action, device);
            }
            JsonNode readNode = phase.get("read");
            if (readNode == null || !readNode.isObject()) continue;
            ObjectNode read = (ObjectNode) readNode;
            if (read.path("ac").isObject() && !read.path("devices").isArray() && !read.path("deviceIds").isArray()) {
                throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "空调条件检查缺少空调设备", 400);
            }
            JsonNode deviceIds = read.get("deviceIds");
            if (deviceIds != null && deviceIds.isArray()) {
                ArrayNode devices = mapper.createArrayNode();
                for (JsonNode id : deviceIds) {
                    AuthorizedDevice device = scope.device(id.asText("")).orElseThrow();
                    ObjectNode view = mapper.createObjectNode();
                    view.put("id", device.id()).put("kind", device.kind()).put("name", device.name())
                            .put("location", device.installAddress());
                    putBinding(view, device);
                    devices.add(view);
                }
                read.set("devices", devices);
                read.remove("deviceIds");
            }
            JsonNode devices = read.get("devices");
            if (devices == null || !devices.isArray()) continue;
            ArrayNode normalized = mapper.createArrayNode();
            for (JsonNode deviceNode : devices) {
                ObjectNode deviceView = (ObjectNode) deviceNode;
                AuthorizedDevice device = scope.device(deviceView.path("id").asText()).orElseThrow();
                if (deviceView.has("kind") && !device.kind().equalsIgnoreCase(deviceView.path("kind").asText())) {
                    throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "只读目标与设备类型不匹配", 403);
                }
                deviceView.put("id", device.id()).put("kind", device.kind()).put("name", device.name())
                        .put("location", device.installAddress());
                putBinding(deviceView, device);
                normalized.add(deviceView);
            }
            read.set("devices", normalized);
            if (read.path("ac").isObject()) {
                if (normalized.isEmpty()) {
                    throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "空调条件检查缺少空调设备", 400);
                }
                for (JsonNode device : normalized) {
                    if (!"ac".equalsIgnoreCase(device.path("kind").asText(""))) {
                        throw new BusinessException("TASK_DEVICE_AUTHORIZATION_UNAVAILABLE", "空调条件只能绑定空调设备", 403);
                    }
                }
            }
            if (read.has("agent")) {
                if (requested != IotSource.COMPANY || normalized.isEmpty()) throw invalid("用电检查点缺少公司电表范围");
                for (JsonNode device : normalized) {
                    if (!"energy".equals(device.path("kind").asText())) throw invalid("用电检查点只能绑定电表");
                }
            }
        }
        root.put("catalogFingerprint", scope.catalogFingerprint());
        root.put("bindingFingerprint", scope.bindingFingerprint());
        ArrayNode regionArray = mapper.createArrayNode();
        regions.forEach(regionArray::add);
        root.set("regionIds", regionArray);
    }

    private static boolean hasAction(ArrayNode phases, String deviceId) {
        for (JsonNode phase : phases) {
            for (JsonNode action : phase.path("actions")) {
                if (deviceId.equals(action.path("deviceId").asText())) return true;
            }
        }
        return false;
    }

    private static void putBinding(ObjectNode target, AuthorizedDevice device) {
        if (device.controlRef() != null) target.put("controlRef", device.controlRef());
        if (device.officeDeviceRef() != null) target.put("officeDeviceRef", device.officeDeviceRef());
        if (device.hubDeviceId() != null) target.put("hubDeviceId", device.hubDeviceId());
        if (device.smartAreaCode() != null) target.put("smartAreaCode", device.smartAreaCode());
        target.put("bindingFingerprint", device.bindingFingerprint());
    }

    private ObjectNode normalizeRepeat(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (!value.isObject()) throw invalid("重复规则无效");
        ObjectNode repeat = (ObjectNode) value.deepCopy();
        repeatRule(repeat, LocalDate.of(1970, 1, 1));
        return repeat;
    }

    private TaskRecurrence.RepeatRule repeatRule(ObjectNode value, LocalDate anchorDate) {
        String frequency = value.path("frequency").asText("");
        Set<Integer> weekdays = Set.of();
        if (value.has("weekdays")) {
            if (!value.get("weekdays").isArray()) throw invalid("请指定有效且不重复的周几");
            List<Integer> parsed = new ArrayList<>();
            for (JsonNode item : value.get("weekdays")) parsed.add(item.isIntegralNumber() ? item.intValue() : -1);
            weekdays = TaskRecurrence.weekdays(parsed);
        }
        Integer day = value.has("dayOfMonth") ? value.path("dayOfMonth").asInt(-1) : null;
        LocalDate until = null;
        if (value.has("until")) {
            String raw = value.path("until").asText("");
            try {
                until = LocalDate.parse(raw);
                if (!until.toString().equals(raw)) throw new IllegalArgumentException();
            } catch (RuntimeException error) {
                throw invalid("截止日无效");
            }
            if (until.isBefore(anchorDate)) throw invalid("截止日无效");
        }
        try {
            return new TaskRecurrence.RepeatRule(frequency, weekdays, day, until);
        } catch (RuntimeException error) {
            throw invalid(error.getMessage() == null ? "重复规则无效" : error.getMessage());
        }
    }

    private ObjectNode normalizeInterval(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (!value.isObject()) throw invalid("任务时间区间无效");
        ObjectNode interval = (ObjectNode) value.deepCopy();
        Instant starts = instant(interval.path("startsAt").asText(null), "任务时间区间无效");
        Instant ends = instant(interval.path("endsAt").asText(null), "任务时间区间无效");
        if (!ends.isAfter(starts)) throw invalid("任务时间区间无效");
        interval.put("startsAt", starts.toString()).put("endsAt", ends.toString());
        return interval;
    }

    private ObjectNode validateReportRequest(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return null;
        if (!value.isObject()) throw invalid("报表请求无效");
        ObjectNode request = (ObjectNode) value.deepCopy();
        String kind = request.path("kind").asText("");
        if (!REPORT_KINDS.contains(kind)) throw invalid("报表类型不受支持");
        for (String key : iterable(request.fieldNames())) {
            boolean common = Set.of("kind", "title", "period", "objective").contains(key);
            boolean energy = "energy".equals(kind) && Set.of("comparisons", "scope", "grain").contains(key);
            boolean safety = "safety".equals(kind) && Set.of("types", "fixedDeviceIds").contains(key);
            if (!common && !energy && !safety) throw invalid("报表请求包含不支持的字段：" + key);
        }
        if (request.has("title") && !request.get("title").isTextual()) throw invalid("报表标题无效");
        if (request.has("objective") && !request.get("objective").isTextual()) throw invalid("报表目标无效");
        JsonNode period = request.get("period");
        if (period == null || !period.isObject()) throw invalid("报表期间无效");
        String periodKind = period.path("kind").asText("");
        if ("absolute".equals(periodKind)) {
            Instant from = offsetInstant(period.path("from").asText(null));
            Instant to = offsetInstant(period.path("to").asText(null));
            if (from == null || to == null || !to.isAfter(from)) throw invalid("报表期间无效");
            request.set("period", mapper.createObjectNode().put("kind", "absolute")
                    .put("from", from.toString()).put("to", to.toString()));
        } else if ("previous".equals(periodKind)) {
            String unit = period.path("unit").asText("");
            if (!Set.of("day", "week", "month").contains(unit)) throw invalid("报表期间无效");
            request.set("period", mapper.createObjectNode().put("kind", "previous").put("unit", unit));
        } else {
            throw invalid("报表期间无效");
        }
        if (request.has("comparisons")) {
            JsonNode comparisons = request.get("comparisons");
            if (!comparisons.isArray() || comparisons.size() > 2) throw invalid("报表比较项无效");
            for (JsonNode comparison : comparisons) {
                if (!REPORT_COMPARISONS.contains(comparison.asText(""))) throw invalid("报表比较项无效");
            }
        }
        JsonNode scope = request.get("scope");
        if (scope == null || scope.isMissingNode() || scope.isNull()) {
            if ("safety".equals(kind)) return request;
            scope = null;
        } else if (!scope.isObject()) {
            throw invalid("报表范围无效");
        }
        if (scope != null) {
            String level = scope.path("level").asText("");
            if (!REPORT_LEVELS.contains(level)) throw invalid("报表范围无效");
            for (String key : iterable(scope.fieldNames())) {
                if (!Set.of("level", "floor", "text", "meterId", "meterIds").contains(key)) {
                    throw invalid("报表范围包含不支持的字段：" + key);
                }
            }
            if ("selection".equals(level)) {
                boolean hasMeter = scope.path("meterId").isTextual() && !scope.path("meterId").asText("").isBlank();
                boolean hasMeters = scope.path("meterIds").isArray() && !scope.path("meterIds").isEmpty();
                if (!hasMeter && !hasMeters) throw invalid("报表范围未选择电表");
            }
            if (scope.has("meterIds")) {
                if (!scope.get("meterIds").isArray()) throw invalid("报表范围无效");
                for (JsonNode meter : scope.get("meterIds")) if (!meter.isTextual() || meter.asText("").isBlank()) throw invalid("报表范围无效");
            }
        }
        if (request.has("grain") && !REPORT_GRAINS.contains(request.path("grain").asText(""))) {
            throw invalid("报表粒度无效");
        }
        if ("safety".equals(kind)) {
            JsonNode types = request.get("types");
            if (types != null) {
                if (!types.isArray() || types.isEmpty()) throw invalid("安全报表类型无效");
                for (JsonNode type : types) {
                    if (!SAFETY_TYPES.contains(type.asText(""))) throw invalid("安全报表类型无效");
                }
            }
            JsonNode fixedDevices = request.get("fixedDeviceIds");
            if (fixedDevices != null) {
                // Server-authored fact from TaskTargetResolver; the model cannot
                // provide it (materialize rejects it before this validation).
                if (!fixedDevices.isArray() || fixedDevices.isEmpty()) throw invalid("安全报表固定成员无效");
                for (JsonNode id : fixedDevices) {
                    if (!id.isTextual() || id.asText("").isBlank()) throw invalid("安全报表固定成员无效");
                }
            }
        }
        return request;
    }

    private static Iterable<String> iterable(java.util.Iterator<String> values) {
        List<String> result = new ArrayList<>();
        values.forEachRemaining(result::add);
        return result;
    }

    private static boolean hasConditional(ArrayNode phases) {
        for (JsonNode phase : phases) if (phase.path("read").path("ac").isObject()) return true;
        return false;
    }

    private static boolean hasActions(ArrayNode phases) {
        for (JsonNode phase : phases) if (phase.path("actions").size() > 0) return true;
        return false;
    }

    private static boolean hasDeviceReferences(ArrayNode phases) {
        for (JsonNode phase : phases) {
            if (phase.path("actions").size() > 0) return true;
            if (phase.path("read").path("devices").isArray() && !phase.path("read").path("devices").isEmpty()) return true;
            if (phase.path("read").path("deviceIds").isArray() && !phase.path("read").path("deviceIds").isEmpty()) return true;
        }
        return false;
    }

    private void checkPrincipal(PrincipalInfo principal) {
        if (principal == null || principal.authorization() == null || principal.authorization().isBlank()
                || principal.tenantCode() == null || principal.tenantCode().isBlank()) {
            throw new BusinessException("UNAUTHORIZED", "缺少登录凭据或园区标识，请重新登录", 401);
        }
    }

    private static String source(String value) {
        String normalized = value == null || value.isBlank() ? "office" : value;
        if (!"office".equals(normalized) && !"company".equals(normalized)) {
            throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        }
        return normalized;
    }

    private static String firstText(JsonNode value, String... keys) {
        for (String key : keys) {
            JsonNode item = value.get(key);
            if (item != null && item.isTextual() && !item.asText().isBlank()) return item.asText();
        }
        return null;
    }

    private static Instant instant(String value, String message) {
        if (value == null || value.isBlank()) throw invalid(message);
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException offsetFailure) {
            try { return Instant.parse(value); }
            catch (RuntimeException error) { throw invalid(message); }
        }
    }

    private static Instant offsetInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException error) {
            try { return Instant.parse(value); }
            catch (RuntimeException ignored) { return null; }
        }
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) throw invalid("任务内容无效");
        try { return mapper.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException error) { throw invalid("任务内容无效"); }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_TASK_PAYLOAD", message, 400);
    }
}
