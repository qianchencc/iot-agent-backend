package ltd.qianc.iotagent.conversation;

import java.util.Map;
import ltd.qianc.iotagent.iot.RequestIdentity;

/** Neutral application port for conversation-origin report generation. */
public interface ReportGenerationPort {
    GeneratedReport generate(RequestIdentity identity, String conversationId, String turnId,
            String requestKey, Map<String, Object> content);

    record GeneratedReport(String reportId, boolean reused, Map<String, Object> report) {
        public GeneratedReport {
            if (reportId == null || reportId.isBlank()) throw new IllegalArgumentException("reportId is required");
            if (report == null) throw new IllegalArgumentException("report is required");
        }
    }
}
