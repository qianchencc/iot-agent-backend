package com.ehe.iotagent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.report.internal.DefaultReportService;

/** Construction entry point for local report contract tests and application composition. */
public final class ReportServiceFactory {
    private ReportServiceFactory() { }

    public static ReportService local(ReportFactStore store, ObjectMapper mapper, IotAdapter iot) {
        return local(store, mapper, iot, ReportRendererFactory.pdf(), ReportRendererFactory.xlsx());
    }

    public static ReportService local(ReportFactStore store, ObjectMapper mapper, IotAdapter iot,
            ReportRenderer pdfRenderer, ReportRenderer xlsxRenderer) {
        return new DefaultReportService(store, mapper, iot, pdfRenderer, xlsxRenderer);
    }
}
