package ltd.qianc.iotagent.audit;

import java.util.List;

public interface AuditEventStore {

    AuditEventFact append(AuditEventFact event);

    List<AuditEventFact> readByTenant(String tenantCode);
}
