package ltd.qianc.iotagent.agent.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.AgentToolContext;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.ObservationOperationEnum;
import ltd.qianc.iotagent.iot.ObservationRequest;
import ltd.qianc.iotagent.iot.ObservationResponse;
import ltd.qianc.iotagent.iot.ObservationStatusEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportService;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskFacade;

/**
 * Resolves client-selected references against current Java-owned business facts.
 * Client titles/blocks/values are never trusted; only stable identities, task ids,
 * report ids and exact environment windows are accepted.
 */
final class AgentReferenceResolver {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final java.time.ZoneId SHANGHAI = java.time.ZoneId.of("Asia/Shanghai");
    private static final java.util.Set<String> COMPANY_ONLY = java.util.Set.of(
            "customer_alert", "report", "energy_report", "safety_event", "environment_window");

    record ReferenceResolution(Map<String, Object> reference, Map<String, Object> facts) {
        ReferenceResolution {
            reference = reference == null ? Map.of() : Map.copyOf(reference);
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }
    }

    private final ObservationAdapter observations;
    private final TaskFacade tasks;
    private final ReportService reports;
    private final IotAdapter iot;
    private final Clock clock;

    AgentReferenceResolver(ObservationAdapter observations, TaskFacade tasks, ReportService reports,
            IotAdapter iot, Clock clock) {
        this.observations = observations;
        this.tasks = tasks;
        this.reports = reports;
        this.iot = iot;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    List<Map<String, Object>> savedReportFacts(AgentToolContext context, List<String> reportIds) {
        if (reportIds.isEmpty()) return List.of();
        if (reports == null) throw unavailable("历史报告事实暂不可读取");
        return reports.conversationFacts(context.conversationId(), reportIds, identity(context, parseSource(context.source())));
    }

    ReferenceResolution resolve(AgentToolContext context) {
        Map<String, Object> raw = context.reference();
        if (raw == null || raw.isEmpty()) return new ReferenceResolution(Map.of(), Map.of());
        String type = text(raw, "type");
        if (type == null) throw invalid("引用身份无效");
        IotSourceEnum source = parseSource(context.source());
        if (COMPANY_ONLY.contains(type) && source != IotSourceEnum.COMPANY) {
            throw invalid("该引用仅用于公司 Agent");
        }
        return switch (type) {
            case "customer_alert" -> customerAlert(raw);
            case "task" -> task(context, source, raw);
            case "task_plan" -> taskPlan(context, source, raw);
            case "safety_event" -> safetyEvent(context, source, raw);
            case "environment_window" -> environmentWindow(context, source, raw);
            case "report" -> report(context, source, raw, type);
            case "device" -> device(context, source, raw);
            default -> throw invalid("引用类型不受支持");
        };
    }

    private ReferenceResolution customerAlert(Map<String, Object> raw) {
        String content = optionalText(raw, "content", 2000);
        if (content == null) throw invalid("客户告警引用内容无效");
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("type", "customer_alert");
        reference.put("content", content);
        reference.put("verified", false);
        return new ReferenceResolution(reference, Map.of("customerAlert", Map.of(
                "content", content, "verified", false)));
    }

    private ReferenceResolution task(AgentToolContext context, IotSourceEnum source, Map<String, Object> raw) {
        if (tasks == null) throw unavailable("任务事实暂不可读取");
        String taskId = required(raw, "taskId", 300, "任务引用身份无效");
        try {
            Map<String, Object> view = tasks.taskView(context.principal(), taskId, source.wireValue());
            String label = textOr(view, "label", "任务");
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("type", "task");
            reference.put("taskId", taskId);
            reference.put("label", label);
            reference.put("status", textOr(view, "status", "任务状态未知"));
            reference.put("verified", true);
            return new ReferenceResolution(reference, Map.of("task", view));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("任务事实暂不可读取");
        }
    }

    private ReferenceResolution taskPlan(AgentToolContext context, IotSourceEnum source, Map<String, Object> raw) {
        if (tasks == null) throw unavailable("周期计划事实暂不可读取");
        String planId = required(raw, "planId", 300, "周期计划引用身份无效");
        try {
            Map<String, Object> view = tasks.planView(context.principal(), planId, source.wireValue());
            String label = textOr(view, "label", "周期计划");
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("type", "task_plan");
            reference.put("planId", planId);
            reference.put("label", label);
            reference.put("status", textOr(view, "status", "任务状态未知"));
            reference.put("verified", true);
            return new ReferenceResolution(reference, Map.of("taskPlan", view));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("周期计划事实暂不可读取");
        }
    }


    private ReferenceResolution safetyEvent(AgentToolContext context, IotSourceEnum source, Map<String, Object> raw) {
        if (observations == null) throw unavailable("安全台账事实暂不可读取");
        String eventId = required(raw, "eventId", 300, "安全台账引用身份无效");
        String deviceId = required(raw, "deviceId", 300, "安全台账引用身份无效");
        String ledger = text(raw, "ledger");
        if (!List.of("gas", "smoke", "flooding").contains(ledger)) throw invalid("安全台账类型无效");
        ObservationResponse response;
        try {
            response = observations.read(new ObservationRequest(identity(context, source),
                    ObservationOperationEnum.SAFETY_EVENT,
                    Map.of("id", eventId, "deviceId", deviceId, "ledger", ledger))).block(TIMEOUT);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("安全台账事实暂不可读取");
        }
        if (response == null || response.status() == ObservationStatusEnum.UNAVAILABLE) {
            throw unavailable("安全台账事实暂不可读取");
        }
        Map<String, Object> record = map(response.payload().get("record"));
        if (record.isEmpty()) throw new BusinessException("SAFETY_EVENT_UNAVAILABLE", "安全台账记录不存在或没有查看权限", 404);
        String occurredAt = text(record, "occurredAt");
        Instant occurred;
        try {
            occurred = occurredAt == null ? null : Instant.parse(occurredAt);
        } catch (RuntimeException error) {
            throw unavailable("安全台账原事件时间不可解析");
        }
        if (occurred == null) throw unavailable("安全台账原事件时间不可解析");
        // Frozen Runtime semantics: the review window spans ±15 minutes around the
        // original event and never extends past the current time.
        Instant windowFrom = occurred.minus(Duration.ofMinutes(15));
        Instant windowTo = occurred.plus(Duration.ofMinutes(15));
        if (windowTo.isAfter(clock.instant())) windowTo = clock.instant();
        if (!windowTo.isAfter(windowFrom)) {
            throw unavailable("安全台账记录时间晚于当前时间，请核对记录时间");
        }
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", windowFrom.toString());
        window.put("to", windowTo.toString());
        window.put("timezone", "Asia/Shanghai");
        String name = textOr(record, "deviceName", textOr(record, "recordedDeviceName", "安全设备"));
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("type", "safety_event");
        reference.put("eventId", eventId);
        reference.put("deviceId", deviceId);
        reference.put("ledger", ledger);
        reference.put("label", name + " · " + occurredAt);
        reference.put("window", window);
        reference.put("verified", true);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("safetyEvent", record);
        facts.put("reviewWindow", window);
        return new ReferenceResolution(reference, facts);
    }

    private ReferenceResolution environmentWindow(AgentToolContext context, IotSourceEnum source, Map<String, Object> raw) {
        if (observations == null) throw unavailable("环境历史事实暂不可读取");
        String from = required(raw, "from", 64, "环境引用窗口无效");
        String to = required(raw, "to", 64, "环境引用窗口无效");
        try {
            if (!OffsetDateTime.parse(to, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .isAfter(OffsetDateTime.parse(from, DateTimeFormatter.ISO_OFFSET_DATE_TIME))) {
                throw invalid("环境引用窗口无效");
            }
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid("环境引用窗口无效");
        }
        ObservationResponse response;
        try {
            response = observations.read(new ObservationRequest(identity(context, source),
                    ObservationOperationEnum.ENVIRONMENT_HISTORY, Map.of("from", from, "to", to))).block(TIMEOUT);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("环境历史事实暂不可读取");
        }
        if (response == null || response.status() == ObservationStatusEnum.UNAVAILABLE) {
            throw unavailable("环境历史事实暂不可读取");
        }
        Map<String, Object> report = new LinkedHashMap<>(response.payload());
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("type", "environment_window");
        reference.put("from", from);
        reference.put("to", to);
        reference.put("label", "环境历史 · " + from + " 至 " + to);
        reference.put("verified", true);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("environmentTimeLabels", environmentTimeLabels(report));
        facts.put("environmentWindow", report);
        return new ReferenceResolution(reference, facts);
    }

    /** Display times are server conversions of the same instants, never model arithmetic. */
    private static Map<String, Object> environmentTimeLabels(Map<String, Object> report) {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("timezone", "Asia/Shanghai");
        for (String section : List.of("window", "coverage")) {
            if (report.get(section) instanceof Map<?, ?> values) {
                for (String key : List.of("from", "to")) {
                    putShanghaiTime(labels, section + "." + key, values.get(key));
                }
            }
        }
        if (report.get("metrics") instanceof List<?> metrics) {
            for (Object item : metrics) {
                if (item instanceof Map<?, ?> metric && metric.get("peak") instanceof Map<?, ?> peak) {
                    putShanghaiTime(labels, "peak." + metric.get("key"), peak.get("at"));
                }
            }
        }
        return labels;
    }

    private static void putShanghaiTime(Map<String, Object> labels, String key, Object value) {
        if (!(value instanceof String time)) return;
        try {
            labels.put(key, OffsetDateTime.parse(time).atZoneSameInstant(SHANGHAI)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } catch (java.time.format.DateTimeParseException invalidTime) {
            labels.put(key, "时间无法核验，不推算");
        }
    }

    private ReferenceResolution report(AgentToolContext context, IotSourceEnum source, Map<String, Object> raw, String type) {
        if (reports == null) throw unavailable("报告事实暂不可读取");
        String reportId = required(raw, "reportId", 100, "报告引用身份无效");
        try {
            Map<String, Object> report = reports.read(reportId,
                    new RequestIdentity(context.principal().authorization(), context.principal().tenantCode(),
                            source, context.principal().ownerKey()));
            String title = textOr(report, "title", "报告");
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("type", type);
            reference.put("reportId", reportId);
            reference.put("label", title);
            reference.put("status", textOr(report, "status", ""));
            reference.put("generatedAt", textOr(report, "generatedAt", ""));
            reference.put("verified", true);
            Map<String, Object> facts = new LinkedHashMap<>();
            if (report.get("evidenceNotes") instanceof List<?> notes && !notes.isEmpty()) {
                facts.put("evidenceNotes", notes);
            }
            facts.put("report", report);
            return new ReferenceResolution(reference, facts);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("报告事实暂不可读取");
        }
    }

    private ReferenceResolution device(AgentToolContext context, IotSourceEnum source, Map<String, Object> raw) {
        if (iot == null) throw unavailable("设备事实暂不可读取");
        String deviceId = required(raw, "deviceId", 300, "设备引用身份无效");
        try {
            AuthorizedDeviceScope scope = iot.loadAuthorizedDeviceScope(identity(context, source)).block(TIMEOUT);
            AuthorizedDevice device = scope == null ? null : scope.device(deviceId).orElse(null);
            if (device == null) throw new BusinessException("DEVICE_NOT_FOUND", "设备不存在或没有查看权限", 404);
            Map<String, Object> deviceFacts = new LinkedHashMap<>();
            deviceFacts.put("id", device.id());
            deviceFacts.put("kind", device.kind());
            deviceFacts.put("name", device.name());
            deviceFacts.put("location", device.installAddress());
            deviceFacts.put("observationOnly", device.observationOnly());
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("type", "device");
            reference.put("deviceId", device.id());
            reference.put("label", device.name());
            reference.put("verified", true);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("device", deviceFacts);
            DeviceStateReadResult state = iot.readDeviceStates(identity(context, source), List.of(device.id()))
                    .block(TIMEOUT);
            if (state != null && state.states().get(device.id()) != null) {
                facts.put("state", state.states().get(device.id()));
            }
            return new ReferenceResolution(reference, facts);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("设备事实暂不可读取");
        }
    }

    private static RequestIdentity identity(AgentToolContext context, IotSourceEnum source) {
        return new RequestIdentity(context.principal().authorization(), context.principal().tenantCode(),
                source, context.principal().ownerKey());
    }

    private static IotSourceEnum parseSource(String source) {
        try {
            return IotSourceEnum.parse(source);
        } catch (RuntimeException error) {
            throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        }
    }

    private static String required(Map<String, Object> body, String key, int maxLength, String message) {
        String value = text(body, key);
        if (value == null || value.length() > maxLength) throw invalid(message);
        return value;
    }

    private static String optionalText(Map<String, Object> body, String key, int maxLength) {
        Object raw = body.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String value) || value.isBlank() || value.length() > maxLength) {
            throw invalid("引用字段无效");
        }
        return value.trim();
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String textOr(Map<String, Object> body, String key, String fallback) {
        String value = text(body, key);
        return value == null ? fallback : value;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_REFERENCE", message, 400);
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException("REFERENCE_UNAVAILABLE", message, 503);
    }
}
