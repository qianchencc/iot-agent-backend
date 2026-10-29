package ltd.qianc.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.report.internal.DefaultReportFactProducer;

/** Construction entry point; the terminal producer implementation remains internal. */
public final class ReportFactProducerFactory {
    private ReportFactProducerFactory() { }

    public static ReportFactProducer local(ReportFactStore store, ObjectMapper mapper) {
        return new DefaultReportFactProducer(store, mapper);
    }
}
