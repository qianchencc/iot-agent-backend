package ltd.qianc.iotagent.report;

import java.util.Map;
import ltd.qianc.iotagent.iot.ReportDownload;
import ltd.qianc.iotagent.iot.RequestIdentity;

/** Local report-artifact boundary. HTTP routes never read or render facts directly. */
public interface ReportService {
    ReportFact save(ReportFact report);

    Map<String, Object> read(String reportId, RequestIdentity identity);

    /** Immutable facts previously generated in this conversation, not current device authorization. */
    java.util.List<Map<String, Object>> conversationFacts(String conversationId, java.util.List<String> reportIds,
            RequestIdentity identity);

    ReportDownload download(String reportId, String format, RequestIdentity identity);
}
