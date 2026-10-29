package ltd.qianc.iotagent.report;

import java.util.Map;
import ltd.qianc.iotagent.iot.ReportDownload;
import ltd.qianc.iotagent.iot.RequestIdentity;

/** Local report-artifact boundary. HTTP routes never read or render facts directly. */
public interface ReportService {
    ReportFact save(ReportFact report);

    Map<String, Object> read(String reportId, RequestIdentity identity);

    ReportDownload download(String reportId, String format, RequestIdentity identity);
}
