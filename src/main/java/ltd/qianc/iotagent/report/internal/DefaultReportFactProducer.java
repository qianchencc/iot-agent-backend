package ltd.qianc.iotagent.report.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactProducer;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.report.ReportUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Projects terminal company checkpoints through the same server-evidence report engine. */
@Component
@ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
public final class DefaultReportFactProducer implements ReportFactProducer {
    private final ReportFactStore store;
    private final ObjectMapper mapper;
    private final ReportEvidenceService evidence;
    private final String buildId;

    public DefaultReportFactProducer(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations) {
        this(store, mapper, iot, observations, Clock.systemUTC(), "local");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultReportFactProducer(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations,
            @org.springframework.beans.factory.annotation.Value("${agent.report.build-id:local}") String buildId) {
        this(store, mapper, iot, observations, Clock.systemUTC(), buildId);
    }

    public DefaultReportFactProducer(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations, Clock clock) {
        this(store, mapper, iot, observations, clock, "local");
    }

    public DefaultReportFactProducer(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations, Clock clock, String buildId) {
        this.store = store;
        this.mapper = mapper;
        this.evidence = new ReportEvidenceService(iot, observations, clock, buildId);
        this.buildId = buildId == null || buildId.isBlank() ? "local" : buildId.trim();
    }

    @Override
    public Optional<ReportFact> produce(ReportCheckpoint checkpoint, RequestIdentity identity) {
        if (checkpoint == null || !"company".equals(checkpoint.source()) || !terminal(checkpoint.outcome())) {
            return Optional.empty();
        }
        JsonNode parsed;
        try {
            parsed = mapper.readTree(checkpoint.payloadJson());
        } catch (Exception error) {
            throw new BusinessException("INVALID_REPORT", "任务报告请求格式无效");
        }
        if (parsed == null || !parsed.isObject() || !parsed.has("reportRequest")) {
            return Optional.empty();
        }
        JsonNode request = parsed.get("reportRequest");
        if (!request.isObject()) throw new BusinessException("INVALID_REPORT", "reportRequest 必须是声明式对象");
        if (identity == null) {
            throw new ReportUnavailableException("REPORT_EVIDENCE_IDENTITY_MISSING",
                    "定时任务报告需要经过核验的服务身份，不能使用任务或模型自述数据");
        }
        if (identity.source() != IotSourceEnum.COMPANY) {
            throw new BusinessException("REPORT_SOURCE_UNSUPPORTED", "报告只支持 Company 来源");
        }
        if (!identity.tenantCode().equals(checkpoint.tenantCode())
                || !identity.ownerKey().equals(checkpoint.ownerKey())) {
            throw new BusinessException("REPORT_SCOPE_CHANGED", "任务报告身份与任务归属不符", 403);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> content = mapper.convertValue(request, Map.class);
        String reportId = UUID.nameUUIDFromBytes((checkpoint.taskId() + "\u0000" + checkpoint.phaseId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        try {
            Optional<ReportFact> existing = store.find(reportId);
            if (existing.isPresent()) return existing;
            // Scheduled relative periods resolve against the occurrence's bound time,
            // not the live clock: a delayed retry across a calendar boundary must
            // still capture the window of the original checkpoint, and the resolved
            // absolute window is persisted inside the saved fact and reused on retry.
            Map<String, Object> report = "ac_inspection".equals(text(content.get("kind")))
                    ? inspectionReport(checkpoint, content, parsed.get("inspectionEvidence"))
                    : evidence.build(identity, content, checkpoint.checkpointAt());
            ObjectNode snapshot = mapper.valueToTree(report);
            snapshot.put("id", reportId);
            snapshot.put("source", "company");
            snapshot.put("tenantCode", checkpoint.tenantCode());
            snapshot.put("ownerKey", checkpoint.ownerKey());
            snapshot.put("status", report.getOrDefault("status", "partial").toString());
            snapshot.put("generatedAt", checkpoint.generatedAt().toString());
            snapshot.put("taskId", checkpoint.taskId());
            snapshot.put("phaseId", checkpoint.phaseId());
            snapshot.put("checkpointAt", checkpoint.checkpointAt().toString());
            if (!checkpoint.objective().isBlank() && !snapshot.hasNonNull("objective")) {
                snapshot.put("objective", checkpoint.objective());
            }
            ObjectNode origin = snapshot.with("origin");
            origin.put("kind", "checkpoint");
            origin.put("taskId", checkpoint.taskId());
            origin.put("phaseId", checkpoint.phaseId());
            origin.put("at", checkpoint.checkpointAt().toString());
            return Optional.of(store.save(new ReportFact(reportId, "company", mapper.writeValueAsString(snapshot))));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new ReportUnavailableException("REPORT_FACT_UNAVAILABLE", "报告事实暂不可保存");
        }
    }

    private Map<String, Object> inspectionReport(ReportCheckpoint checkpoint, Map<String, Object> request,
            JsonNode evidenceNode) {
        for (String key : request.keySet()) {
            if (!Set.of("kind", "title", "period", "objective").contains(key)) {
                throw new BusinessException("INVALID_REPORT", "空调巡检报告请求包含未知字段：" + key);
            }
        }
        if (evidenceNode == null || !evidenceNode.isObject()) {
            throw new BusinessException("INVALID_REPORT", "空调巡检报告缺少服务端阶段证据");
        }
        Map<String, Object> evidence = mapper.convertValue(evidenceNode, Map.class);
        String evidenceTask = text(evidence.get("taskId"));
        if (evidenceTask != null && !checkpoint.taskId().equals(evidenceTask)) {
            throw new BusinessException("REPORT_SCOPE_CHANGED", "巡检证据与任务归属不符", 403);
        }
        List<Map<String, Object>> checkpoints = maps(evidence.get("checkpoints"));
        List<Map<String, Object>> blocks = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        Set<String> deviceIds = new LinkedHashSet<>();
        int adjusted = 0;
        int unconfirmed = 0;
        Instant from = null;
        Instant to = null;
        for (Map<String, Object> item : checkpoints) {
            String phaseId = text(item.get("phaseId"));
            String at = text(item.get("at"));
            if (phaseId == null || at == null) {
                gaps.add("存在缺少阶段标识或时间的检查结果");
                continue;
            }
            Instant atInstant;
            try {
                atInstant = java.time.OffsetDateTime.parse(at).toInstant();
            } catch (RuntimeException invalid) {
                gaps.add("阶段 " + phaseId + " 的检查时间无效");
                continue;
            }
            if (from == null || atInstant.isBefore(from)) from = atInstant;
            if (to == null || atInstant.isAfter(to)) to = atInstant;
            List<Map<String, Object>> devices = maps(item.get("devices"));
            List<Map<String, Object>> cleanDevices = new ArrayList<>();
            for (Map<String, Object> device : devices) {
                String deviceId = text(device.get("deviceId"));
                if (deviceId == null) {
                    gaps.add("阶段 " + phaseId + " 存在缺少设备标识的检查结果");
                    continue;
                }
                deviceIds.add(deviceId);
                String disposition = text(device.get("disposition"));
                String outcome = text(device.get("outcome"));
                if ("adjusted".equals(disposition) || "verified".equals(outcome)) adjusted++;
                if ("unconfirmed".equals(outcome) || "unknown".equals(outcome) || "not_dispatched".equals(disposition)) unconfirmed++;
                cleanDevices.add(new LinkedHashMap<>(device));
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "ac_inspection");
            block.put("phaseId", phaseId);
            block.put("at", at);
            block.put("status", text(item.get("status")));
            block.put("outcome", text(item.get("outcome")));
            block.put("mode", text(item.get("mode")));
            block.put("target", item.get("target"));
            block.put("criteria", item.get("criteria"));
            block.put("devices", cleanDevices);
            if (item.get("energyEstimate") instanceof Map<?, ?> estimate) block.put("energyEstimate", estimatedEnergy((Map<?, ?>) estimate));
            blocks.add(block);
        }
        if (checkpoints.isEmpty()) gaps.add("没有取得持久化的阶段检查结果");
        if (!"COMPLETED".equals(text(evidence.get("taskOutcome")))) gaps.add("任务终态为 " + text(evidence.get("taskOutcome")));
        if (!gaps.isEmpty()) blocks.add(textBlock("未满足或缺数的要求", String.join("；", gaps)));
        Map<String, Object> period = period(request.get("period"), from, to);
        String status = gaps.isEmpty() ? "completed" : "partial";
        String summary = "空调巡检报告，期间 " + text(period.get("from"), "") + " 至 " + text(period.get("to"), "")
                + "；阶段 " + checkpoints.size() + " 个，设备 " + deviceIds.size() + " 台，已调整 " + adjusted
                + " 台，待确认/未知 " + unconfirmed + " 台"
                + (gaps.isEmpty() ? "。" : "；" + String.join("；", gaps) + "。");
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("checkpoints", checkpoints.size());
        coverage.put("deviceIds", new ArrayList<>(deviceIds));
        coverage.put("adjusted", adjusted);
        coverage.put("unconfirmed", unconfirmed);
        coverage.put("missing", gaps);
        Map<String, Object> requirements = new LinkedHashMap<>();
        requirements.put("kind", "ac_inspection");
        requirements.put("period", period);
        requirements.put("phaseIds", checkpoints.stream().map(item -> item.get("phaseId")).toList());
        requirements.put("deviceIds", new ArrayList<>(deviceIds));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", text(request.get("title"), "空调巡检报告"));
        result.put("objective", text(request.get("objective"), checkpoint.objective()));
        result.put("status", status);
        result.put("summary", summary);
        result.put("blocks", blocks);
        result.put("requirements", requirements);
        result.put("coverage", coverage);
        result.put("meterIds", List.of());
        result.put("authorizationMeterIds", List.of());
        result.put("build", buildId);
        result.put("taskId", checkpoint.taskId());
        return result;
    }

    private static Map<String, Object> estimatedEnergy(Map<?, ?> estimate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "estimated");
        result.put("measured", false);
        result.put("label", "估算（非电表实测）");
        Object value = estimate.get("value");
        if (value != null) result.put("value", value);
        result.put("unit", text(estimate.get("unit"), "度"));
        if (estimate.get("costValue") != null) result.put("costValue", estimate.get("costValue"));
        result.put("costUnit", text(estimate.get("costUnit"), "元"));
        result.put("baseline", estimate.get("baseline"));
        result.put("assumptions", estimate.get("assumptions"));
        result.put("coverage", estimate.get("coverage"));
        result.put("reason", text(estimate.get("reason"), "按调前设定温度与经验参数估算，非电表实测"));
        result.put("calculation", estimate.get("calculation"));
        return result;
    }

    private static Map<String, Object> period(Object raw, Instant from, Instant to) {
        Map<String, Object> period = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map && "absolute".equals(text(map.get("kind")))) {
            period.put("kind", "absolute");
            period.put("from", text(map.get("from"), from == null ? "" : from.toString()));
            period.put("to", text(map.get("to"), to == null ? "" : to.toString()));
        } else {
            period.put("kind", "absolute");
            period.put("from", from == null ? "" : from.toString());
            period.put("to", to == null ? "" : to.toString());
        }
        return period;
    }

    private static Map<String, Object> textBlock(String title, String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("title", title);
        block.put("text", text);
        return block;
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> valueMap = new LinkedHashMap<>();
                map.forEach((key, entry) -> valueMap.put(String.valueOf(key), entry));
                result.add(valueMap);
            }
        }
        return result;
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String text(Object value, String fallback) {
        String result = text(value);
        return result == null ? fallback : result;
    }

    private static boolean terminal(String outcome) {
        // Runtime builds the task report for every terminal status, including
        // missed, satisfied and cancelled tasks; the evidence projection must
        // not silently drop those occurrences.
        return switch (outcome.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED",
                    "MISSED", "SATISFIED", "CANCELLED" -> true;
            default -> false;
        };
    }
}
