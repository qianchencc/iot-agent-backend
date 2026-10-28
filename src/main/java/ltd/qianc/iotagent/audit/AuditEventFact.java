package ltd.qianc.iotagent.audit;

public record AuditEventFact(
        String eventId,
        String tenantCode,
        String eventType,
        String payloadJson) {
}
