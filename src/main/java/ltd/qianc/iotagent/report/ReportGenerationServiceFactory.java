package ltd.qianc.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.report.internal.DefaultReportGenerationService;

/** Construction entry point for local contract tests and application composition. */
public final class ReportGenerationServiceFactory {
    private ReportGenerationServiceFactory() { }

    public static ReportGenerationService local(ReportFactStore store, ObjectMapper mapper, IotAdapter iot) {
        return new DefaultReportGenerationService(store, mapper, iot);
    }
}
