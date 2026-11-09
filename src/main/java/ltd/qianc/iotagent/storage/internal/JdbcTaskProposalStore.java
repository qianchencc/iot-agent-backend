package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import ltd.qianc.iotagent.task.TaskProposalFact;
import ltd.qianc.iotagent.task.TaskProposalStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskProposalStore extends AbstractJdbcFactSupport implements TaskProposalStore {
    public JdbcTaskProposalStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public TaskProposalFact save(TaskProposalFact proposal) {
        MapValues values = values(proposal.payloadJson());
        String tenant = proposal.tenantCode().isBlank() ? values.tenant : proposal.tenantCode();
        String owner = proposal.ownerKey().isBlank() ? values.owner : proposal.ownerKey();
        String source = proposal.source().isBlank() ? "office" : proposal.source();
        try {
            jdbc.update("INSERT INTO task_proposals(proposal_id,tenant_code,owner_key,conversation_id,turn_id,version,status,payload_json,idempotency_key,kind,target_json,before_json,after_json,operation,source,expires_at,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    proposal.proposalId(), tenant, owner, values.conversation, values.turn, proposal.version(), proposal.status(),
                    proposal.payloadJson(), proposal.idempotencyKey(), proposal.kind(), proposal.targetJson(), proposal.beforeJson(),
                    proposal.afterJson(), proposal.operation(), source, proposal.expiresAt() == null ? null : time(proposal.expiresAt()),
                    time(java.time.Instant.now()), time(java.time.Instant.now()));
            return proposal;
        } catch (DuplicateKeyException duplicate) { return findByIdempotencyKey(proposal.idempotencyKey()).orElseThrow(() -> duplicate); }
    }
    @Override public Optional<TaskProposalFact> find(String id) {
        return one("SELECT proposal_id,version,status,payload_json,idempotency_key,kind,target_json,before_json,after_json,operation,tenant_code,owner_key,source,expires_at FROM task_proposals WHERE proposal_id=?", this::map, id);
    }
    @Override public Optional<TaskProposalFact> findByIdempotencyKey(String key) {
        return one("SELECT proposal_id,version,status,payload_json,idempotency_key,kind,target_json,before_json,after_json,operation,tenant_code,owner_key,source,expires_at FROM task_proposals WHERE idempotency_key=?", this::map, key);
    }
    @Override public Optional<TaskProposalFact> findByConversationAndTurn(String conversationId, String turnId) {
        return one("SELECT proposal_id,version,status,payload_json,idempotency_key,kind,target_json,before_json,after_json,operation,tenant_code,owner_key,source,expires_at FROM task_proposals WHERE conversation_id=? AND turn_id=? ORDER BY create_time DESC LIMIT 1", this::map, conversationId, turnId);
    }
    @Override public Optional<TaskProposalFact> updateIfVersion(TaskProposalFact replacement, long expectedVersion) {
        int changed = jdbc.update("UPDATE task_proposals SET version=?,status=?,payload_json=?,update_time=? WHERE proposal_id=? AND version=?",
                replacement.version(), replacement.status(), replacement.payloadJson(), time(java.time.Instant.now()),
                replacement.proposalId(), expectedVersion);
        return changed == 1 ? Optional.of(replacement) : Optional.empty();
    }
    @Override public java.util.List<TaskProposalFact> retireForConversation(String conversationId, String tenantCode,
            String ownerKey, String source) {
        return transactions.execute(transaction -> {
            var pending = jdbc.query("SELECT proposal_id,version,status,payload_json,idempotency_key,kind,target_json,before_json,after_json,operation,tenant_code,owner_key,source,expires_at FROM task_proposals "
                            + "WHERE conversation_id=? AND tenant_code=? AND owner_key=? AND source=? AND status IN ('PENDING_CONFIRMATION','EXPIRED') FOR UPDATE",
                    this::map, conversationId, tenantCode, ownerKey, source);
            if (pending.isEmpty()) return java.util.List.of();
            jdbc.update("UPDATE task_proposals SET status='CANCELLED',version=version+1,update_time=? "
                            + "WHERE conversation_id=? AND tenant_code=? AND owner_key=? AND source=? AND status IN ('PENDING_CONFIRMATION','EXPIRED')",
                    time(java.time.Instant.now()), conversationId, tenantCode, ownerKey, source);
            return pending.stream().map(proposal -> new TaskProposalFact(proposal.proposalId(), proposal.version() + 1,
                    "CANCELLED", proposal.payloadJson(), proposal.idempotencyKey(), proposal.kind(), proposal.targetJson(),
                    proposal.beforeJson(), proposal.afterJson(), proposal.operation(), proposal.tenantCode(),
                    proposal.ownerKey(), proposal.source(), proposal.expiresAt())).toList();
        });
    }
    private TaskProposalFact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        java.sql.Timestamp expiry = rs.getTimestamp(14);
        return new TaskProposalFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), expiry == null ? null : expiry.toInstant());
    }
    private MapValues values(String payload) { var body = json(payload); return new MapValues(text(body, "tenantCode"), text(body, "ownerKey"), text(body, "conversationId"), text(body, "turnId")); }
    private record MapValues(String tenant, String owner, String conversation, String turn) {}
}
