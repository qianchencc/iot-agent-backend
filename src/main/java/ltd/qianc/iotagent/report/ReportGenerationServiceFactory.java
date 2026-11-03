package ltd.qianc.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.report.internal.DefaultReportGenerationService;

/** Construction entry point for local contract tests and application composition. */
public final class ReportGenerationServiceFactory {
    private ReportGenerationServiceFactory() { }

    public static ReportGenerationService local(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations) {
        return new DefaultReportGenerationService(store, mapper, iot, observations);
    }

    public static ReportGenerationService local(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations, Clock clock) {
        return new DefaultReportGenerationService(store, mapper, iot, observations, clock);
    }

    public static ReportEvidencePort evidence(IotAdapter iot, ObservationAdapter observations, Clock clock) {
        return new ltd.qianc.iotagent.report.internal.ReportEvidenceService(iot, observations, clock)::build;
    }
}
