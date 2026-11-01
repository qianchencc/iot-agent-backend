package ltd.qianc.iotagent.report.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactProducer;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.report.ReportUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/** Projects terminal company checkpoints through the same server-evidence report engine. */
@Component
@ConditionalOnBean(ReportFactStore.class)
public final class DefaultReportFactProducer implements ReportFactProducer {
    private final ReportFactStore store;
    private final ObjectMapper mapper;
    private final ReportEvidenceService evidence;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultReportFactProducer(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations) {
        this(store, mapper, iot, observations, Clock.systemUTC());
    }

    public DefaultReportFactProducer(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations, Clock clock) {
        this.store = store;
        this.mapper = mapper;
        this.evidence = new ReportEvidenceService(iot, observations, clock);
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
            Map<String, Object> report = evidence.build(identity, content);
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

    private static boolean terminal(String outcome) {
        return switch (outcome.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED" -> true;
            default -> false;
        };
    }
}
