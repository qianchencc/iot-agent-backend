package ltd.qianc.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.report.internal.DefaultReportFactProducer;

/** Construction entry point; the terminal producer implementation remains internal. */
public final class ReportFactProducerFactory {
    private ReportFactProducerFactory() { }

    public static ReportFactProducer local(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations) {
        return new DefaultReportFactProducer(store, mapper, iot, observations);
    }

    public static ReportFactProducer local(ReportFactStore store, ObjectMapper mapper,
            IotAdapter iot, ObservationAdapter observations, Clock clock) {
        return new DefaultReportFactProducer(store, mapper, iot, observations, clock);
    }
}
