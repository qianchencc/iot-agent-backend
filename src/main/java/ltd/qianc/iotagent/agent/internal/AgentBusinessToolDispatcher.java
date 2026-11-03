package ltd.qianc.iotagent.agent.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.agent.AgentToolBridge;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.DeviceCandidateBinding;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.VideoPlaybackPreparation;
import ltd.qianc.iotagent.iot.VideoPlaybackWindow;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskProjectionStore;

/**
 * Deep business implementation behind the public Tool Bridge entry. It owns
 * canonical tool dispatch, server-bound context injection, argument guards and
 * domain authorization. Transport only verifies the private envelope.
 */
public final class AgentBusinessToolDispatcher implements AgentToolBridge {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> CONTEXT_ARGUMENTS = Set.of("source", "focusRegionId");
    private static final Set<String> FORBIDDEN_INTERNAL_ARGUMENTS = Set.of(
            "tenantCode", "reference", "principal", "ownerKey", "runId", "conversationId", "turnId", "toolContextId");
    private static final DateTimeFormatter VIDEO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private final ConversationFacade conversations;
    private final IotAdapter iot;
    private final ObservationAdapter observations;
    private final TaskProjectionStore projections;
    private final ActionDraftFacade actions;
    private final TaskFacade tasks;
    private final ObjectMapper mapper;
    private final ReportGenerationPort reports;
    private final AgentRunGuard guard;

    public AgentBusinessToolDispatcher(ConversationFacade conversations, IotAdapter iot,
            ObservationAdapter observations, TaskProjectionStore projections,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports) {
        this.conversations = conversations;
        this.iot = iot;
        this.observations = observations;
        this.projections = projections;
        this.actions = actions;
        this.tasks = tasks;
        this.mapper = mapper;
        this.guard = new AgentRunGuard(mapper);
        this.reports = reports;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> request) {
        if (request == null) throw new BusinessException("INVALID_AGENT_TOOL", "工具请求不能为空");
        String runId = requiredText(request, "runId", "Agent 运行标识不能为空");
        String toolContextId = requiredText(request, "toolContextId", "Agent 工具上下文不能为空");
        String conversationId = requiredText(request, "conversationId", "会话标识不能为空");
        String turnId = requiredText(request, "turnId", "轮次标识不能为空");
        String toolName = requiredText(request, "toolName", "工具名称不能为空");
        String toolCallId = requiredText(request, "toolCallId", "工具调用标识不能为空");
        AgentToolContext context = resolveContext(toolContextId, conversationId, turnId, runId);
        IotSource source = parseSource(context.source());
        PrincipalInfo principal = context.principal();
        AgentToolCatalog.Definition definition = AgentToolCatalog.find(source.wireValue(), toolName);
        Map<String, Object> arguments = normalizeArguments(definition, request.get("arguments"), context,
                turnId, toolCallId);
        return guard.invoke(toolContextId, toolCallId, definition, arguments, () -> switch (definition.name()) {
            case "list_office_regions" -> officeRegions(principal, arguments);
            case "find_device_candidates" -> findCandidates(principal, source, conversationId,
                    context.focusRegionId(), arguments);
            case "get_device_details" -> findCandidates(principal, source, conversationId,
                    context.focusRegionId(), detailQuery(arguments));
            case "read_device_states" -> readStates(principal, source, conversationId, arguments);
            case "inspect_device_capabilities" -> inspectCapabilities(principal, source, conversationId, arguments);
            case "propose_action_draft" -> proposeAction(principal, source, conversationId, turnId,
                    context.focusRegionId(), arguments);
            case "read_action_draft" -> readAction(principal, source, arguments);
            case "read_action_device_states" -> actions.deviceStates(principal, identifier(arguments), source.wireValue());
            case "read_action_summary" -> actions.summary(principal, identifier(arguments), source.wireValue());
            case "query_alerts" -> queryAlerts(principal, source, arguments);
            case "query_environment_history" -> observation(principal, source,
                    ObservationOperation.ENVIRONMENT_HISTORY, arguments);
            case "query_energy_history" -> observation(principal, source,
                    ObservationOperation.ENERGY_HISTORY, arguments);
            case "summarize_energy" -> observation(principal, source,
                    source == IotSource.COMPANY ? ObservationOperation.COMPANY_METER_REPORT : ObservationOperation.ENERGY_REPORT,
                    arguments);
            case "compare_energy" -> observation(principal, source,
                    source == IotSource.COMPANY ? ObservationOperation.COMPANY_METER_REPORT : ObservationOperation.ENERGY_COMPARISON,
                    arguments);
            case "prepare_video_playback" -> prepareVideoPlayback(principal, source, arguments);
            case "review_safety_event" -> reviewSafetyEvent(principal, source, arguments);
            case "create_energy_report" -> generateReport(principal, source, conversationId, turnId, arguments);
            case "propose_task" -> proposeTask(principal, source, conversationId, turnId, arguments);
            case "propose_task_change" -> proposeTaskChange(principal, source, conversationId, turnId, arguments);
            case "propose_task_cancellation" -> proposeTaskCancellation(principal, source, conversationId, turnId, arguments);
            case "list_tasks" -> listTasks(principal, source, arguments);
            case "list_task_plans" -> listTaskPlans(principal, source);
            case "get_task" -> getTask(principal, source, arguments);
            default -> throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "工具未接入", 404);
        });
    }

    private Map<String, Object> officeRegions(PrincipalInfo principal, Map<String, Object> args) {
        var regions = iot.loadOfficeRegions(identity(principal, IotSource.OFFICE)).block(TIMEOUT);
        if (regions == null) throw new BusinessException("REGION_UNAVAILABLE", "办公室授权区域暂不可用", 503);
        if (args.containsKey("nameContains")) {
            String name = stringValue(args.get("nameContains"), "INVALID_AGENT_TOOL", "区域名称筛选不能为空").strip();
            List<OfficeRegion> matches = new ArrayList<>();
            for (int index = 0; index < regions.size(); index++) {
                OfficeRegion region = regions.get(index);
                if (region.name().contains(name)) matches.add(region);
            }
            regions = matches;
        }
        return Map.of("regions", regions, "source", "office");
    }

    private AgentToolContext resolveContext(String toolContextId, String conversationId, String turnId, String runId) {
        AgentToolContext context;
        try {
            context = conversations.toolContext(toolContextId, conversationId, turnId, runId);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("AGENT_TOOL_RUN_UNAVAILABLE", "Agent 运行上下文不可用", 409);
        }
        if (context == null || context.principal() == null) {
            throw new BusinessException("AGENT_TOOL_RUN_UNAVAILABLE", "Agent 运行上下文不可用", 409);
        }
        if (!runId.equals(context.runId()) || !conversationId.equals(context.conversationId())
                || !turnId.equals(context.turnId())) {
            throw new BusinessException("AGENT_TOOL_CONTEXT_MISMATCH", "工具调用与已绑定运行上下文不匹配", 409);
        }
        return context;
    }

    private Map<String, Object> normalizeArguments(AgentToolCatalog.Definition definition, Object raw,
            AgentToolContext context, String turnId, String toolCallId) {
        Map<String, Object> supplied = raw == null ? Map.of() : map(raw);
        for (String key : supplied.keySet()) {
            if (FORBIDDEN_INTERNAL_ARGUMENTS.contains(key)) {
                throw new BusinessException("AGENT_TOOL_CONTEXT_MISMATCH", "工具参数携带了服务端专属上下文", 403);
            }
        }
        if (supplied.containsKey("source")) {
            String requestedSource = stringValue(supplied.get("source"), "INVALID_AGENT_TOOL_ARGUMENTS", "来源参数格式无效");
            if (!requestedSource.equals(context.source())) {
                throw new BusinessException("AGENT_TOOL_CONTEXT_MISMATCH", "工具参数来源与已绑定上下文不一致", 403);
            }
        }
        if (supplied.containsKey("focusRegionId")
                && !java.util.Objects.equals(emptyToNull(stringOrNull(supplied.get("focusRegionId"))),
                        emptyToNull(context.focusRegionId()))) {
            throw new BusinessException("AGENT_TOOL_CONTEXT_MISMATCH", "工具参数关注区域与已绑定上下文不一致", 403);
        }
        Set<String> allowed = new LinkedHashSet<>(AgentToolCatalog.topLevelArgumentNames(definition));
        allowed.addAll(CONTEXT_ARGUMENTS);
        if (definition.confirmation() != AgentToolCatalog.Confirmation.NONE) allowed.add("idempotencyKey");
        for (String key : supplied.keySet()) {
            if (!allowed.contains(key)) {
                throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "工具参数包含不支持的字段", 400);
            }
        }
        for (String required : AgentToolCatalog.requiredArgumentNames(definition)) {
            if (emptyArgument(supplied.get(required))) {
                throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "工具缺少必需参数", 400);
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(supplied);
        normalized.put("source", context.source());
        normalized.remove("focusRegionId");
        normalized.remove("idempotencyKey");
        if (Set.of("find_device_candidates", "propose_task").contains(definition.name()) && !isBlank(context.focusRegionId())) {
            normalized.put("focusRegionId", context.focusRegionId());
        }
        if (definition.confirmation() != AgentToolCatalog.Confirmation.NONE
                || "create_energy_report".equals(definition.name())) {
            normalized.put("idempotencyKey", turnId + ":" + toolCallId);
        }
        return normalized;
    }

    private Map<String, Object> generateReport(PrincipalInfo principal, IotSource source,
            String conversationId, String turnId, Map<String, Object> args) {
        if (reports == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "报告工具未接入", 404);
        if (source != IotSource.COMPANY) throw new BusinessException("REPORT_SOURCE_UNSUPPORTED", "报告只支持 Company 来源");
        Map<String, Object> content = businessArguments(args);
        ReportGenerationPort.GeneratedReport result = reports.generate(
                new RequestIdentity(principal.authorization(), principal.tenantCode(), IotSource.COMPANY,
                        principal.ownerKey()),
                conversationId, turnId, text(args, "idempotencyKey", turnId), content);
        return Map.of("reportId", result.reportId(), "reused", result.reused(), "report", result.report());
    }

    private Map<String, Object> prepareVideoPlayback(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        String deviceId = text(args, "deviceId", "");
        if (deviceId.isBlank()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
        return Map.of("videoPlayback", playbackCard(principal, source, deviceId,
                text(args, "beginTime", ""), text(args, "endTime", "")));
    }

    private Map<String, Object> playbackCard(PrincipalInfo principal, IotSource source, String deviceId,
            String beginTime, String endTime) {
        OffsetDateTime start = parseVideoTime(beginTime);
        OffsetDateTime finish = parseVideoTime(endTime);
        if (!finish.isAfter(start) || Duration.between(start.toInstant(), finish.toInstant()).toMillis() > 3 * 86_400_000L) {
            throw new BusinessException("INVALID_VIDEO_WINDOW", "录像窗口须带时区，结束晚于开始，单次最多三天", 400);
        }
        VideoPlaybackPreparation prepared = iot.prepareVideoPlayback(identity(principal, source), deviceId,
                new VideoPlaybackWindow(
                        start.withOffsetSameInstant(ZoneOffset.ofHours(8)).format(VIDEO_TIME),
                        finish.withOffsetSameInstant(ZoneOffset.ofHours(8)).format(VIDEO_TIME))).block(TIMEOUT);
        if (prepared == null) throw new BusinessException("VIDEO_RECORDING_UNAVAILABLE", "所选设备或录像窗口不可用", 404);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", prepared.source());
        report.put("deviceId", prepared.deviceId());
        report.put("name", prepared.name());
        report.put("location", prepared.location());
        report.put("beginTime", prepared.beginTime());
        report.put("endTime", prepared.endTime());
        return report;
    }

    /**
     * Resolves the source safety record and its real ±15-minute window through the
     * composed observation adapter, then validates and prepares playback only for
     * one server-authorized candidate selected by the user or the sole candidate.
     */
    private Map<String, Object> reviewSafetyEvent(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        if (source != IotSource.COMPANY) {
            throw new BusinessException("SAFETY_SOURCE_UNAVAILABLE", "安全记录监控复盘仅支持公司来源", 404);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("eventId", text(args, "eventId", ""));
        parameters.put("deviceId", text(args, "deviceId", ""));
        parameters.put("ledger", text(args, "ledger", ""));
        Map<String, Object> payload = observation(principal, source,
                ObservationOperation.SAFETY_EVENT_CAMERAS, parameters);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Object item : list(payload.get("candidates"))) {
            Map<String, Object> candidate = map(item);
            if (!candidate.isEmpty()) candidates.add(candidate);
        }
        Map<String, Object> selected = null;
        String requestedCamera = text(args, "cameraDeviceId", "");
        if (!requestedCamera.isBlank()) {
            selected = candidates.stream()
                    .filter(candidate -> requestedCamera.equals(text(candidate, "deviceId", "")))
                    .findFirst().orElse(null);
            if (selected == null) {
                throw new BusinessException("SAFETY_EVENT_CAMERA_UNAVAILABLE",
                        "所选监控不在该安全记录的当前授权候选中，请重新选择", 404);
            }
        } else if (candidates.size() == 1) {
            selected = candidates.get(0);
        }
        if (selected != null) {
            String cameraDeviceId = text(selected, "deviceId", "");
            if (cameraDeviceId.isBlank()) {
                throw new BusinessException("SAFETY_EVENT_CAMERA_UNAVAILABLE", "候选监控身份无效", 404);
            }
            Map<String, Object> report = playbackCard(principal, source, cameraDeviceId,
                    text(selected, "beginTime", ""), text(selected, "endTime", ""));
            Map<String, Object> safetyContext = map(selected.get("safetyContext"));
            if (!safetyContext.isEmpty()) report.put("safetyContext", safetyContext);
            payload.put("videoPlayback", report);
        }
        return payload;
    }

    private static OffsetDateTime parseVideoTime(String value) {
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (RuntimeException error) {
            throw new BusinessException("INVALID_VIDEO_WINDOW", "录像窗口须带时区，结束晚于开始，单次最多三天", 400);
        }
    }

    private Map<String, Object> queryAlerts(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        List<String> types = strings(args.get("types"));
        if (types.isEmpty()) types = List.of("smoke", "gas", "water");
        if (types.size() == 1) {
            Map<String, Object> normalized = new LinkedHashMap<>(args);
            normalized.put("type", types.get(0));
            return observation(principal, source, ObservationOperation.SAFETY_EVENTS, normalized);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        List<Object> items = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        Integer total = null;
        int scanned = 0;
        int excluded = 0;
        boolean complete = true;
        int available = 0;
        for (String type : types) {
            Map<String, Object> normalized = new LinkedHashMap<>(args);
            normalized.put("type", type);
            Map<String, Object> part;
            try {
                part = observation(principal, source, ObservationOperation.SAFETY_EVENTS, normalized);
            } catch (BusinessException error) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("type", type);
                failure.put("message", error.getMessage());
                failures.add(failure);
                complete = false;
                continue;
            }
            available += 1;
            if (merged.isEmpty()) merged.putAll(part);
            appendValues(items, part.get("items"));
            appendValues(items, part.get("records"));
            if (part.get("total") instanceof Number numeric) {
                total = (total == null ? 0 : total) + numeric.intValue();
            }
            if (part.get("scanned") instanceof Number numeric) scanned += numeric.intValue();
            if (part.get("excludedCount") instanceof Number numeric) excluded += numeric.intValue();
            complete &= !Boolean.FALSE.equals(part.get("coverageComplete"));
        }
        if (!items.isEmpty()) {
            merged.put("items", items);
            merged.remove("records");
        } else if (!merged.containsKey("items")) {
            merged.put("items", List.of());
        }
        merged.put("total", total);
        merged.put("scanned", scanned);
        merged.put("excludedCount", excluded);
        merged.put("coverageComplete", complete && failures.isEmpty());
        merged.put("summaryStatus", available == 0 ? "unavailable" : failures.isEmpty() && complete ? "complete" : "partial");
        if (!failures.isEmpty()) merged.put("failures", failures);
        return merged;
    }

    private static void appendValues(List<Object> target, Object value) {
        if (value instanceof List<?> values) target.addAll(values);
    }

    private Map<String, Object> observation(PrincipalInfo principal, IotSource source,
            ObservationOperation operation, Map<String, Object> args) {
        if (observations == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "只读观察工具未接入", 404);
        try {
            ObservationResponse result = observations.read(new ObservationRequest(identity(principal, source), operation,
                    parameterMap(normalizeObservationParameters(operation, args)))).block(TIMEOUT);
            if (result == null || result.status() == ObservationStatus.UNAVAILABLE) {
                throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "只读观察工具不可用", 404);
            }
            Map<String, Object> body = new LinkedHashMap<>(result.payload());
            if (result.status() != ObservationStatus.AVAILABLE && !body.containsKey("status")) {
                body.put("status", result.status().name().toLowerCase(java.util.Locale.ROOT));
            }
            if (!result.failures().isEmpty()) body.put("failures", result.failures());
            if (!result.coverageComplete()) body.put("coverageComplete", false);
            return body;
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "只读观察工具不可用", 404);
        }
    }

    private Map<String, Object> readAction(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        return actions.view(actions.current(principal, identifier(args), source.wireValue()));
    }

    private Map<String, Object> proposeTaskChange(PrincipalInfo principal, IotSource source,
            String conversationId, String turnId, Map<String, Object> args) {
        return tasks.proposalView(tasks.proposeChange(principal, source.wireValue(), conversationId, turnId,
                payloadJson(args), text(args, "idempotencyKey", "")));
    }

    private Map<String, Object> proposeTaskCancellation(PrincipalInfo principal, IotSource source,
            String conversationId, String turnId, Map<String, Object> args) {
        return tasks.proposalView(tasks.proposeCancellation(principal, source.wireValue(), conversationId, turnId,
                payloadJson(args), text(args, "idempotencyKey", "")));
    }

    private Map<String, Object> listTasks(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        if (projections == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "任务列表工具未接入", 404);
        try {
            Instant now = Instant.now();
            Instant from = instant(args.get("from"), now.minus(Duration.ofDays(365)));
            Instant to = instant(args.get("to"), now.plus(Duration.ofDays(365)));
            return Map.of("tasks", projections.list(principal, source.wireValue(), from, to));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "任务列表工具不可用", 404);
        }
    }

    private Map<String, Object> listTaskPlans(PrincipalInfo principal, IotSource source) {
        try {
            return Map.of("plans", tasks.plans(principal, source.wireValue()));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "任务计划工具不可用", 404);
        }
    }

    private Map<String, Object> getTask(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        String planId = text(args, "planId", "");
        if (!planId.isBlank()) return tasks.planView(tasks.plan(principal, planId, source.wireValue()));
        return tasks.view(tasks.current(principal, identifier(args), source.wireValue()));
    }

    private static Map<String, Object> detailQuery(Map<String, Object> args) {
        Map<String, Object> query = new LinkedHashMap<>(args);
        query.putIfAbsent("kind", "unknown");
        return query;
    }

    private String payloadJson(Map<String, Object> args) {
        try {
            return mapper.writeValueAsString(businessArguments(args));
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效");
        }
    }

    private static Map<String, Object> businessArguments(Map<String, Object> args) {
        Map<String, Object> result = new LinkedHashMap<>(args);
        result.remove("source");
        result.remove("focusRegionId");
        result.remove("idempotencyKey");
        return result;
    }

    private static Map<String, Object> normalizeObservationParameters(
            ObservationOperation operation, Map<String, Object> args) {
        Map<String, Object> normalized = new LinkedHashMap<>(args);
        if ((operation == ObservationOperation.SAFETY_EVENT || operation == ObservationOperation.SAFETY_EVENT_CAMERAS)
                && args.get("eventId") instanceof String eventId && !eventId.isBlank()) {
            normalized.put("id", eventId);
        }
        if (operation == ObservationOperation.SAFETY_EVENTS && args.get("types") instanceof List<?> types
                && types.size() == 1 && types.get(0) instanceof String type && !type.isBlank()) {
            normalized.put("type", type);
        }
        if (operation == ObservationOperation.COMPANY_METER_REPORT) {
            normalizeMeterScope(normalized, "scope", false);
            normalizeMeterScope(normalized, "other", true);
            if (args.get("baseline") instanceof Map<?, ?> baseline) {
                Map<String, Object> values = map(baseline);
                if (values.get("from") instanceof String from) normalized.put("baseFrom", from);
                if (values.get("to") instanceof String to) normalized.put("baseTo", to);
            }
            normalizeMeterInstant(normalized, "from");
            normalizeMeterInstant(normalized, "to");
            normalizeMeterInstant(normalized, "baseFrom");
            normalizeMeterInstant(normalized, "baseTo");
            normalized.put("kind", args.containsKey("axis") ? "comparison" : "statistics");
        }
        return normalized;
    }

    private static void normalizeMeterInstant(Map<String, Object> normalized, String key) {
        Object value = normalized.get(key);
        if (value instanceof String text && text.matches("\\d{4}-\\d{2}-\\d{2}")) {
            normalized.put(key, text + "T00:00:00+08:00");
        }
    }

    private static void normalizeMeterScope(Map<String, Object> normalized, String key, boolean other) {
        if (!(normalized.get(key) instanceof Map<?, ?> scope)) return;
        Object level = scope.get("level");
        if (level != null) normalized.put(other ? "otherScopeLevel" : "scopeLevel", level);
        Object floor = scope.get("floor");
        if (floor != null) normalized.put(other ? "otherFloor" : "floor", floor);
        Object text = scope.get("text");
        if (text != null) normalized.put(other ? "otherText" : "text", text);
        Object meterId = scope.get("meterId");
        if (meterId != null) normalized.put(other ? "otherMeterId" : "meterId", meterId);
        Object meterIds = scope.get("meterIds");
        if (meterIds != null) normalized.put(other ? "otherMeterIds" : "meterIds", meterIds);
    }

    private Map<String, String> parameterMap(Map<String, Object> args) {
        Map<String, String> result = new LinkedHashMap<>();
        args.forEach((key, value) -> {
            if ("source".equals(key) || "focusRegionId".equals(key) || "idempotencyKey".equals(key) || value == null) return;
            if (value instanceof String text && !text.isBlank()) result.put(key, text);
            else if (value instanceof Number || value instanceof Boolean) result.put(key, String.valueOf(value));
            else if (value instanceof Map<?, ?> || value instanceof List<?>) {
                try {
                    result.put(key, mapper.writeValueAsString(value));
                } catch (JsonProcessingException error) {
                    throw new BusinessException("INVALID_AGENT_TOOL", "观察参数格式无效");
                }
            }
        });
        return result;
    }

    private static String identifier(Map<String, Object> args) {
        String value = text(args, "draftId", "");
        if (value.isBlank()) value = text(args, "proposalId", "");
        if (value.isBlank()) value = text(args, "taskId", "");
        if (value.isBlank()) value = text(args, "planId", "");
        if (value.isBlank()) value = text(args, "id", "");
        if (value.isBlank()) throw new BusinessException("INVALID_AGENT_TOOL", "工具标识不能为空");
        return value;
    }

    private static Instant instant(Object value, Instant fallback) {
        if (!(value instanceof String text) || text.isBlank()) return fallback;
        try {
            return Instant.parse(text);
        } catch (RuntimeException error) {
            throw new BusinessException("INVALID_AGENT_TOOL", "时间范围无效");
        }
    }

    private Map<String, Object> findCandidates(PrincipalInfo principal, IotSource source, String conversationId,
            String focusRegionId, Map<String, Object> args) {
        String scopeText = nullableText(args, "scopeText");
        List<String> deviceIds = strings(args.get("deviceIds"));
        List<String> scopeTerms = strings(args.get("scopeTerms"));
        String regionId = null;
        if (source == IotSource.OFFICE && !isBlank(focusRegionId) && currentScope(scopeText)) {
            regionId = focusRegionId;
            scopeText = null;
            scopeTerms = List.of();
        }
        DeviceCandidateQuery query = new DeviceCandidateQuery(text(args, "kind", "unknown"), deviceIds,
                scopeText, scopeTerms, strings(args.get("nameHints")), nullableText(args, "deviceText"),
                regionId);
        DeviceCandidateSet candidates = iot.findDeviceCandidates(identity(principal, source), query,
                new DeviceCandidateBinding(conversationId)).block(TIMEOUT);
        if (candidates == null) throw new BusinessException("IOT_SCOPE_UNAVAILABLE", "设备候选范围暂不可用", 503);
        Map<String, Object> result = new LinkedHashMap<>(mapper.convertValue(candidates, Map.class));
        result.put("devices", candidates.devices().stream().map(AgentBusinessToolDispatcher::publicDevice).toList());
        return result;
    }

    private static Map<String, Object> publicDevice(AuthorizedDevice device) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", device.id());
        result.put("kind", device.kind());
        result.put("name", device.name());
        result.put("location", device.installAddress());
        result.put("observationOnly", device.observationOnly());
        result.put("locationPaths", device.locationPaths());
        return result;
    }

    private static boolean currentScope(String scopeText) {
        return scopeText == null || scopeText.isBlank() || "当前位置".equals(scopeText) || "当前区域".equals(scopeText)
                || "当前关注区域".equals(scopeText) || "办公室".equals(scopeText) || scopeText.startsWith("focusRegionId:");
    }

    private Map<String, Object> readStates(PrincipalInfo principal, IotSource source,
            String conversationId, Map<String, Object> args) {
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections =
                capabilitySelections(principal, source, conversationId, args);
        DeviceStateReadResult result = iot.readDeviceStates(identity(principal, source),
                new DeviceCapabilityRequest(selections), conversationId).block(TIMEOUT);
        if (result == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "设备状态暂不可用", 404);
        return mapper.convertValue(result, Map.class);
    }

    private Map<String, Object> inspectCapabilities(PrincipalInfo principal, IotSource source,
            String conversationId, Map<String, Object> args) {
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections =
                capabilitySelections(principal, source, conversationId, args);
        DeviceCapabilityInspection result = iot.inspectDeviceCapabilities(identity(principal, source),
                new DeviceCapabilityRequest(selections), conversationId).block(TIMEOUT);
        if (result == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "设备能力暂不可用", 404);
        return mapper.convertValue(result, Map.class);
    }

    private List<DeviceCapabilityRequest.DeviceCapabilitySelection> capabilitySelections(
            PrincipalInfo principal, IotSource source, String conversationId, Map<String, Object> args) {
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections = new ArrayList<>();
        for (Object value : list(args.get("candidateSets"))) {
            Map<String, Object> item = map(value);
            String setId = text(item, "candidateSetId", "");
            List<String> ids = strings(item.get("deviceIds"));
            if (Boolean.TRUE.equals(item.get("all"))) {
                if (item.containsKey("deviceIds")) throw new BusinessException(
                        "INVALID_DEVICE_REFERENCE", "完整候选范围与精确设备选择不能混用");
                var stored = iot.resolveDeviceCandidateSet(identity(principal, source), setId, conversationId).block(TIMEOUT);
                if (stored == null || stored.members().size() != stored.totalMatches()) throw new BusinessException(
                        "CANDIDATE_SET_INCOMPLETE", "候选范围尚未完整取得，不能按全部读取", 409);
                ids = stored.members().stream().map(ltd.qianc.iotagent.iot.AuthorizedDevice::id).toList();
            }
            if (ids.isEmpty()) throw new BusinessException(
                    "INVALID_DEVICE_REFERENCE", "请选择明确设备或显式指定完整候选范围");
            selections.add(new DeviceCapabilityRequest.DeviceCapabilitySelection(setId, ids));
        }
        if (selections.isEmpty()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
        return selections;
    }

    private Map<String, Object> proposeAction(PrincipalInfo principal, IotSource source,
            String conversationId, String turnId, String focusRegionId, Map<String, Object> args) {
        try {
            Map<String, Object> payload = businessArguments(args);
            payload.put("actions", normalizeActionEntries(payload.get("actions")));
            return actions.view(actions.propose(principal, source.wireValue(), conversationId, turnId,
                    mapper.writeValueAsString(payload), text(args, "idempotencyKey", turnId),
                    isBlank(focusRegionId) ? null : focusRegionId));
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "草案内容无效");
        }
    }

    private static List<Map<String, Object>> normalizeActionEntries(Object value) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list(value)) {
            Map<String, Object> action = new LinkedHashMap<>(map(item));
            if (!action.containsKey("kind") || !action.containsKey("command")) {
                String kind = action.get("kind") instanceof String valueKind ? valueKind : "";
                Object ac = action.get("ac");
                if ("ac".equals(kind) && ac instanceof Map<?, ?>) {
                    action.put("command", map(ac));
                } else if ("lighting".equals(kind) && action.get("closeOrOpen") instanceof Number power) {
                    action.put("command", Map.of("closeOrOpen", power.intValue()));
                } else if ("access".equals(kind) && action.get("controlType") instanceof Number controlType) {
                    action.put("command", Map.of("controlType", controlType.intValue()));
                } else if (ac instanceof Map<?, ?>) {
                    action.put("kind", "ac");
                    action.put("command", map(ac));
                } else if (action.get("closeOrOpen") instanceof Number power) {
                    action.put("kind", "lighting");
                    action.put("command", Map.of("closeOrOpen", power.intValue()));
                } else if (action.get("controlType") instanceof Number controlType) {
                    action.put("kind", "access");
                    action.put("command", Map.of("controlType", controlType.intValue()));
                }
            }
            normalized.add(action);
        }
        return normalized;
    }

    private Map<String, Object> proposeTask(PrincipalInfo principal, IotSource source,
            String conversationId, String turnId, Map<String, Object> args) {
        try {
            Map<String, Object> payload = businessArguments(args);
            if (source == IotSource.OFFICE && args.containsKey("focusRegionId")) payload.put("focusRegionId", args.get("focusRegionId"));
            return tasks.proposalView(tasks.propose(principal, source.wireValue(), conversationId, turnId,
                    mapper.writeValueAsString(payload), text(args, "idempotencyKey", "")));
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效");
        }
    }

    private static RequestIdentity identity(PrincipalInfo principal, IotSource source) {
        return new RequestIdentity(principal.authorization(), principal.tenantCode(), source, principal.ownerKey());
    }

    private static IotSource parseSource(String source) {
        try {
            return IotSource.parse(source);
        } catch (RuntimeException error) {
            throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        }
    }

    private static String requiredText(Map<String, Object> body, String key, String message) {
        String value = text(body, key, "");
        if (value.isBlank()) throw new BusinessException("INVALID_AGENT_TOOL", message, 400);
        return value;
    }

    private static String stringValue(Object value, String code, String message) {
        if (!(value instanceof String text) || text.isBlank()) throw new BusinessException(code, message, 400);
        return text;
    }

    private static String stringOrNull(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean emptyArgument(Object value) {
        if (value == null) return true;
        if (value instanceof String text) return text.isBlank();
        if (value instanceof List<?> list) return list.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        return false;
    }

    private static String text(Map<String, Object> body, String key, String fallback) {
        Object value = body.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static String nullableText(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : list(value)) if (item instanceof String text && !text.isBlank()) result.add(text);
        return result;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw new BusinessException("INVALID_AGENT_TOOL", "工具参数格式无效");
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
