package ltd.qianc.iotagent.report.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.report.ReportGenerationService;
import ltd.qianc.iotagent.report.ReportUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Generates immutable conversation-origin reports from server-owned evidence. */
@Service
@ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
public final class DefaultReportGenerationService implements ReportGenerationService {
    private final ReportFactStore store;
    private final ObjectMapper mapper;
    private final ReportEvidenceService evidence;

    public DefaultReportGenerationService(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations) {
        this(store, mapper, iot, observations, Clock.systemUTC(), "local");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultReportGenerationService(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations,
            @org.springframework.beans.factory.annotation.Value("${agent.report.build-id:local}") String buildId) {
        this(store, mapper, iot, observations, Clock.systemUTC(), buildId);
    }

    public DefaultReportGenerationService(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations, Clock clock) {
        this(store, mapper, iot, observations, clock, "local");
    }

    public DefaultReportGenerationService(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations, Clock clock, String buildId) {
        this.store = store;
        this.mapper = mapper;
        this.evidence = new ReportEvidenceService(iot, observations, clock, buildId);
    }

    @Override
    public GeneratedReport generate(RequestIdentity identity, String conversationId, String turnId,
            String requestKey, Map<String, Object> content) {
        if (identity == null || identity.source() != IotSourceEnum.COMPANY) {
            throw new BusinessException("REPORT_SOURCE_UNSUPPORTED", "报告只支持 Company 来源");
        }
        require(conversationId, "conversationId");
        require(turnId, "turnId");
        String effectiveKey = requestKey == null || requestKey.isBlank() ? turnId : requestKey;
        String reportId = stableId(identity, conversationId, turnId, effectiveKey);

        try {
            var existing = store.find(reportId);
            if (existing.isPresent()) return new GeneratedReport(reportId, true, publicReport(existing.get()));
            Map<String, Object> report = evidence.build(identity, content);
            ObjectNode snapshot = mapper.valueToTree(report);
            snapshot.put("id", reportId);
            snapshot.put("source", "company");
            snapshot.put("tenantCode", identity.tenantCode());
            snapshot.put("ownerKey", identity.ownerKey());
            snapshot.put("generatedAt", Instant.now().toString());
            if (!snapshot.path("objective").isTextual()) snapshot.put("objective", "");
            ObjectNode origin = mapper.createObjectNode();
            snapshot.set("origin", origin);
            origin.put("kind", "conversation");
            origin.put("conversationId", conversationId);
            origin.put("turnId", turnId);
            origin.put("requestKey", effectiveKey);
            ReportFact saved = store.save(new ReportFact(reportId, "company", mapper.writeValueAsString(snapshot)));
            ReportFact durable = store.find(reportId).orElse(saved);
            return new GeneratedReport(reportId, durable != saved || existing.isPresent(), publicReport(durable));
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new ReportUnavailableException("REPORT_FACT_UNAVAILABLE", "报告事实暂不可保存");
        }
    }

    private Map<String, Object> publicReport(ReportFact fact) throws Exception {
        JsonNode root = mapper.readTree(fact.payloadJson());
        if (root.has("report") && root.get("report").isObject()) root = root.get("report");
        Map<String, Object> result = mapper.convertValue(root, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        result.remove("tenantCode");
        result.remove("ownerKey");
        java.util.List<String> notes = MeterExportEvidence.reportNotes(result);
        if (!notes.isEmpty()) result.put("evidenceNotes", notes);
        return result;
    }

    private static String stableId(RequestIdentity identity, String conversationId, String turnId, String key) {
        String input = identity.tenantCode() + "\u0000" + identity.ownerKey() + "\u0000"
                + conversationId + "\u0000" + turnId + "\u0000" + key;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new BusinessException("INVALID_REPORT", name + " 不能为空");
    }
}
