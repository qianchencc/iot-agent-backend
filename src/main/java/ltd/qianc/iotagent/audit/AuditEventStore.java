package ltd.qianc.iotagent.audit;

import java.util.List;
import java.util.Optional;

public interface AuditEventStore {

    AuditEventFact append(AuditEventFact event);

    List<AuditEventFact> readByTenant(String tenantCode);
    Optional<AuditEventFact> findByEventId(String eventId);
    List<String> tenants();

    /**
     * Bounded, cursor-paged event read for the admin console. Production MySQL
     * implementations push every filter down; the default derives the same
     * answer from {@link #readByTenant(String)} so in-memory test stubs stay
     * contract-true without duplicating SQL.
     */
    default List<AuditEventFact> query(AuditEventQuery query) {
        return AuditQuerySupport.select(readByTenant(query.tenantCode()), query);
    }

    /**
     * Grouped member index for one tenant, newest activity first. Bounded in
     * production; the default aggregates {@link #readByTenant(String)}.
     */
    default AuditMemberIndex memberIndex(String tenantCode, String identityRealm,
            String principalId, int limit, int offset) {
        return AuditQuerySupport.index(tenantCode, readByTenant(tenantCode),
                identityRealm, principalId, limit, offset);
    }
}
