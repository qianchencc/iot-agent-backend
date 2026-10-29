package ltd.qianc.iotagent.report;

/**
 * Public application seam for the report HTTP routes. The endpoint is intentionally
 * opaque so callers and contract tests do not couple to MVC implementation classes.
 */
public interface ReportHttpApplication {
    Object endpoint();
}
