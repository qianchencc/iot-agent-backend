package com.ehe.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.report.internal.DefaultReportFactProducer;

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
