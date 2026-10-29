package ltd.qianc.iotagent.report.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import ltd.qianc.iotagent.report.ReportService;
import ltd.qianc.iotagent.report.ReportUnavailableException;
import org.springframework.stereotype.Service;

/** Company report authorization, durable fact projection, and rendering seam. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ReportFactStore.class)
public final class DefaultReportService implements ReportService {
    private static final Duration SCOPE_TIMEOUT = Duration.ofSeconds(30);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ReportFactStore store;
    private final ObjectMapper mapper;
    private final IotAdapter iot;

    public DefaultReportService(ReportFactStore store, ObjectMapper mapper, IotAdapter iot) {
        this.store = store;
        this.mapper = mapper;
        this.iot = iot;
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
        ReportFact fact;
        try {
            fact = store.find(reportId).orElseThrow(ReportNotFoundException::new);
        } catch (ReportNotFoundException missing) {
            throw missing;
        } catch (RuntimeException error) {
            throw new ReportUnavailableException("REPORT_FACT_UNAVAILABLE", "报告事实暂不可读取");
        }
        Map<String, Object> report = parse(fact);
        if (!"company".equals(fact.source()) || !"company".equals(text(report, "source", fact.source()))) {
            throw new ReportNotFoundException();
        }
        if (!identity.tenantCode().equals(text(report, "tenantCode", ""))
                || !identity.ownerKey().equals(text(report, "ownerKey", ""))) {
            throw new ReportNotFoundException();
        }
        List<String> fixedIds = fixedDeviceIds(report);
        AuthorizedDeviceScope scope = loadScope(identity);
        for (String id : fixedIds) {
            AuthorizedDevice device = scope.device(id).orElseThrow(ReportNotFoundException::new);
            if (!energy(device)) throw new ReportNotFoundException();
        }
        report.remove("tenantCode");
        report.remove("ownerKey");
        report.remove("authorization");
        report.putIfAbsent("id", fact.reportId());
        report.put("source", "company");
        return report;
    }

    @Override
    public ReportDownload download(String reportId, String format, RequestIdentity identity) {
        if (!"pdf".equals(format) && !"xlsx".equals(format)) throw new ReportFormatException();
        Map<String, Object> report = read(reportId, identity);
        String title = safeFilename(text(report, "title", "report-" + reportId));
        try {
            if ("xlsx".equals(format)) {
                return new ReportDownload("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", title + ".xlsx",
                        ReportXlsxRenderer.render(report));
            }
            return new ReportDownload("application/pdf", title + ".pdf", ReportPdfRenderer.render(report));
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

    private static List<String> fixedDeviceIds(Map<String, Object> report) {
        Object value = report.containsKey("meterIds") ? report.get("meterIds") : report.get("deviceIds");
        if (!(value instanceof List<?> list)) return List.of();
        List<String> ids = new ArrayList<>();
        for (Object item : list) if (item instanceof String id && !id.isBlank() && !ids.contains(id)) ids.add(id);
        return List.copyOf(ids);
    }

    private static boolean energy(AuthorizedDevice device) {
        String kind = device.kind() == null ? "" : device.kind().toLowerCase(Locale.ROOT);
        return kind.equals("energy") || kind.equals("meter") || kind.equals("electricity");
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String safeFilename(String value) {
        String result = value.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        return result.isBlank() ? "report" : result;
    }
}
