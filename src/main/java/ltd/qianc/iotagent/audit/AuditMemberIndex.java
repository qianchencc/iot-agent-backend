package ltd.qianc.iotagent.audit;

import java.util.List;

/** Member index page plus the total number of members behind the filters. */
public record AuditMemberIndex(List<AuditMember> members, long total) {

    public AuditMemberIndex {
        members = members == null ? List.of() : List.copyOf(members);
    }
}
