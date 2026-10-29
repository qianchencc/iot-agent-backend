package ltd.qianc.iotagent.report;

import java.util.Map;
import ltd.qianc.iotagent.iot.ReportDownload;

/**
 * Public renderer seam for a previously authorized, immutable report projection.
 * Implementations MUST NOT reload facts or perform authorization.
 */
@FunctionalInterface
public interface ReportRenderer {
    ReportDownload render(Map<String, Object> report);
}
