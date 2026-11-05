package ltd.qianc.iotagent.audit;

import java.time.Instant;

public record AuditEventFact(
        String eventId,
        String tenantCode,
        String eventType,
        String payloadJson,
        Instant createdAt,
        long rowId) {

    /** Write-side facts have no physical cursor until persisted. */
    public AuditEventFact(String eventId, String tenantCode, String eventType, String payloadJson, Instant createdAt) {
        this(eventId, tenantCode, eventType, payloadJson, createdAt, 0);
    }

    /** Write-side constructor: the store supplies the persisted timestamp. */
    public AuditEventFact(String eventId, String tenantCode, String eventType, String payloadJson) {
        this(eventId, tenantCode, eventType, payloadJson, null);
    }

}
