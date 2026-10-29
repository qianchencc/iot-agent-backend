package ltd.qianc.iotagent.report;

import java.util.Objects;
import ltd.qianc.iotagent.report.internal.controller.ReportController;

/** Composes the public report HTTP application without exposing its MVC controller. */
public final class ReportHttpApplicationFactory {
    private ReportHttpApplicationFactory() { }

    public static ReportHttpApplication local(ReportService service) {
        Objects.requireNonNull(service, "service");
        Object endpoint = new ReportController(service);
        return () -> endpoint;
    }
}
