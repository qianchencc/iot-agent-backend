package ltd.qianc.iotagent.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One member row of the audit member index: the activity of one trusted
 * identity (tenant + identity realm + principal) or, when history carries no
 * resolved identity, of the honest unresolved bucket. The name is never
 * borrowed from the allowlist or the operator; callers render missing names as
 * unknown.
 */
public record AuditMember(
        String tenantCode,
        String identityRealm,
        String principalId,
        String displayName,
        String mobileNumber,
        boolean identityResolved,
        long eventCount,
        Map<String, Long> sources,
        Instant firstAt,
        Instant lastAt) {

    public AuditMember {
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }
}
