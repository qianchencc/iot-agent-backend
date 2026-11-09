package ltd.qianc.iotagent.agent.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSet;
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperationEnum;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatusEnum;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.VideoPlaybackPreparation;
import ltd.qianc.iotagent.iot.VideoPlaybackWindow;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private static final java.util.regex.Pattern DEVICE_HANDLE =
            java.util.regex.Pattern.compile("^d(\\d+)_(\\d+)$");
    private static final java.util.regex.Pattern REGION_NAME_SPACING =
            java.util.regex.Pattern.compile("[\\s\\p{Z}]+");
    private static final Set<String> ALERT_TYPES = Set.of("smoke", "gas", "water");
    private static final java.util.regex.Pattern ZONED_TIME =
            java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?(?:Z|[+-]\\d{2}:\\d{2})$");

    private final ConversationFacade conversations;
    private final IotAdapter iot;
    private final ObservationAdapter observations;
    private final TaskProjectionStore projections;
    private final ActionDraftFacade actions;
    private final TaskFacade tasks;
    private final ObjectMapper mapper;
    private final ReportGenerationPort reports;
    private final ltd.qianc.iotagent.report.ReportService savedReports;
    private final AgentRunGuard guard;
    private final java.time.Clock clock;
    /** Run-scoped business state mirroring the Runtime runner loop (listed tasks, task-proposal count). */
    private final RunTaskState taskState = new RunTaskState();

    public AgentBusinessToolDispatcher(ConversationFacade conversations, IotAdapter iot,
            ObservationAdapter observations, TaskProjectionStore projections,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports,
            ltd.qianc.iotagent.report.ReportService savedReports) {
        this(conversations, iot, observations, projections, actions, tasks, mapper, reports, savedReports,
                java.time.Clock.systemUTC());
    }

    public AgentBusinessToolDispatcher(ConversationFacade conversations, IotAdapter iot,
            ObservationAdapter observations, TaskProjectionStore projections,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports,
            ltd.qianc.iotagent.report.ReportService savedReports, java.time.Clock clock) {
        this.conversations = conversations;
        this.iot = iot;
        this.observations = observations;
        this.projections = projections;
        this.actions = actions;
        this.tasks = tasks;
        this.mapper = mapper;
        this.guard = new AgentRunGuard(mapper);
        this.reports = reports;
        this.savedReports = savedReports;
        this.clock = clock;
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
        IotSourceEnum source = parseSource(context.source());
        PrincipalInfo principal = context.principal();
        AgentToolCatalog.Definition definition = AgentToolCatalog.find(source.wireValue(), toolName);
        Map<String, Object> arguments = normalizeArguments(definition, request.get("arguments"), context,
                turnId, toolCallId);
        if (source == IotSourceEnum.COMPANY && Set.of("query_energy_history", "summarize_energy",
                "compare_energy", "create_energy_report").contains(toolName)) {
            resolveEnergyReferences(principal, source, conversationId, arguments);
        }
        return guard.invoke(toolContextId, toolCallId, definition, arguments,
                ref -> iot.resolveDeviceCandidateSet(identity(principal, source), ref, conversationId).block(TIMEOUT), () -> {
            Map<String, Object> result = switch (definition.name()) {
            case "list_office_regions" -> officeRegions(principal, arguments);
            case "find_device_candidates" -> findCandidates(principal, source, conversationId,
                    context.focusRegionId(), arguments, toolContextId);
            case "get_device_details" -> deviceDetails(principal, source, conversationId, arguments);
            case "read_device_states" -> readStates(principal, source, conversationId, arguments);
            case "inspect_device_capabilities" -> inspectCapabilities(principal, source, conversationId, arguments);
            case "propose_action_draft" -> proposeAction(principal, source, conversationId, turnId,
                    context.focusRegionId(), arguments);
            case "propose_lighting_draft" -> proposeLighting(principal, source, conversationId, turnId,
                    context.focusRegionId(), arguments);
            case "read_action_draft" -> readAction(principal, source, arguments);
            case "read_action_device_states" -> actions.deviceStates(principal, identifier(arguments), source.wireValue());
            case "read_action_summary" -> actions.summary(principal, identifier(arguments), source.wireValue());
            case "query_alerts" -> queryAlerts(principal, source, arguments);
            case "query_environment_history" -> observation(principal, source,
                    ObservationOperationEnum.ENVIRONMENT_HISTORY, arguments);
            case "query_energy_history" -> observation(principal, source,
                    ObservationOperationEnum.ENERGY_HISTORY, arguments);
            case "summarize_energy" -> observation(principal, source,
                    source == IotSourceEnum.COMPANY ? ObservationOperationEnum.COMPANY_METER_REPORT : ObservationOperationEnum.ENERGY_REPORT,
                    arguments);
            case "compare_energy" -> observation(principal, source,
                    source == IotSourceEnum.COMPANY ? ObservationOperationEnum.COMPANY_METER_REPORT : ObservationOperationEnum.ENERGY_COMPARISON,
                    arguments);
            case "prepare_video_playback" -> prepareVideoPlayback(principal, source, conversationId, arguments);
            case "review_safety_event" -> reviewSafetyEvent(principal, source, conversationId, arguments);
            case "create_energy_report" -> generateReport(principal, source, conversationId, turnId, context.reference(), arguments);
            case "propose_task" -> proposeTask(toolContextId, principal, source, conversationId, turnId, arguments);
            case "propose_task_change" -> proposeTaskChange(principal, source, conversationId, turnId, arguments);
            case "propose_task_cancellation" -> proposeTaskCancellation(toolContextId, principal, source, conversationId, turnId, arguments);
            case "list_tasks" -> listTasks(toolContextId, principal, source, arguments);
            case "list_task_plans" -> listTaskPlans(principal, source);
            case "get_task" -> getTask(toolContextId, principal, source, arguments);
            default -> throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "工具未接入", 404);
            };
            if ("query_alerts".equals(definition.name())) {
                Map<String, Object> model = new LinkedHashMap<>();
                for (String key : List.of("status", "total", "scanned", "excludedCount", "excludedCountNote",
                        "coverageComplete", "summaryStatus", "windowDays", "failures")) {
                    if (result.containsKey(key)) model.put(key, result.get(key));
                }
                if (result.get("items") instanceof List<?> items) model.put("itemsCount", items.size());
                result.put("modelResult", model);
            }
            if (Set.of("propose_action_draft", "propose_lighting_draft").contains(toolName)
                    && ("PENDING_CONFIRMATION".equals(result.get("status")) || "NO_CHANGE".equals(result.get("status")))) {
                Map<String, Object> fact = new LinkedHashMap<>();
                fact.put("status", "NO_CHANGE".equals(result.get("status")) ? "no_change" : "awaiting_user_confirmation");
                List<Map<String, Object>> scopes = new ArrayList<>();
                List<String> refs = strings(arguments.get("candidateSetIds"));
                if (arguments.get("candidateSetId") instanceof String ref) refs = List.of(ref);
                for (String ref : refs) {
                    var stored = iot.resolveDeviceCandidateSet(identity(principal, source), ref, conversationId).block(TIMEOUT);
                    if (stored != null) scopes.add(Map.of("scope", stored.query().scopeText() == null
                            ? String.join("/", stored.query().scopeTerms()) : stored.query().scopeText(), "deviceCount", stored.totalMatches()));
                }
                fact.put("candidateScopes", scopes);
                int actionCount = (int) list(result.get("actions")).stream()
                        .map(AgentBusinessToolDispatcher::map)
                        .filter(action -> !Boolean.TRUE.equals(action.get("satisfied"))).count();
                fact.put("actionCount", actionCount);
                if (result.get("counts") instanceof Map<?, ?> counts) fact.putAll(map(counts));
                fact.put("executed", false);
                if ("propose_lighting_draft".equals(toolName)) {
                    Map<String, Object> counts = map(result.get("counts"));
                    Map<String, Object> model = new LinkedHashMap<>();
                    model.put("status", actionCount > 0 ? "draft_ready" : "no_change");
                    model.put("includedScopes", scopes.stream().map(scope -> Map.of(
                            "scope", scope.get("scope"), "count", scope.get("deviceCount"))).toList());
                    model.put("scopeCount", counts.get("total"));
                    model.put("actionCount", actionCount);
                    model.put("excludedCount", counts.get("excluded"));
                    model.put("alreadySatisfiedCount", counts.get("satisfied"));
                    model.put("statusMeaning", actionCount > 0 ? "仅生成待确认草案，设备控制尚未执行"
                            : "目标已满足，本次没有待下发操作，也未执行控制");
                    model.put("actionCountMeaning", "待用户在卡片确认后才下发的操作数");
                    model.put("note", "完整具体设备与排除项已由草案和设备清单展示；正文简述范围与排除，不复读全部成员，不声称已执行。");
                    result.put("modelResult", model);
                }
                attachClosingContext(toolContextId, result, mapper.valueToTree(Map.of("currentActionProposal", fact)).toString());
            }
            return result;
        });
    }

    private Map<String, Object> officeRegions(PrincipalInfo principal, Map<String, Object> args) {
        var regions = iot.loadOfficeRegions(identity(principal, IotSourceEnum.OFFICE)).block(TIMEOUT);
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
        if (definition.confirmation() != AgentToolCatalog.ConfirmationEnum.NONE) allowed.add("idempotencyKey");
        for (String key : supplied.keySet()) {
            if (!allowed.contains(key)) {
                throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "工具参数包含不支持的字段", 400);
            }
        }
        for (String required : AgentToolCatalog.requiredArgumentNames(definition)) {
            if (!supplied.containsKey(required) || supplied.get(required) == null
                    || supplied.get(required) instanceof String text && text.isBlank()) {
                throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "工具缺少必需参数", 400);
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>(supplied);
        normalized.put("source", context.source());
        normalized.remove("focusRegionId");
        normalized.remove("idempotencyKey");
        if (Set.of("find_device_candidates", "propose_task", "list_tasks").contains(definition.name()) && !isBlank(context.focusRegionId())) {
            normalized.put("focusRegionId", context.focusRegionId());
        }
        if (definition.confirmation() != AgentToolCatalog.ConfirmationEnum.NONE
                || "create_energy_report".equals(definition.name())) {
            normalized.put("idempotencyKey", turnId + ":" + toolCallId);
        }
        return normalized;
    }

    private Map<String, Object> generateReport(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, String turnId, Map<String, Object> reference, Map<String, Object> args) {
        if (reports == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "报告工具未接入", 404);
        if (source != IotSourceEnum.COMPANY) throw new BusinessException("REPORT_SOURCE_UNSUPPORTED", "报告只支持 Company 来源");
        Map<String, Object> content = new LinkedHashMap<>(map(args.get("spec")));
        content.put("kind", "energy");
        content.put("title", args.get("title"));
        List<String> meterIds = args.containsKey("deviceIds") ? strings(args.get("deviceIds")) : null;
        if (meterIds == null && reference != null
                && ("report".equals(reference.get("type")) || "energy_report".equals(reference.get("type")))) {
            if (savedReports == null) throw new BusinessException("REPORT_FACT_UNAVAILABLE", "报告事实暂不可读取", 503);
            Map<String, Object> report = savedReports.read(text(reference, "reportId", ""), identity(principal, source));
            meterIds = strings(report.get("meterIds"));
            if (meterIds.isEmpty() && report.get("requirements") instanceof Map<?, ?> requirements) {
                meterIds = strings(requirements.get("meterIds"));
            }
        }
        if (meterIds != null) {
            if (meterIds.isEmpty()) throw new BusinessException("INVALID_REPORT", "报告计量范围为空", 400);
            content.put("scope", Map.of("level", "selection", "meterIds", meterIds));
        } else {
            content.put("scope", Map.of("level", "all"));
        }
        try {
            ReportGenerationPort.GeneratedReport result = reports.generate(
                    new RequestIdentity(principal.authorization(), principal.tenantCode(), IotSourceEnum.COMPANY,
                            principal.ownerKey()), conversationId, turnId, text(args, "idempotencyKey", turnId), content);
            return Map.of("reportId", result.reportId(), "reused", result.reused(), "report", result.report());
        } catch (BusinessException failure) {
            if (failure.httpStatus() < 500) throw failure;
            return Map.of("status", "unavailable", "energyRead", "unavailable",
                    "summary", failure.getMessage(), "energyMessage", failure.getMessage(),
                    "requirements", content, "retryable", true);
        }
    }

    private Map<String, Object> prepareVideoPlayback(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, Map<String, Object> args) {
        String deviceId = text(args, "deviceId", "");
        if (deviceId.isBlank()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
        deviceId = resolveStandaloneDeviceRef(principal, source, conversationId, deviceId);
        return Map.of("videoPlayback", playbackCard(principal, source, deviceId,
                text(args, "beginTime", ""), text(args, "endTime", "")));
    }

    /**
     * Runtime playback/safety tools resolve candidate handles against the sets of
     * this conversation; a d{N}_{k} ref loads its s{N} record with full ownership
     * checks, any other ref passes through untouched.
     */
    private String resolveStandaloneDeviceRef(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, String ref) {
        java.util.regex.Matcher handle = DEVICE_HANDLE.matcher(ref);
        if (!handle.matches()) return ref;
        var stored = iot.resolveDeviceCandidateSet(identity(principal, source), "s" + handle.group(1), conversationId)
                .block(TIMEOUT);
        return stored == null ? ref : stored.resolveDeviceRef(ref);
    }

    private void resolveEnergyReferences(PrincipalInfo principal, IotSourceEnum source, String conversationId,
            Map<String, Object> arguments) {
        java.util.function.Function<String, String> resolve =
                ref -> resolveStandaloneDeviceRef(principal, source, conversationId, ref);
        if (arguments.get("equipmentId") instanceof String ref) arguments.put("equipmentId", resolve.apply(ref));
        if (arguments.get("deviceIds") instanceof List<?> refs) {
            arguments.put("deviceIds", refs.stream().map(String::valueOf).map(resolve).toList());
        }
        for (String key : List.of("scope", "other")) if (arguments.get(key) instanceof Map<?, ?> rawScope) {
            Map<String, Object> scope = new LinkedHashMap<>(map(rawScope));
            if (scope.get("meterId") instanceof String ref) scope.put("meterId", resolve.apply(ref));
            if (scope.get("meterIds") instanceof List<?> refs) scope.put("meterIds", refs.stream().map(String::valueOf).map(resolve).toList());
            arguments.put(key, scope);
        }
    }

    private Map<String, Object> playbackCard(PrincipalInfo principal, IotSourceEnum source, String deviceId,
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
    private Map<String, Object> reviewSafetyEvent(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, Map<String, Object> args) {
        if (source != IotSourceEnum.COMPANY) {
            throw new BusinessException("SAFETY_SOURCE_UNAVAILABLE", "安全记录监控复盘仅支持公司来源", 404);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("eventId", text(args, "eventId", ""));
        parameters.put("deviceId", text(args, "deviceId", ""));
        parameters.put("ledger", text(args, "ledger", ""));
        Map<String, Object> payload = observation(principal, source,
                ObservationOperationEnum.SAFETY_EVENT_CAMERAS, parameters);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Object item : list(payload.get("candidates"))) {
            Map<String, Object> candidate = map(item);
            if (!candidate.isEmpty()) candidates.add(candidate);
        }
        Map<String, Object> selected = null;
        String requestedCamera = text(args, "cameraDeviceId", "");
        if (!requestedCamera.isBlank()) {
            final String resolvedCamera = resolveStandaloneDeviceRef(principal, source, conversationId, requestedCamera);
            selected = candidates.stream()
                    .filter(candidate -> resolvedCamera.equals(text(candidate, "deviceId", "")))
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

    private Map<String, Object> queryAlerts(PrincipalInfo principal, IotSourceEnum source, Map<String, Object> args) {
        List<String> types = strings(args.get("types"));
        if (types.isEmpty()) types = List.of("smoke", "gas", "water");
        for (String type : types) {
            if (!ALERT_TYPES.contains(type)) {
                throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "告警类型仅支持烟感、水浸、燃气", 400);
            }
        }
        // Runtime queryAlertsTool: days defaults to 30; from/to must arrive as a pair of
        // zoned ISO instants with since < to <= now, and days derives the window otherwise.
        Instant now = clock.instant();
        int days = 30;
        if (args.get("days") instanceof Number numeric) {
            days = numeric.intValue();
            if (days < 1 || days > 365) {
                throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "回看天数须在1–365天之间", 400);
            }
        }
        String from = text(args, "from", "");
        String to = text(args, "to", "");
        Map<String, Object> window = new LinkedHashMap<>(args);
        window.remove("days");
        boolean explicitWindow = !from.isBlank() || !to.isBlank();
        if (from.isBlank() != to.isBlank()) {
            throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "告警统计的from和to必须同时提供；否则请只提供days", 400);
        }
        Instant windowTo = now;
        if (explicitWindow) {
            if (!ZONED_TIME.matcher(from).matches() || !ZONED_TIME.matcher(to).matches()) {
                throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "告警统计窗口无效，请提供明确上海时区、起点早于终点且不晚于当前时间的范围", 400);
            }
            try {
                Instant since = OffsetDateTime.parse(from).toInstant();
                windowTo = OffsetDateTime.parse(to).toInstant();
                if (!since.isBefore(windowTo) || windowTo.isAfter(now)) {
                    throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "告警统计窗口无效，请提供明确上海时区、起点早于终点且不晚于当前时间的范围", 400);
                }
                window.put("from", from);
                window.put("to", to);
            } catch (java.time.format.DateTimeParseException invalid) {
                throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "告警统计窗口无效，请提供明确上海时区、起点早于终点且不晚于当前时间的范围", 400);
            }
        } else {
            // days drives the window start; the ledger composer reads "to" as now when absent.
            window.put("from", now.minus(Duration.ofDays(days)).toString());
        }
        boolean unreportedOnly = Boolean.TRUE.equals(args.get("unreportedOnly"));
        Map<String, Object> result;
        if (types.size() == 1) {
            Map<String, Object> normalized = new LinkedHashMap<>(window);
            normalized.put("type", types.get(0));
            result = observation(principal, source, ObservationOperationEnum.SAFETY_EVENTS, normalized);
        } else {
            result = new LinkedHashMap<>();
            List<Object> items = new ArrayList<>();
            List<Map<String, Object>> failures = new ArrayList<>();
            Integer total = null;
            int scanned = 0;
            int excluded = 0;
            boolean complete = true;
            int available = 0;
            // Runtime app.ts queries independent ledger types together; merge only
            // after each type settles so a failed ledger retains the others' evidence.
            record AlertPart(String type, Map<String, Object> value, BusinessException error) { }
            List<AlertPart> parts = Flux.fromIterable(types)
                    .flatMapSequential(type -> Mono.fromCallable(() -> {
                        Map<String, Object> normalized = new LinkedHashMap<>(window);
                        normalized.put("type", type);
                        try {
                            return new AlertPart(type,
                                    observation(principal, source, ObservationOperationEnum.SAFETY_EVENTS, normalized), null);
                        } catch (BusinessException error) {
                            return new AlertPart(type, null, error);
                        }
                    }).subscribeOn(Schedulers.boundedElastic()), 6)
                    .collectList().block();
            for (AlertPart item : parts) {
                Map<String, Object> part = item.value();
                if (item.error() != null) {
                    Map<String, Object> failure = new LinkedHashMap<>();
                    failure.put("type", item.type());
                    failure.put("message", item.error().getMessage());
                    failures.add(failure);
                    complete = false;
                    continue;
                }
                available += 1;
                if (result.isEmpty()) result.putAll(part);
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
                result.put("items", items);
                result.remove("records");
            } else if (!result.containsKey("items")) {
                result.put("items", List.of());
            }
            result.put("total", total);
            result.put("scanned", scanned);
            result.put("excludedCount", excluded);
            result.put("coverageComplete", complete && failures.isEmpty());
            result.put("summaryStatus", available == 0 ? "unavailable" : failures.isEmpty() && complete ? "complete" : "partial");
            if (!failures.isEmpty()) result.put("failures", failures);
        }
        if (result.get("excludedCount") instanceof Number excluded && excluded.longValue() > 0) {
            result.put("excludedCountNote", "excludedCount 是在查询的授权设备和所请求告警类型范围内被过滤的记录数；"
                    + "不是时间窗外的记录数。窗口外记录另行处理，不计入该字段。");
        }
        if (unreportedOnly && result.get("items") instanceof List<?> rows) {
            result.put("items", rows.stream()
                    .filter(row -> row instanceof Map<?, ?> record && Boolean.FALSE.equals(record.get("report")))
                    .toList());
        }
        result.put("windowDays", explicitWindow ? 0 : days);
        return result;
    }

    private static void appendValues(List<Object> target, Object value) {
        if (value instanceof List<?> values) target.addAll(values);
    }

    private Map<String, Object> observation(PrincipalInfo principal, IotSourceEnum source,
            ObservationOperationEnum operation, Map<String, Object> args) {
        if (observations == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "只读观察工具未接入", 404);
        try {
            ObservationResponse result = observations.read(new ObservationRequest(identity(principal, source), operation,
                    parameterMap(normalizeObservationParameters(operation, args)))).block(TIMEOUT);
            if (result == null || result.status() == ObservationStatusEnum.UNAVAILABLE) {
                if (source == IotSourceEnum.COMPANY && operation == ObservationOperationEnum.COMPANY_METER_REPORT) {
                    return Map.of("status", "unavailable", "energyRead", "unavailable",
                            "energyMessage", "电表数据暂不可用，请稍后重试", "retryable", true);
                }
                throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "只读观察工具不可用", 404);
            }
            Map<String, Object> body = new LinkedHashMap<>(result.payload());
            if (result.status() != ObservationStatusEnum.AVAILABLE && !body.containsKey("status")) {
                body.put("status", result.status().name().toLowerCase(java.util.Locale.ROOT));
            }
            if (!result.failures().isEmpty()) body.put("failures", result.failures());
            if (!result.coverageComplete()) body.put("coverageComplete", false);
            if ("meter-report".equals(body.get("type"))) {
                Map<String, Object> facts = AgentEnergyRunFacts.modelFacts(body);
                body.put("dataProvenance", facts.get("dataProvenance"));
                body.put("modelResult", facts);
            }
            return body;
        } catch (BusinessException error) {
            if (source == IotSourceEnum.COMPANY && operation == ObservationOperationEnum.COMPANY_METER_REPORT
                    && error.httpStatus() >= 500) {
                return Map.of("status", "unavailable", "energyRead", "unavailable", "energyMessage", error.getMessage(),
                        "message", error.getMessage(), "retryable", true);
            }
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "只读观察工具不可用", 404);
        }
    }

    private Map<String, Object> readAction(PrincipalInfo principal, IotSourceEnum source, Map<String, Object> args) {
        return actions.view(actions.current(principal, identifier(args), source.wireValue()));
    }

    private Map<String, Object> proposeTaskChange(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, String turnId, Map<String, Object> args) {
        Map<String, Object> payload = businessArguments(args);
        String type = text(args, "type", "");
        payload.remove("type");
        payload.remove("id");
        payload.put("target", taskTarget(type, identifier(args)));
        if (payload.get("template") instanceof Map<?, ?> rawTemplate) {
            Map<String, Object> template = new LinkedHashMap<>(map(rawTemplate));
            if (template.get("phases") instanceof List<?> phases) {
                List<Map<String, Object>> normalized = new ArrayList<>();
                for (Object value : phases) {
                    Map<String, Object> phase = new LinkedHashMap<>(map(value));
                    if (phase.containsKey("actions")) {
                        phase.put("actions", normalizeActionEntries(phase.get("actions")).stream()
                                .map(action -> resolvedAction(action, principal, source, conversationId))
                                .toList());
                    }
                    if (phase.get("read") instanceof Map<?, ?> rawRead) {
                        Map<String, Object> read = new LinkedHashMap<>(map(rawRead));
                        if (read.get("deviceIds") instanceof List<?> ids) {
                            read.put("deviceIds", ids.stream()
                                    .map(id -> resolveStandaloneDeviceRef(principal, source, conversationId, String.valueOf(id)))
                                    .toList());
                        }
                        phase.put("read", read);
                    }
                    normalized.add(phase);
                }
                template.put("phases", normalized);
            }
            payload.put("template", template);
        }
        return tasks.proposalView(tasks.proposeChange(principal, source.wireValue(), conversationId, turnId,
                payloadJson(payload), text(args, "idempotencyKey", "")));
    }

    private Map<String, Object> resolvedAction(Map<String, Object> action, PrincipalInfo principal,
            IotSourceEnum source, String conversationId) {
        Map<String, Object> resolved = new LinkedHashMap<>(action);
        if (resolved.get("deviceId") instanceof String id) {
            resolved.put("deviceId", resolveStandaloneDeviceRef(principal, source, conversationId, id));
        }
        return resolved;
    }

    private Map<String, Object> proposeTaskCancellation(String toolContextId, PrincipalInfo principal,
            IotSourceEnum source, String conversationId, String turnId, Map<String, Object> args) {
        String taskId = identifier(args);
        // Runtime cancelTaskTool only accepts ids this run has actually listed or read.
        if (!taskState.listedTaskIds(toolContextId).contains(taskId)) {
            throw new BusinessException("TASK_NOT_LISTED", "请先查询并核对要取消的任务", 400);
        }
        return tasks.proposalView(tasks.proposeCancellation(principal, source.wireValue(), conversationId, turnId,
                payloadJson(Map.of("target", taskTarget("task", taskId))), text(args, "idempotencyKey", "")));
    }

    private Map<String, Object> listTasks(String toolContextId, PrincipalInfo principal, IotSourceEnum source,
            Map<String, Object> args) {
        if (projections == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "任务列表工具未接入", 404);
        LocalDate date;
        try {
            date = LocalDate.parse(text(args, "date", ""));
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new BusinessException("INVALID_AGENT_TOOL_ARGUMENTS", "任务日期须为中国标准时间的 YYYY-MM-DD", 400);
        }
        Instant from = date.atStartOfDay().toInstant(ZoneOffset.ofHours(8));
        Instant to = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.ofHours(8));
        String regionId = text(args, "regionId", text(args, "focusRegionId", ""));
        if (source == IotSourceEnum.OFFICE && regionId.isBlank()) {
            throw new BusinessException("REGION_REQUIRED", "请先确定要查询的区域", 400);
        }
        List<Map<String, Object>> found = regionId.isBlank()
                ? projections.list(principal, source.wireValue(), from, to)
                : projections.regionTimeline(principal, source.wireValue(), regionId, from, to);
        for (Map<String, Object> task : found) {
            if (task.get("taskId") instanceof String id) taskState.listTask(toolContextId, id);
        }
        return Map.of("regionId", regionId, "date", date.toString(), "tasks", found);
    }

    private Map<String, Object> listTaskPlans(PrincipalInfo principal, IotSourceEnum source) {
        try {
            return Map.of("plans", tasks.plans(principal, source.wireValue()));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "任务计划工具不可用", 404);
        }
    }

    private Map<String, Object> getTask(String toolContextId, PrincipalInfo principal, IotSourceEnum source,
            Map<String, Object> args) {
        String id = identifier(args);
        if ("task_plan".equals(args.get("type"))) return Map.of("plan", tasks.planView(principal, id, source.wireValue()));
        if (!"task".equals(args.get("type"))) throw new BusinessException("INVALID_CHANGE", "目标引用无效", 400);
        taskState.listTask(toolContextId, id);
        return Map.of("task", tasks.taskView(principal, id, source.wireValue()));
    }

    private static Map<String, Object> taskTarget(String type, String id) {
        return switch (type) {
            case "task" -> Map.of("type", "task", "taskId", id);
            case "task_plan" -> Map.of("type", "task_plan", "planId", id);
            default -> throw new BusinessException("INVALID_CHANGE", "目标引用无效", 400);
        };
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
            ObservationOperationEnum operation, Map<String, Object> args) {
        Map<String, Object> normalized = new LinkedHashMap<>(args);
        if (operation == ObservationOperationEnum.ENVIRONMENT_HISTORY) {
            normalized.remove("page");
            normalized.put("fullWindow", true);
        }
        if (operation == ObservationOperationEnum.COMPANY_METER_REPORT
                && args.get("dailyWindow") instanceof Map<?, ?> dailyWindow) {
            // Original tool vocabulary dailyWindow{from,to} reaches the composer seam
            // as its windowFrom/windowTo night-window parameters.
            if (dailyWindow.get("from") instanceof String windowFrom
                    && dailyWindow.get("to") instanceof String windowTo) {
                normalized.put("windowFrom", windowFrom);
                normalized.put("windowTo", windowTo);
            }
            normalized.remove("dailyWindow");
        }
        if ((operation == ObservationOperationEnum.SAFETY_EVENT || operation == ObservationOperationEnum.SAFETY_EVENT_CAMERAS)
                && args.get("eventId") instanceof String eventId && !eventId.isBlank()) {
            normalized.put("id", eventId);
        }
        if (operation == ObservationOperationEnum.SAFETY_EVENTS && args.get("types") instanceof List<?> types
                && types.size() == 1 && types.get(0) instanceof String type && !type.isBlank()) {
            normalized.put("type", type);
        }
        if (operation == ObservationOperationEnum.COMPANY_METER_REPORT) {
            normalizeMeterScope(normalized, "scope", false);
            normalizeMeterScope(normalized, "other", true);
            if (args.get("dailyWindow") instanceof Map<?, ?> window) {
                normalized.put("windowFrom", window.get("from"));
                normalized.put("windowTo", window.get("to"));
            }
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

    private Map<String, Object> deviceDetails(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, Map<String, Object> args) {
        String setId = text(args, "candidateSetId", "");
        var stored = iot.resolveDeviceCandidateSet(identity(principal, source), setId, conversationId).block(TIMEOUT);
        if (stored == null) throw new BusinessException("CANDIDATE_SET_NOT_FOUND", "设备候选集不存在或已过期", 404);
        // Runtime deviceDetailsTool: d{N}_{k} refs resolve inside the referenced set first.
        List<String> ids = strings(args.get("deviceIds")).stream()
                .map(stored::resolveDeviceRef).toList();
        Set<String> members = new LinkedHashSet<>();
        for (AuthorizedDevice device : stored.members()) members.add(device.id());
        if (ids.isEmpty() || !members.containsAll(ids)) {
            throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备不属于所引用的候选集", 400);
        }
        Map<String, Object> query = Map.of("kind", stored.query().kind(), "deviceIds", ids);
        return findCandidates(principal, source, conversationId, null, query);
    }

    private Map<String, Object> findCandidates(PrincipalInfo principal, IotSourceEnum source, String conversationId,
            String focusRegionId, Map<String, Object> args) {
        return findCandidates(principal, source, conversationId, focusRegionId, args, null);
    }

    private Map<String, Object> findCandidates(PrincipalInfo principal, IotSourceEnum source, String conversationId,
            String focusRegionId, Map<String, Object> args, String toolContextId) {
        String scopeText = nullableText(args, "scopeText");
        List<String> deviceIds = strings(args.get("deviceIds"));
        List<String> scopeTerms = strings(args.get("scopeTerms"));
        String regionId = null;
        if (source == IotSourceEnum.OFFICE && deviceIds.isEmpty() && !isBlank(focusRegionId)) {
            // The bound focus region is the default scope, not a ceiling: generic or absent
            // scope binds it; a canonical region name resolves through verified Office region
            // metadata (the focus region or another authorized region) instead of fuzzy
            // matching installAddress; any other real text stays a subset filter inside the
            // focus region. Explicit device selections are never clipped by the focus region.
            List<OfficeRegion> regions = List.of();
            if (!currentScope(scopeText) || !scopeTerms.isEmpty()) {
                regions = iot.loadOfficeRegions(identity(principal, source)).block(TIMEOUT);
                if (regions == null) throw new BusinessException("REGION_UNAVAILABLE", "办公室授权区域暂不可用", 503);
            }
            if (currentScope(scopeText)) {
                regionId = focusRegionId;
                scopeText = null;
            } else {
                OfficeRegion named = namedAuthorizedRegion(regions, focusRegionId, scopeText);
                if (named != null) {
                    regionId = named.id();
                    scopeText = null;
                } else {
                    regionId = focusRegionId;
                }
            }
            String selectedRegion = regionId;
            OfficeRegion bound = regions.stream().filter(region -> selectedRegion.equals(region.id())).findFirst().orElse(null);
            if (bound != null && !scopeTerms.isEmpty()) {
                String boundName = normalizedRegionName(bound.name());
                scopeTerms = scopeTerms.stream().filter(term -> !boundName.equals(normalizedRegionName(term))).toList();
            }
        }
        DeviceCandidateQuery query = new DeviceCandidateQuery(text(args, "kind", "unknown"), deviceIds,
                scopeText, scopeTerms, strings(args.get("nameHints")), nullableText(args, "deviceText"),
                regionId);
        DeviceCandidateSet candidates = iot.findDeviceCandidates(identity(principal, source), query,
                new DeviceCandidateBinding(conversationId)).block(TIMEOUT);
        if (candidates == null) throw new BusinessException("IOT_SCOPE_UNAVAILABLE", "设备候选范围暂不可用", 503);
        List<AuthorizedDevice> members = candidates.allDevices() != null ? candidates.allDevices()
                : candidates.complete() ? candidates.devices() : List.of();
        String membership = members.size() == candidates.totalMatches()
                ? DeviceCandidateRecord.scopeFingerprint(members) : null;
        if (toolContextId != null && candidates.totalMatches() > 0) {
            // Runtime findResultCounts: the same member list returned again is folded into
            // a short duplicate note; the third identical hit stops further expansion.
            String signature = candidates.query().kind() + ":" + candidates.resolution().mode() + ":"
                    + candidates.totalMatches() + ":" + (membership == null
                    ? String.join("|", candidates.devices().stream().map(AuthorizedDevice::id).toList()) : membership);
            int attempt = taskState.recordFindResult(toolContextId, signature);
            if (attempt > 1) {
                Map<String, Object> duplicate = new LinkedHashMap<>();
                duplicate.put("status", "duplicate_result");
                duplicate.put("totalMatches", candidates.totalMatches());
                duplicate.put("note", "相同范围的检索已第 " + attempt
                        + " 次返回完全相同的候选，不再重复返回明细。请基于已有候选集继续；已明确目标可用 get_device_details 补读已有引用；"
                        + "仍有歧义时再按更小的真实范围调查，不要放宽范围。");
                duplicate.put("duplicateAttempt", attempt);
                return duplicate;
            }
        }
        // Runtime projectCandidateSetForModel: the model sees handles only; real ids stay server-side.
        boolean wide = candidates.totalMatches() > candidates.devices().size();
        List<AuthorizedDevice> shown = wide
                ? candidates.devices().subList(0, Math.min(10, candidates.devices().size())) : candidates.devices();
        Set<String> shownIds = shown.stream().map(AuthorizedDevice::id).collect(java.util.stream.Collectors.toSet());
        Map<String, String> handleOf = deviceHandleIndex(candidates);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("handle", candidates.handle());
        Map<String, Object> queryView = new LinkedHashMap<>(mapper.convertValue(candidates.query(), Map.class));
        if (queryView.get("deviceIds") instanceof List<?> ids) {
            queryView.put("deviceIds", ids.stream().map(id -> handleOf.getOrDefault(String.valueOf(id), String.valueOf(id))).toList());
        }
        result.put("query", queryView);
        result.put("devices", publicDevices(shown, handleOf));
        result.put("complete", candidates.complete());
        result.put("totalMatches", candidates.totalMatches());
        var groups = candidates.groups();
        result.put("groups", wide ? Map.of(
                "complete", groups.complete() && groups.locations().size() <= 40 && groups.baseNames().size() <= 40,
                "locations", groups.locations().subList(0, Math.min(40, groups.locations().size())),
                "baseNames", groups.baseNames().subList(0, Math.min(40, groups.baseNames().size())))
                : mapper.convertValue(groups, Map.class));
        if (candidates.nameHintMatches() != null && !candidates.nameHintMatches().isEmpty()) {
            List<Map<String, Object>> projected = new ArrayList<>();
            for (DeviceCandidateSet.NameHintMatch match : candidates.nameHintMatches()) {
                if (!shownIds.contains(match.deviceId())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("deviceHandle", handleOf.get(match.deviceId()));
                row.put("matchedNameHints", match.matchedNameHints());
                projected.add(row);
            }
            result.put("nameHintMatches", projected);
        }
        if (candidates.unmatchedNameHints() != null && !candidates.unmatchedNameHints().isEmpty()) {
            result.put("unmatchedNameHints", candidates.unmatchedNameHints());
        }
        result.put("resolution", mapper.convertValue(candidates.resolution(), Map.class));
        result.put("membersComplete", members.size() == candidates.totalMatches());
        if (wide) {
            // Mirrors iot.internal.DeviceCandidateMatcher.CANDIDATE_PRESENTATION_LIMIT.
            final int presentationLimit = 50;
            result.put("note", "匹配 " + candidates.totalMatches() + " 台，超出单次明细上限 "
                    + presentationLimit
                    + " 台，当前展示前 " + shown.size()
                    + " 台；完整照明范围可用 propose_lighting_draft 引用集合，混合即时目标用 propose_action_draft.lighting 合并其他设备actions，"
                    + "未来安排用 propose_task 检查点的 lighting 引用集合，不需抄写设备；已明确的单台目标可用 get_device_details 补读已有引用形成精确候选集。"
                    + "未展示明细不表示设备不存在，分组不完整时不得宣称完整覆盖。");
        }
        if (toolContextId != null) {
            taskState.rememberCandidateSet(toolContextId, closingSetLine(candidates));
            attachClosingContext(toolContextId, result, null);
        }
        Map<String, Object> full = new LinkedHashMap<>(result);
        full.put("candidateSetId", candidates.candidateSetId());
        if (membership != null) full.put("candidateMembersFingerprint", membership);
        full.put("devices", candidates.devices().stream().map(AgentBusinessToolDispatcher::publicDevice).toList());
        full.put("modelResult", result);
        return full;
    }


    private static String closingSetLine(DeviceCandidateSet candidates) {
        String scope = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(candidates.query().scopeText()),
                        candidates.query().scopeTerms().stream())
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + "/" + right)
                .orElse("未限定位置");
        String names = candidates.devices().stream().limit(15).map(AuthorizedDevice::name)
                .collect(java.util.stream.Collectors.joining("、"));
        String handle = candidates.handle() != null ? candidates.handle() : candidates.candidateSetId();
        return handle + "（" + candidates.query().kind() + " · " + scope + "）：" + names
                + (candidates.devices().size() > 15 ? " 等" : "");
    }

    /**
     * Runtime prepareNextTurnWithContext: the closing turn keeps this run's candidate
     * facts and pending-proposal state as a compact reference so short anaphora still
     * resolve. The transport moves the value into the engine envelope.
     */
    private void attachClosingContext(String toolContextId, Map<String, Object> result, String proposalFact) {
        String knownSets = String.join("\n", taskState.candidateSetLines(toolContextId));
        String context = null;
        if (!knownSets.isBlank()) {
            context = "本轮检索已获得的候选集仍然有效，可直接据此回答或列出选项让用户选择：\n" + knownSets
                    + "\n用户对此前列出选项的简短指代（如“后者”“第二个”“第一组”）应对照上述清单或你上一条回复中列出的选项解析，不要再要求用户重新描述位置。";
        }
        if (proposalFact != null) {
            context = (context == null ? "" : context + "\n") + proposalFact;
        }
        if (context != null && context.length() <= 16_000) result.put("closingContext", context);
    }

    private static Map<String, String> deviceHandleIndex(DeviceCandidateSet candidates) {
        Map<String, String> index = new LinkedHashMap<>();
        List<String> handles = candidates.deviceHandles();
        for (int position = 0; position < candidates.devices().size() && position < handles.size(); position++) {
            index.put(candidates.devices().get(position).id(), handles.get(position));
        }
        return index;
    }

    private static List<Map<String, Object>> publicDevices(List<AuthorizedDevice> shown, Map<String, String> handleOf) {
        List<Map<String, Object>> devices = new ArrayList<>(shown.size());
        for (AuthorizedDevice device : shown) {
            Map<String, Object> view = new LinkedHashMap<>();
            if (handleOf.containsKey(device.id())) {
                view.put("handle", handleOf.get(device.id()));
            } else {
                view.put("id", device.id());
            }
            view.put("kind", device.kind());
            view.put("name", device.name());
            view.put("installAddress", device.installAddress());
            view.put("locationPaths", device.locationPaths());
            devices.add(view);
        }
        return devices;
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

    /**
     * Resolves a canonical region name through verified Office region metadata instead of
     * matching installAddress. Only whitespace-normalized equality with an authorized
     * region name binds; no synonym aliases are invented. The focus region wins a
     * name tie; otherwise duplicate authorized names remain unresolved.
     */
    private static OfficeRegion namedAuthorizedRegion(List<OfficeRegion> regions, String focusRegionId, String scopeText) {
        OfficeRegion focus = null;
        for (OfficeRegion region : regions) {
            if (focusRegionId != null && focusRegionId.equals(region.id())) focus = region;
        }
        String wanted = normalizedRegionName(scopeText);
        if (focus != null && normalizedRegionName(focus.name()).equals(wanted)) return focus;
        OfficeRegion match = null;
        for (OfficeRegion region : regions) {
            if (!normalizedRegionName(region.name()).equals(wanted)) continue;
            if (match != null && !match.id().equals(region.id())) return null;
            match = region;
        }
        return match;
    }

    private static String normalizedRegionName(String value) {
        return REGION_NAME_SPACING.matcher(java.text.Normalizer.normalize(
                value == null ? "" : value, java.text.Normalizer.Form.NFKC)).replaceAll("");
    }

    private Map<String, Object> readStates(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, Map<String, Object> args) {
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections =
                capabilitySelections(principal, source, conversationId, args);
        DeviceStateReadResult result = iot.readDeviceStates(identity(principal, source),
                new DeviceCapabilityRequest(selections), conversationId).block(TIMEOUT);
        if (result == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "设备状态暂不可用", 404);
        return mapper.convertValue(result, Map.class);
    }

    private Map<String, Object> inspectCapabilities(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, Map<String, Object> args) {
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections =
                capabilitySelections(principal, source, conversationId, args);
        DeviceCapabilityInspection result = iot.inspectDeviceCapabilities(identity(principal, source),
                new DeviceCapabilityRequest(selections), conversationId).block(TIMEOUT);
        if (result == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "设备能力暂不可用", 404);
        return mapper.convertValue(result, Map.class);
    }

    private List<DeviceCapabilityRequest.DeviceCapabilitySelection> capabilitySelections(
            PrincipalInfo principal, IotSourceEnum source, String conversationId, Map<String, Object> args) {
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections = new ArrayList<>();
        for (Object value : list(args.get("candidateSets"))) {
            Map<String, Object> item = map(value);
            if (Boolean.TRUE.equals(item.get("all")) && item.containsKey("deviceIds")) {
                throw new BusinessException("INVALID_DEVICE_REFERENCE", "完整候选范围与精确设备选择不能混用");
            }
            String setId = text(item, "candidateSetId", "");
            var stored = iot.resolveDeviceCandidateSet(identity(principal, source), setId, conversationId).block(TIMEOUT);
            if (stored == null) throw new BusinessException("CANDIDATE_SET_NOT_FOUND", "设备候选集不存在或已过期", 404);
            // Runtime state/inspect tools: device refs resolve inside their own candidate set.
            setId = stored.candidateSetId();
            List<String> ids = strings(item.get("deviceIds")).stream().map(stored::resolveDeviceRef).toList();
            if (Boolean.TRUE.equals(item.get("all"))) {
                if (stored.members().size() != stored.totalMatches()) throw new BusinessException(
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

    private Map<String, Object> proposeLighting(PrincipalInfo principal, IotSourceEnum source,
            String conversationId, String turnId, String focusRegionId, Map<String, Object> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lighting", businessArguments(args));
        payload.put("candidateSetIds", List.of());
        payload.put("actions", List.of());
        payload.put("summary", "照明操作草案");
        payload.put("idempotencyKey", args.get("idempotencyKey"));
        return proposeAction(principal, source, conversationId, turnId, focusRegionId, payload);
    }

    private Map<String, Object> proposeAction(PrincipalInfo principal, IotSourceEnum source,
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
                    // 保留原始数值：非整数/溢出交给 ActionDraftFacade/TaskProposalNormalizer 的
                    // 统一规则拒绝，不在校验前截断成看似合法的值。
                    action.put("command", Map.of("closeOrOpen", power));
                } else if ("access".equals(kind) && action.get("controlType") instanceof Number controlType) {
                    action.put("command", Map.of("controlType", controlType));
                } else if (ac instanceof Map<?, ?>) {
                    action.put("kind", "ac");
                    action.put("command", map(ac));
                } else if (action.get("closeOrOpen") instanceof Number power) {
                    action.put("kind", "lighting");
                    action.put("command", Map.of("closeOrOpen", power));
                } else if (action.get("controlType") instanceof Number controlType) {
                    action.put("kind", "access");
                    action.put("command", Map.of("controlType", controlType));
                }
            }
            normalized.add(action);
        }
        return normalized;
    }

    private Map<String, Object> proposeTask(String toolContextId, PrincipalInfo principal, IotSourceEnum source,
            String conversationId, String turnId, Map<String, Object> args) {
        if (taskState.taskProposalCount(toolContextId) >= 2) {
            throw new BusinessException("TASK_LIMIT", "本次最多提出两项独立任务", 400);
        }
        Map<String, Object> payload = businessArguments(args);
        // normalizeArguments replaced any model context with the server binding.
        // Keep that binding for Office member resolution after stripping model fields.
        if (source == IotSourceEnum.OFFICE) {
            payload.put("focusRegionId", text(args, "focusRegionId", ""));
        }
        boolean companyTargets = false;
        if (source == IotSourceEnum.COMPANY) {
            boolean anyTargets = list(args.get("phases")).stream()
                    .map(AgentBusinessToolDispatcher::map)
                    .anyMatch(phase -> !emptyArgument(phase.get("targets")));
            if (anyTargets) {
                // Runtime candidateProposeTaskTool targets path: one confirmed request
                // finalizes the run; scope/expansion happens in the task module.
                companyTargets = true;
            } else if (!args.containsKey("candidateSetIds")) {
                boolean controlPhases = list(args.get("phases")).stream()
                        .map(AgentBusinessToolDispatcher::map)
                        .anyMatch(phase -> !emptyArgument(phase.get("actions")) || phase.containsKey("lighting"));
                if (controlPhases) {
                    throw new BusinessException("INVALID_TASK_SCOPE", "候选集任务需要提供已发现的 candidateSetIds", 400);
                }
                // Runtime legacy path: the sN sources are recovered from the dN_M refs
                // the model cites, without a fresh search.
                restoreReadOnlyCandidateSets(payload, principal, source, conversationId);
            }
        }
        try {
            Map<String, Object> view = tasks.proposalView(tasks.propose(principal, source.wireValue(),
                    conversationId, turnId, mapper.writeValueAsString(payload), text(args, "idempotencyKey", "")));
            taskState.recordTaskProposal(toolContextId);
            if (companyTargets || taskState.taskProposalCount(toolContextId) >= 2) {
                view = new LinkedHashMap<>(view);
                view.put("haltRun", true);
            }
            return view;
        } catch (ltd.qianc.iotagent.task.TaskAlreadyScheduledException duplicate) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("status", "already_scheduled");
            receipt.put("planId", duplicate.planId());
            receipt.put("label", duplicate.label());
            if (duplicate.nextAt() != null) receipt.put("nextAt", duplicate.nextAt());
            receipt.put("note", duplicate.getMessage());
            return receipt;
        } catch (BusinessException error) {
            Map<String, Object> clarification = acScheduleClarification(error, args);
            if (clarification != null) return clarification;
            throw error;
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效");
        }
    }

    /**
     * Runtime candidateProposeTaskTool legacy path: for a company read-only task the
     * candidateSetIds are the sN sets behind the cited dN_M handles, and the handles
     * resolve into real ids before server-side member validation.
     */
    private void restoreReadOnlyCandidateSets(Map<String, Object> payload, PrincipalInfo principal,
            IotSourceEnum source, String conversationId) {
        Set<String> setRefs = new LinkedHashSet<>();
        List<Map<String, Object>> phases = new ArrayList<>();
        for (Object item : list(payload.get("phases"))) {
            Map<String, Object> phase = new LinkedHashMap<>(map(item));
            if (phase.get("read") instanceof Map<?, ?> rawRead) {
                Map<String, Object> read = new LinkedHashMap<>(map(rawRead));
                if (read.get("deviceIds") instanceof List<?> ids) {
                    List<String> resolved = new ArrayList<>();
                    for (Object id : ids) {
                        String ref = String.valueOf(id);
                        java.util.regex.Matcher handle = DEVICE_HANDLE.matcher(ref);
                        if (handle.matches()) {
                            setRefs.add("s" + handle.group(1));
                            var stored = iot.resolveDeviceCandidateSet(identity(principal, source),
                                    "s" + handle.group(1), conversationId).block(TIMEOUT);
                            ref = stored == null ? ref : stored.resolveDeviceRef(ref);
                        }
                        resolved.add(ref);
                    }
                    read.put("deviceIds", resolved);
                }
                phase.put("read", read);
            }
            phases.add(phase);
        }
        if (!setRefs.isEmpty()) {
            List<String> candidateSetIds = new ArrayList<>();
            for (String ref : setRefs) {
                var stored = iot.resolveDeviceCandidateSet(identity(principal, source), ref, conversationId).block(TIMEOUT);
                if (stored != null) candidateSetIds.add(stored.candidateSetId());
            }
            payload.put("candidateSetIds", candidateSetIds);
        }
        payload.put("phases", phases);
    }

    /**
     * Runtime missingAcCadenceClarification: a window without a cadence stays the
     * user's decision — the tool returns needs_clarification facts instead of an
     * error, and no task proposal is registered.
     */
    private Map<String, Object> acScheduleClarification(BusinessException error, Map<String, Object> args) {
        boolean cadenceMissingInWindow = "AC_CADENCE_REQUIRED".equals(error.code());
        boolean windowRequired = "AC_WINDOW_REQUIRED".equals(error.code());
        if (!cadenceMissingInWindow && !windowRequired) return null;
        Map<String, Object> firstRead = phasesFirstRead(args);
        Map<String, Object> schedule = new LinkedHashMap<>();
        if (cadenceMissingInWindow) {
            String startsAt = null;
            String endsAt = null;
            if (args.get("interval") instanceof Map<?, ?> interval) {
                Map<String, Object> window = map(interval);
                startsAt = text(window, "startsAt", "");
                endsAt = text(window, "endsAt", "");
            }
            try {
                Instant start = OffsetDateTime.parse(startsAt).toInstant();
                Instant end = OffsetDateTime.parse(endsAt).toInstant();
                Duration duration = Duration.between(start, end);
                int suggested = duration.toMinutes() >= 30 ? 30 : 15;
                schedule.put("kind", "window");
                schedule.put("interval", Map.of("startsAt", start.toString(), "endsAt", end.toString()));
                schedule.put("suggestedCadenceMinutes", suggested);
                schedule.put("allowedCadenceMinutes", List.of(15, 30, 60));
                schedule.put("checkpointCount", (int) (duration.toMinutes() / suggested));
            } catch (RuntimeException invalid) {
                schedule.put("kind", "window_required");
            }
        } else {
            schedule.put("kind", "window_required");
            if (firstRead != null && firstRead.get("ac") instanceof Map<?, ?> ac) {
                Object cadence = map(ac).get("cadenceMinutes");
                if (cadence instanceof Number numeric) schedule.put("cadenceMinutes", numeric.intValue());
            }
            for (Object item : list(args.get("phases"))) {
                Map<String, Object> phase = map(item);
                if (phase.get("at") instanceof String at && !at.isBlank()) {
                    schedule.put("anchorAt", at);
                    break;
                }
            }
        }
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("status", "needs_clarification");
        clarification.put("schedule", schedule);
        return clarification;
    }

    private static Map<String, Object> phasesFirstRead(Map<String, Object> args) {
        for (Object item : list(args.get("phases"))) {
            Map<String, Object> phase = map(item);
            if (phase.get("read") instanceof Map<?, ?> read) return map(read);
        }
        return null;
    }

    private static RequestIdentity identity(PrincipalInfo principal, IotSourceEnum source) {
        return new RequestIdentity(principal.authorization(), principal.tenantCode(), source, principal.ownerKey());
    }

    private static IotSourceEnum parseSource(String source) {
        try {
            return IotSourceEnum.parse(source);
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

    /**
     * Run-scoped task facts from the Runtime runner loop: which task ids the model
     * has actually listed/read (cancellation gate) and how many independent task
     * proposals this run produced. Entries expire with the run like AgentRunGuard.
     */
    private static final class RunTaskState {
        private static final long RETENTION_NANOS = Duration.ofMinutes(15).toNanos();
        private final Map<String, Entry> runs = new java.util.HashMap<>();

        synchronized Set<String> listedTaskIds(String toolContextId) {
            return entry(toolContextId).listedTaskIds;
        }

        synchronized void listTask(String toolContextId, String taskId) {
            entry(toolContextId).listedTaskIds.add(taskId);
        }

        synchronized int taskProposalCount(String toolContextId) {
            return entry(toolContextId).taskProposalCount;
        }

        synchronized void recordTaskProposal(String toolContextId) {
            entry(toolContextId).taskProposalCount += 1;
        }

        synchronized int recordFindResult(String toolContextId, String signature) {
            return entry(toolContextId).findSignatures.merge(signature, 1, Integer::sum);
        }

        synchronized void rememberCandidateSet(String toolContextId, String line) {
            Entry entry = entry(toolContextId);
            entry.candidateLines.removeIf(existing -> existing.equals(line));
            entry.candidateLines.add(line);
            while (entry.candidateLines.size() > 4) entry.candidateLines.remove(0);
        }

        synchronized List<String> candidateSetLines(String toolContextId) {
            return List.copyOf(entry(toolContextId).candidateLines);
        }

        private Entry entry(String toolContextId) {
            long now = System.nanoTime();
            runs.values().removeIf(entry -> now - entry.atNanos > RETENTION_NANOS);
            Entry entry = runs.computeIfAbsent(toolContextId, ignored -> new Entry(now));
            entry.atNanos = now;
            return entry;
        }

        private static final class Entry {
            private long atNanos;
            private final Set<String> listedTaskIds = new LinkedHashSet<>();
            private int taskProposalCount;
            private final Map<String, Integer> findSignatures = new java.util.HashMap<>();
            private final List<String> candidateLines = new ArrayList<>();

            private Entry(long atNanos) {
                this.atNanos = atNanos;
            }
        }
    }
}
