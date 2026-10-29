package ltd.qianc.iotagent.report;

import java.util.Map;
import ltd.qianc.iotagent.iot.ReportDownload;
import ltd.qianc.iotagent.report.internal.ReportPdfRenderer;
import ltd.qianc.iotagent.report.internal.ReportXlsxRenderer;

/** Local renderer adapters; rendering only projects fields from the authorized report map. */
public final class ReportRendererFactory {
    private ReportRendererFactory() { }

    public static ReportRenderer pdf() {
        return report -> new ReportDownload("application/pdf", filename(report, "pdf"), ReportPdfRenderer.render(report));
    }

    public static ReportRenderer xlsx() {
        return report -> new ReportDownload("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                filename(report, "xlsx"), ReportXlsxRenderer.render(report));
    }

    private static String filename(Map<String, Object> report, String format) {
        Object title = report.get("title");
        String value = title == null ? "report-" + String.valueOf(report.getOrDefault("id", "")) : String.valueOf(title);
        String safe = value.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
        return (safe.isBlank() ? "report" : safe) + "." + format;
    }
}
