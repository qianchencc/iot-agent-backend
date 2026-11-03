package ltd.qianc.iotagent.report.internal.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.error.ErrorResponse;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.ReportDownload;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.report.ReportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Durable Company report read/download boundary. */
@RestController
@RequestMapping("/api/agent/reports")
@ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
public final class ReportController {
    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> read(HttpServletRequest request, @PathVariable String id) {
        if (!companySource(request)) return notFound();
        try {
            Map<String, Object> report = reports.read(id, RequestIdentity.fromPrincipal(IotSource.COMPANY));
            ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
            if (report.get("contentHash") != null) {
                response.header("X-Report-Content-Hash", String.valueOf(report.get("contentHash")));
            }
            return response.body(Map.of("report", report));
        } catch (BusinessException failure) {
            return error(failure);
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(HttpServletRequest request, @PathVariable String id,
            @RequestParam(defaultValue = "pdf") String format) {
        if (!companySource(request)) return notFound();
        try {
            RequestIdentity identity = RequestIdentity.fromPrincipal(IotSource.COMPANY);
            Map<String, Object> report = reports.read(id, identity);
            ReportDownload result = reports.download(id, format, identity);
            String encoded = URLEncoder.encode(result.filename(), StandardCharsets.UTF_8).replace("+", "%20");
            ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
            if (report.get("contentHash") != null) {
                response.header("X-Report-Content-Hash", String.valueOf(report.get("contentHash")));
            }
            return response
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + asciiFallback(result.filename()) + "\"; filename*=UTF-8''" + encoded)
                    .header(HttpHeaders.CONTENT_TYPE, result.contentType()).body(result.content());
        } catch (BusinessException failure) {
            return error(failure);
        }
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("code", "REPORT_NOT_FOUND", "message", "报告不存在或没有查看权限"));
    }

    private static ResponseEntity<?> error(BusinessException failure) {
        return ResponseEntity.status(failure.httpStatus()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorResponse(failure.code(), failure.getMessage(), failure.recovery()));
    }
    private static boolean companySource(HttpServletRequest request) {
        String[] values = request.getParameterValues("source");
        return values != null && values.length == 1 && "company".equals(values[0]);
    }

    private static String asciiFallback(String filename) {
        return filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
