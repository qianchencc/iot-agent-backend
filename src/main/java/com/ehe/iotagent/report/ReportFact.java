package com.ehe.iotagent.report;

public record ReportFact(
        String reportId,
        String source,
        String payloadJson) {
}
