package ltd.qianc.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.report.internal.DefaultReportService;

/** Construction entry point for local report contract tests and application composition. */
public final class ReportServiceFactory {
    private ReportServiceFactory() { }

    public static ReportService local(ReportFactStore store, ObjectMapper mapper, IotAdapter iot) {
        return new DefaultReportService(store, mapper, iot);
    }
}
