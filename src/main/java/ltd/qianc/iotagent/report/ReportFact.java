package ltd.qianc.iotagent.report;

public record ReportFact(
        String reportId,
        String source,
        String payloadJson) {
}
