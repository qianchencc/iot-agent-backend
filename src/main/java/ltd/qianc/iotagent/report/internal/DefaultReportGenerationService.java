package ltd.qianc.iotagent.report.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.report.ReportGenerationService;
import ltd.qianc.iotagent.report.ReportUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** Generates immutable conversation-origin report snapshots after current-scope validation. */
@Service
@ConditionalOnBean(ReportFactStore.class)
public final class DefaultReportGenerationService implements ReportGenerationService {
    private static final Duration SCOPE_TIMEOUT = Duration.ofSeconds(30);
    private final ReportFactStore store;
    private final ObjectMapper mapper;
    private final IotAdapter iot;

    public DefaultReportGenerationService(ReportFactStore store, ObjectMapper mapper, IotAdapter iot) {
        this.store = store;
        this.mapper = mapper;
        this.iot = iot;
    }

    @Override
    public GeneratedReport generate(RequestIdentity identity, String conversationId, String turnId,
            String requestKey, Map<String, Object> content) {
        if (identity == null || identity.source() != IotSource.COMPANY) {
            throw new BusinessException("REPORT_SOURCE_UNSUPPORTED", "报告只支持 Company 来源");
        }
        require(conversationId, "conversationId");
        require(turnId, "turnId");
        String effectiveKey = requestKey == null || requestKey.isBlank() ? turnId : requestKey;
        JsonNode report = normalize(content);
        String reportId = stableId(identity, conversationId, turnId, effectiveKey);
        AuthorizedDeviceScope scope = loadScope(identity);
        validateReferences(report, scope);

        try {
            var existing = store.find(reportId);
            if (existing.isPresent()) return new GeneratedReport(reportId, true, publicReport(existing.get()));
            ObjectNode snapshot = (ObjectNode) report.deepCopy();
            snapshot.put("id", reportId);
            snapshot.put("source", "company");
            snapshot.put("tenantCode", identity.tenantCode());
            snapshot.put("ownerKey", identity.ownerKey());
            snapshot.put("objective", snapshot.path("objective").asText(""));
            snapshot.put("generatedAt", Instant.now().toString());
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

    private JsonNode normalize(Map<String, Object> content) {
        if (content == null || content.isEmpty()) throw new BusinessException("INVALID_REPORT", "报告内容不能为空");
        JsonNode root = mapper.valueToTree(content);
        if (root.has("report") && root.get("report").isObject()) root = root.get("report");
        if (!root.isObject() || !root.path("title").isTextual() || root.path("title").asText().isBlank()
                || !root.path("blocks").isArray()) {
            throw new BusinessException("INVALID_REPORT", "报告必须包含标题和内容区块");
        }
        String status = root.path("status").asText("completed").toLowerCase(Locale.ROOT);
        if (!List.of("preparing", "partial", "failed", "completed").contains(status)) {
            throw new BusinessException("INVALID_REPORT", "报告状态无效");
        }
        ((ObjectNode) root).put("status", status);
        String source = root.path("source").asText("company");
        if (!"company".equals(source)) throw new BusinessException("REPORT_SOURCE_UNSUPPORTED", "报告只支持 Company 来源");
        return root;
    }

    private void validateReferences(JsonNode report, AuthorizedDeviceScope scope) {
        try {
            for (ReportReferenceExtractor.Reference reference : ReportReferenceExtractor.extract(report)) {
                AuthorizedDevice device = scope.device(reference.id()).orElse(null);
                if (device == null || (reference.meter() && !energy(device))) {
                    throw new BusinessException("REPORT_SCOPE_CHANGED", "报告引用的设备已不在当前授权范围内", 404);
                }
            }
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException("INVALID_REPORT", "报告引用无效");
        }
    }

    private AuthorizedDeviceScope loadScope(RequestIdentity identity) {
        try {
            AuthorizedDeviceScope scope = iot.loadAuthorizedDeviceScope(identity).block(SCOPE_TIMEOUT);
            if (scope == null || scope.source() != IotSource.COMPANY || !identity.equals(scope.identity())) {
                throw new ReportUnavailableException("REPORT_SCOPE_UNAVAILABLE", "授权目录暂不可用，请稍后重试");
            }
            return scope;
        } catch (GatewayAuthorizationException error) {
            throw error;
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ReportUnavailableException("REPORT_SCOPE_UNAVAILABLE", "授权目录暂不可用，请稍后重试");
        }
    }

    private Map<String, Object> publicReport(ReportFact fact) throws Exception {
        JsonNode root = mapper.readTree(fact.payloadJson());
        if (root.has("report") && root.get("report").isObject()) root = root.get("report");
        Map<String, Object> result = mapper.convertValue(root, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        result.remove("tenantCode");
        result.remove("ownerKey");
        return result;
    }

    private static boolean energy(AuthorizedDevice device) {
        String kind = device.kind() == null ? "" : device.kind().toLowerCase(Locale.ROOT);
        return kind.equals("energy") || kind.equals("meter") || kind.equals("electricity");
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
