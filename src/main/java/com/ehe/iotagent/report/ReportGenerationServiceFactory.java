package com.ehe.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.report.internal.DefaultReportGenerationService;

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
        return new com.ehe.iotagent.report.internal.ReportEvidenceService(iot, observations, clock)::build;
    }
}
