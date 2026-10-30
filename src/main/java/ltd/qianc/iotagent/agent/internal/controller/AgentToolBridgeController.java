package ltd.qianc.iotagent.agent.internal.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperation;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatus;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated internal bridge from the Node Pi sidecar back into Java tools. */
@RestController
@RequestMapping("/internal/agent/tools")
public final class AgentToolBridgeController {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final ConversationFacade conversations;
    private final IotAdapter iot;
    private final ObservationAdapter observations;
    private final TaskProjectionStore projections;
    private final ActionDraftFacade actions;
    private final TaskFacade tasks;
    private final ObjectMapper mapper;
    private final ReportGenerationPort reports;

    public AgentToolBridgeController(ConversationFacade conversations, IotAdapter iot,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper) {
        this(conversations, iot, null, null, actions, tasks, mapper, null);
    }

    public AgentToolBridgeController(ConversationFacade conversations, IotAdapter iot,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper,
            ObjectProvider<ReportGenerationPort> reportProvider) {
        this(conversations, iot, null, null, actions, tasks, mapper, reportProvider.getIfAvailable());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentToolBridgeController(ConversationFacade conversations, IotAdapter iot,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper,
            ObjectProvider<ObservationAdapter> observationProvider,
            ObjectProvider<TaskProjectionStore> projectionProvider,
            ObjectProvider<ReportGenerationPort> reportProvider) {
        this(conversations, iot, observationProvider.getIfAvailable(), projectionProvider.getIfAvailable(),
                actions, tasks, mapper, reportProvider.getIfAvailable());
    }

    public AgentToolBridgeController(ConversationFacade conversations, IotAdapter iot,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper,
            ReportGenerationPort reports) {
        this(conversations, iot, null, null, actions, tasks, mapper, reports);
    }

    public AgentToolBridgeController(ConversationFacade conversations, IotAdapter iot,
            ObservationAdapter observations, TaskProjectionStore projections,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper,
            ReportGenerationPort reports) {
        this.conversations = conversations;
        this.iot = iot;
        this.observations = observations;
        this.projections = projections;
        this.actions = actions;
        this.tasks = tasks;
        this.mapper = mapper;
        this.reports = reports;
    }

    @PostMapping
    public Map<String, Object> execute(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) throw new BusinessException("INVALID_AGENT_TOOL", "工具请求不能为空");
        String toolContextId = text(body, "toolContextId", "");
        String conversationId = text(body, "conversationId", "");
        String turnId = text(body, "turnId", "");
        String toolName = text(body, "toolName", "");
        PrincipalInfo principal = conversations.toolPrincipal(toolContextId, conversationId, turnId);
        Map<String, Object> arguments = map(body.get("arguments"));
        return switch (toolName) {
            case "find_device_candidates" -> findCandidates(principal, source(arguments), arguments);
            case "get_device_details" -> findCandidates(principal, source(arguments), detailQuery(arguments));
            case "read_device_states", "read_device_readback", "readback_device_states" -> readStates(principal, source(arguments), arguments);
            case "inspect_device_capabilities" -> inspectCapabilities(principal, source(arguments), arguments);
            case "query_alerts" -> queryAlerts(principal, source(arguments), arguments);
            case "query_environment_history" -> observation(principal, source(arguments), ObservationOperation.ENVIRONMENT_HISTORY, arguments);
            case "query_energy_history" -> observation(principal, source(arguments), ObservationOperation.ENERGY_HISTORY, arguments);
            case "summarize_energy" -> observation(principal, source(arguments), ObservationOperation.ENERGY_REPORT, arguments);
            case "compare_energy" -> observation(principal, source(arguments), ObservationOperation.ENERGY_COMPARISON, arguments);
            case "review_safety_event" -> observation(principal, source(arguments),
                    arguments.containsKey("cameraDeviceId") ? ObservationOperation.SAFETY_EVENT_CAMERAS : ObservationOperation.SAFETY_EVENT,
                    arguments);
            case "propose_action_draft", "propose_lighting_draft" -> proposeAction(principal, conversationId, turnId, arguments);
            case "read_action_draft" -> readAction(principal, arguments);
            case "read_action_device_states" -> actions.deviceStates(principal, identifier(arguments));
            case "read_action_summary" -> actions.summary(principal, identifier(arguments));
            case "confirm_action" -> resolveAction(principal, arguments, true);
            case "resolve_action" -> resolveAction(principal, arguments, false);
            case "propose_task" -> proposeTask(principal, conversationId, turnId, arguments);
            case "propose_task_change" -> proposeTaskChange(principal, conversationId, turnId, arguments);
            case "propose_task_cancellation" -> proposeTaskCancellation(principal, conversationId, turnId, arguments);
            case "list_tasks" -> listTasks(principal, arguments);
            case "list_task_plans" -> listTaskPlans(principal, arguments);
            case "get_task" -> getTask(principal, arguments);
            case "confirm_task" -> confirmTask(principal, arguments);
            case "confirm_task_change" -> confirmTaskChange(principal, arguments);
            case "confirm_task_cancellation" -> confirmTaskCancellation(principal, arguments);
            case "create_report", "create_energy_report", "generate_report" -> generateReport(principal, conversationId, turnId, arguments);
            default -> throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "工具未接入", 404);
        };
    }

    private Map<String, Object> generateReport(PrincipalInfo principal, String conversationId,
            String turnId, Map<String, Object> args) {
        if (reports == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "报告工具未接入", 404);
        IotSource source = source(args);
        if (source != IotSource.COMPANY) throw new BusinessException("REPORT_SOURCE_UNSUPPORTED", "报告只支持 Company 来源");
        Object value = args.get("report");
        Map<String, Object> content = value instanceof Map<?, ?> ? map(value) : args;
        ReportGenerationPort.GeneratedReport result = reports.generate(
                new RequestIdentity(principal.authorization(), principal.tenantCode(), IotSource.COMPANY, principal.ownerKey()),
                conversationId, turnId, text(args, "idempotencyKey", turnId), content);
        return Map.of("reportId", result.reportId(), "reused", result.reused(), "report", result.report());
    }
    private Map<String, Object> queryAlerts(PrincipalInfo principal, IotSource source,
            Map<String, Object> args) {
        List<String> types = strings(args.get("types"));
        if (types.size() <= 1) {
            Map<String, Object> normalized = new LinkedHashMap<>(args);
            if (types.size() == 1) normalized.put("type", types.get(0));
            return observation(principal, source, ObservationOperation.SAFETY_EVENTS, normalized);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        List<Object> items = new ArrayList<>();
        int total = 0;
        int scanned = 0;
        int excluded = 0;
        boolean complete = true;
        for (String type : types) {
            Map<String, Object> normalized = new LinkedHashMap<>(args);
            normalized.put("type", type);
            Map<String, Object> part = observation(principal, source, ObservationOperation.SAFETY_EVENTS, normalized);
            if (merged.isEmpty()) merged.putAll(part);
            appendValues(items, part.get("items"));
            appendValues(items, part.get("records"));
            total += number(part.get("total"));
            scanned += number(part.get("scanned"));
            excluded += number(part.get("excludedCount"));
            complete &= !Boolean.FALSE.equals(part.get("coverageComplete"));
        }
        if (!items.isEmpty()) {
            merged.put("items", items);
            merged.remove("records");
        }
        merged.put("total", total);
        merged.put("scanned", scanned);
        merged.put("excludedCount", excluded);
        merged.put("coverageComplete", complete);
        return merged;
    }

    private static void appendValues(List<Object> target, Object value) {
        if (value instanceof List<?> values) target.addAll(values);
    }

    private static int number(Object value) {
        return value instanceof Number numeric ? numeric.intValue() : 0;
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

    private Map<String, Object> readAction(PrincipalInfo principal, Map<String, Object> args) {
        return actions.view(actions.current(principal, identifier(args)));
    }

    private Map<String, Object> resolveAction(PrincipalInfo principal, Map<String, Object> args, boolean confirm) {
        var draft = confirm
                ? actions.confirm(principal, identifier(args), versionOrNull(args))
                : actions.cancel(principal, identifier(args));
        return actions.view(draft);
    }

    private Map<String, Object> proposeTaskChange(PrincipalInfo principal, String conversationId, String turnId,
            Map<String, Object> args) {
        return proposalView(tasks.proposeChange(principal, text(args, "source", "office"), conversationId, turnId,
                payloadJson(args), text(args, "idempotencyKey", "")));
    }

    private Map<String, Object> proposeTaskCancellation(PrincipalInfo principal, String conversationId, String turnId,
            Map<String, Object> args) {
        return proposalView(tasks.proposeCancellation(principal, text(args, "source", "office"), conversationId, turnId,
                payloadJson(args), text(args, "idempotencyKey", "")));
    }

    private Map<String, Object> proposalView(ltd.qianc.iotagent.task.TaskProposalFact proposal) {
        return tasks.proposalView(proposal);
    }

    private Map<String, Object> listTasks(PrincipalInfo principal, Map<String, Object> args) {
        if (projections == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "任务列表工具未接入", 404);
        try {
            Instant now = Instant.now();
            Instant from = instant(args.get("from"), now.minus(Duration.ofDays(365)));
            Instant to = instant(args.get("to"), now.plus(Duration.ofDays(365)));
            return Map.of("tasks", projections.list(principal, text(args, "source", "office"), from, to));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "任务列表工具不可用", 404);
        }
    }

    private Map<String, Object> listTaskPlans(PrincipalInfo principal, Map<String, Object> args) {
        try {
            return Map.of("plans", tasks.plans(principal, text(args, "source", "office")));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "任务计划工具不可用", 404);
        }
    }

    private Map<String, Object> getTask(PrincipalInfo principal, Map<String, Object> args) {
        return tasks.view(tasks.current(principal, identifier(args), source(args).wireValue()));
    }

    private Map<String, Object> confirmTask(PrincipalInfo principal, Map<String, Object> args) {
        return tasks.view(tasks.confirm(principal, identifier(args), version(args), source(args).wireValue()));
    }
    private Map<String, Object> confirmTaskChange(PrincipalInfo principal, Map<String, Object> args) {
        return tasks.confirmChange(principal, identifier(args), version(args), source(args).wireValue());
    }

    private Map<String, Object> confirmTaskCancellation(PrincipalInfo principal, Map<String, Object> args) {
        return tasks.confirmCancellation(principal, identifier(args), version(args), source(args).wireValue());
    }

    private static Map<String, Object> detailQuery(Map<String, Object> args) {
        Map<String, Object> query = new LinkedHashMap<>(args);
        query.putIfAbsent("kind", "unknown");
        if (!query.containsKey("deviceIds") && query.get("ids") != null) query.put("deviceIds", query.get("ids"));
        return query;
    }

    private String payloadJson(Map<String, Object> args) {
        try {
            Object payload = args.get("payload");
            return mapper.writeValueAsString(payload instanceof Map<?, ?> ? map(payload) : args);
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效");
        }
    }

    private static Map<String, Object> normalizeObservationParameters(
            ObservationOperation operation, Map<String, Object> args) {
        Map<String, Object> normalized = new LinkedHashMap<>(args);
        if (operation == ObservationOperation.SAFETY_EVENT && args.get("eventId") instanceof String eventId
                && !eventId.isBlank()) {
            normalized.put("id", eventId);
        }
        if (operation == ObservationOperation.SAFETY_EVENTS && args.get("types") instanceof List<?> types
                && types.size() == 1 && types.get(0) instanceof String type && !type.isBlank()) {
            normalized.put("type", type);
        }
        return normalized;
    }

    private Map<String, String> parameterMap(Map<String, Object> args) {
        Map<String, String> result = new LinkedHashMap<>();
        args.forEach((key, value) -> {
            if ("source".equals(key) || value == null) return;
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
        if (value.isBlank()) value = text(args, "id", "");
        if (value.isBlank()) throw new BusinessException("INVALID_AGENT_TOOL", "工具标识不能为空");
        return value;
    }

    private static long version(Map<String, Object> args) {
        Object value = args.get("expectedVersion");
        if (value instanceof Number number) return number.longValue();
        value = args.get("version");
        if (value instanceof Number number) return number.longValue();
        return 1L;
    }

    private static Long versionOrNull(Map<String, Object> args) {
        Object value = args.get("expectedRevision");
        if (value instanceof Number number) return number.longValue();
        value = args.get("revision");
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Instant instant(Object value, Instant fallback) {
        if (!(value instanceof String text) || text.isBlank()) return fallback;
        try {
            return Instant.parse(text);
        } catch (RuntimeException error) {
            throw new BusinessException("INVALID_AGENT_TOOL", "时间范围无效");
        }
    }

    private Map<String, Object> findCandidates(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        DeviceCandidateQuery query = new DeviceCandidateQuery(text(args, "kind", "unknown"), strings(args.get("deviceIds")),
                nullableText(args, "scopeText"), strings(args.get("scopeTerms")), strings(args.get("nameHints")), nullableText(args, "deviceText"));
        return mapper.convertValue(iot.findDeviceCandidates(identity(principal, source), query).block(TIMEOUT), Map.class);
    }

    private Map<String, Object> readStates(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        List<String> deviceIds = deviceIds(args);
        RequestIdentity requestIdentity = identity(principal, source);
        Object result;
        if (source == IotSource.COMPANY) {
            var scope = iot.loadAuthorizedDeviceScope(requestIdentity).block(TIMEOUT);
            if (scope == null) throw new BusinessException("IOT_SCOPE_UNAVAILABLE", "设备授权范围暂不可用", 503);
            List<ltd.qianc.iotagent.iot.AuthorizedDevice> devices = deviceIds.stream()
                    .map(id -> scope.device(id).orElseThrow(() -> new BusinessException(
                            "INVALID_DEVICE_REFERENCE", "设备引用无效")))
                    .toList();
            result = iot.readObservationStates(requestIdentity, devices).block(TIMEOUT);
        } else {
            result = iot.readDeviceStates(requestIdentity, deviceIds).block(TIMEOUT);
        }
        return mapper.convertValue(result, Map.class);
    }

    private static List<String> deviceIds(Map<String, Object> args) {
        List<String> direct = strings(args.get("deviceIds"));
        if (!direct.isEmpty()) return direct;
        List<String> result = new ArrayList<>();
        for (Object value : list(args.get("candidateSets"))) {
            Map<String, Object> item = map(value);
            result.addAll(strings(item.get("deviceIds")));
        }
        if (result.isEmpty()) throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
        if (result.stream().distinct().count() != result.size()) {
            throw new BusinessException("INVALID_DEVICE_REFERENCE", "设备引用无效");
        }
        return result;
    }

    private Map<String, Object> inspectCapabilities(PrincipalInfo principal, IotSource source, Map<String, Object> args) {
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections = new ArrayList<>();
        for (Object value : list(args.get("candidateSets"))) {
            Map<String, Object> item = map(value);
            selections.add(new DeviceCapabilityRequest.DeviceCapabilitySelection(text(item, "candidateSetId", ""), strings(item.get("deviceIds"))));
        }
        return mapper.convertValue(iot.inspectDeviceCapabilities(identity(principal, source), new DeviceCapabilityRequest(selections)).block(TIMEOUT), Map.class);
    }

    private Map<String, Object> proposeAction(PrincipalInfo principal, String conversationId, String turnId, Map<String, Object> args) {
        try {
            Object rawPayload = args.get("payload");
            Map<String, Object> payload = rawPayload instanceof Map<?, ?> ? map(rawPayload) : new LinkedHashMap<>(args);
            payload.put("actions", normalizeActionEntries(payload.get("actions")));
            return actions.view(actions.propose(principal, text(args, "source", "office"), conversationId, turnId,
                    mapper.writeValueAsString(payload), text(args, "idempotencyKey", turnId)));
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "草案内容无效");
        }
    }

    private static List<Map<String, Object>> normalizeActionEntries(Object value) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list(value)) {
            Map<String, Object> action = new LinkedHashMap<>(map(item));
            if (!action.containsKey("kind") || !action.containsKey("command")) {
                Object ac = action.get("ac");
                if (ac instanceof Map<?, ?>) {
                    action.put("kind", "ac");
                    action.put("command", map(ac));
                } else if (action.get("closeOrOpen") instanceof Number power) {
                    action.put("kind", "lighting");
                    action.put("command", Map.of("power", power.intValue() == 1 ? "on" : "off"));
                } else if (action.get("controlType") instanceof Number controlType) {
                    action.put("kind", "access");
                    action.put("command", Map.of("controlType", controlType.intValue()));
                }
            }
            normalized.add(action);
        }
        return normalized;
    }

    private Map<String, Object> proposeTask(PrincipalInfo principal, String conversationId, String turnId, Map<String, Object> args) {
        try {
            Object rawPayload = args.get("payload");
            Map<String, Object> payload = rawPayload instanceof Map<?, ?> ? map(rawPayload) : args;
            return tasks.proposalView(tasks.propose(principal, text(args, "source", "office"), conversationId, turnId,
                    mapper.writeValueAsString(payload), text(args, "idempotencyKey", "")));
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_TASK_PAYLOAD", "任务内容无效");
        }
    }

    private static RequestIdentity identity(PrincipalInfo principal, IotSource source) {
        return new RequestIdentity(principal.authorization(), principal.tenantCode(), source, principal.ownerKey());
    }

    private static IotSource source(Map<String, Object> args) { return IotSource.parse(text(args, "source", "office")); }

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

    private static List<?> list(Object value) { return value instanceof List<?> values ? values : List.of(); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw new BusinessException("INVALID_AGENT_TOOL", "工具参数格式无效");
        return (Map<String, Object>) (Map<?, ?>) raw;
    }
}
