package ltd.qianc.iotagent.report.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ReportDownload;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportFact;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.report.ReportFormatException;
import ltd.qianc.iotagent.report.ReportNotFoundException;
import ltd.qianc.iotagent.report.ReportRenderer;
import ltd.qianc.iotagent.report.ReportRendererFactory;
import ltd.qianc.iotagent.report.ReportService;
import ltd.qianc.iotagent.report.ReportUnavailableException;
import org.springframework.stereotype.Service;

/** Company report authorization, durable fact projection, and rendering seam. */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
public final class DefaultReportService implements ReportService {
    private static final Duration SCOPE_TIMEOUT = Duration.ofSeconds(30);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ReportFactStore store;
    private final ObjectMapper mapper;
    private final IotAdapter iot;
    private final ReportRenderer pdfRenderer;
    private final ReportRenderer xlsxRenderer;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultReportService(ReportFactStore store, ObjectMapper mapper, IotAdapter iot) {
        this(store, mapper, iot, ReportRendererFactory.pdf(), ReportRendererFactory.xlsx());
    }

    public DefaultReportService(ReportFactStore store, ObjectMapper mapper, IotAdapter iot,
            ReportRenderer pdfRenderer, ReportRenderer xlsxRenderer) {
        this.store = store;
        this.mapper = mapper;
        this.iot = iot;
        this.pdfRenderer = pdfRenderer;
        this.xlsxRenderer = xlsxRenderer;
    }

    @Override
    public ReportFact save(ReportFact report) {
        if (report == null || report.reportId() == null || report.reportId().isBlank()
                || !"company".equals(report.source())) {
            throw new IllegalArgumentException("company report fact is required");
        }
        try {
            return store.find(report.reportId()).orElseGet(() -> store.save(report));
        } catch (RuntimeException error) {
            throw new ReportUnavailableException("REPORT_FACT_UNAVAILABLE", "报告事实暂不可保存");
        }
    }

    @Override
    public Map<String, Object> read(String reportId, RequestIdentity identity) {
        if (identity == null || identity.source() != IotSource.COMPANY) throw new ReportNotFoundException();
        ReportFact fact = find(reportId);
        Map<String, Object> report = ownedReport(fact, identity);
        AuthorizedDeviceScope scope = loadScope(identity);
        try {
            for (ReportReferenceExtractor.Reference reference : ReportReferenceExtractor.extract(mapper.valueToTree(report))) {
                AuthorizedDevice device = scope.device(reference.id()).orElseThrow(ReportNotFoundException::new);
                if (reference.meter() && !energy(device)) throw new ReportNotFoundException();
            }
        } catch (ReportNotFoundException missing) {
            throw missing;
        } catch (RuntimeException invalid) {
            throw new ReportNotFoundException();
        }
        String contentHash = sha256(fact.payloadJson());
        report.remove("tenantCode");
        report.remove("ownerKey");
        report.remove("authorization");
        report.putIfAbsent("id", fact.reportId());
        report.put("source", "company");
        report.put("contentHash", contentHash);
        java.util.List<String> evidenceNotes = MeterExportEvidence.reportNotes(report);
        if (!evidenceNotes.isEmpty()) report.put("evidenceNotes", evidenceNotes);
        return report;
    }
    @Override
    public java.util.List<Map<String, Object>> conversationFacts(String conversationId,
            java.util.List<String> reportIds, RequestIdentity identity) {
        if (identity == null || identity.source() != IotSource.COMPANY) throw new ReportNotFoundException();
        java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (String id : new java.util.LinkedHashSet<>(reportIds)) {
            ReportFact fact = find(id);
            Map<String, Object> report = ownedReport(fact, identity);
            if (!conversationId.equals(object(report.get("origin")).get("conversationId"))) {
                throw new ReportNotFoundException();
            }
            Map<String, Object> saved = new java.util.LinkedHashMap<>();
            saved.put("reportId", fact.reportId());
            for (String key : java.util.List.of("title", "generatedAt", "summary", "coverage")) {
                if (report.get(key) != null) saved.put(key, report.get(key));
            }
            saved.put("historical", true);
            saved.put("currentDeviceAuthorization", false);
            saved.put("evidenceNotes", MeterExportEvidence.reportNotes(report));
            java.util.List<Map<String, Object>> values = new java.util.ArrayList<>();
            if (report.get("blocks") instanceof java.util.List<?> blocks) {
                for (Object value : blocks) {
                    Map<?, ?> block = object(value);
                    if (!"meter_report".equals(block.get("type"))) continue;
                    Map<?, ?> meter = object(block.get("report"));
                    Map<?, ?> params = object(meter.get("params"));
                    Map<String, Object> totals = new java.util.LinkedHashMap<>();
                    for (String key : java.util.List.of("total", "diff", "coverage")) {
                        if (meter.get(key) != null) totals.put(key, meter.get(key));
                    }
                    for (String key : java.util.List.of("span", "baseline")) {
                        if (params.get(key) != null) totals.put(key, params.get(key));
                    }
                    Map<?, ?> sides = object(meter.get("sides"));
                    for (String side : java.util.List.of("a", "b")) {
                        Object total = object(sides.get(side)).get("total");
                        if (total != null) totals.put(side + "Total", total);
                    }
                    values.add(totals);
                }
            }
            saved.put("meterFacts", values);
            result.add(saved);
        }
        return java.util.List.copyOf(result);
    }

    private ReportFact find(String reportId) {
        try {
            return store.find(reportId).orElseThrow(ReportNotFoundException::new);
        } catch (ReportNotFoundException missing) {
            throw missing;
        } catch (RuntimeException error) {
            throw new ReportUnavailableException("REPORT_FACT_UNAVAILABLE", "报告事实暂不可读取");
        }
    }

    private Map<String, Object> ownedReport(ReportFact fact, RequestIdentity identity) {
        Map<String, Object> report = parse(fact);
        if (!"company".equals(fact.source()) || !"company".equals(text(report, "source", fact.source()))
                || !identity.tenantCode().equals(text(report, "tenantCode", ""))
                || !identity.ownerKey().equals(text(report, "ownerKey", ""))) {
            throw new ReportNotFoundException();
        }
        return report;
    }

    private static Map<?, ?> object(Object value) { return value instanceof Map<?, ?> map ? map : Map.of(); }


    @Override
    public ReportDownload download(String reportId, String format, RequestIdentity identity) {
        if (!"pdf".equals(format) && !"xlsx".equals(format)) throw new ReportFormatException();
        Map<String, Object> report = read(reportId, identity);
        ReportRenderer renderer = "xlsx".equals(format) ? xlsxRenderer : pdfRenderer;
        try {
            return renderer.render(report);
        } catch (RuntimeException failure) {
            throw new ReportUnavailableException("REPORT_" + format.toUpperCase(Locale.ROOT) + "_UNAVAILABLE", "报告导出暂不可用");
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

    private Map<String, Object> parse(ReportFact fact) {
        try {
            JsonNode root = mapper.readTree(fact.payloadJson());
            if (root == null || !root.isObject()) throw new IllegalArgumentException("report payload is not an object");
            if (root.has("report") && root.get("report").isObject()) root = root.get("report");
            return mapper.convertValue(root, MAP);
        } catch (Exception error) {
            throw new ReportUnavailableException("REPORT_FACT_UNAVAILABLE", "报告事实暂不可读取");
        }
    }


    private static boolean energy(AuthorizedDevice device) {
        String kind = device.kind() == null ? "" : device.kind().toLowerCase(Locale.ROOT);
        return kind.equals("energy") || kind.equals("meter") || kind.equals("electricity");
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

}
