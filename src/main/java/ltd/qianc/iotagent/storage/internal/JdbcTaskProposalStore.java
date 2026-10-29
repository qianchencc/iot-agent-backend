package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import ltd.qianc.iotagent.task.TaskProposalFact;
import ltd.qianc.iotagent.task.TaskProposalStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcTaskProposalStore extends JdbcFactSupport implements TaskProposalStore {
    public JdbcTaskProposalStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public TaskProposalFact save(TaskProposalFact proposal) {
        MapValues values = values(proposal.payloadJson());
        try {
            jdbc.update("INSERT INTO task_proposals(proposal_id,tenant_code,owner_key,conversation_id,turn_id,version,status,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)", proposal.proposalId(), values.tenant, values.owner, values.conversation, values.turn, proposal.version(), proposal.status(), proposal.payloadJson(), proposal.idempotencyKey(), time(java.time.Instant.now()), time(java.time.Instant.now()));
            return proposal;
        } catch (DuplicateKeyException duplicate) { return findByIdempotencyKey(proposal.idempotencyKey()).orElseThrow(() -> duplicate); }
    }
    @Override public Optional<TaskProposalFact> find(String id) { return one("SELECT proposal_id,version,status,payload_json,idempotency_key FROM task_proposals WHERE proposal_id=?", this::map, id); }
    @Override public Optional<TaskProposalFact> findByIdempotencyKey(String key) { return one("SELECT proposal_id,version,status,payload_json,idempotency_key FROM task_proposals WHERE idempotency_key=?", this::map, key); }
    @Override public Optional<TaskProposalFact> updateIfVersion(TaskProposalFact replacement, long expectedVersion) {
        int changed = jdbc.update("UPDATE task_proposals SET version=?,status=?,payload_json=?,update_time=? WHERE proposal_id=? AND version=?", replacement.version(), replacement.status(), replacement.payloadJson(), time(java.time.Instant.now()), replacement.proposalId(), expectedVersion);
        return changed == 1 ? Optional.of(replacement) : Optional.empty();
    }
    @Override public void retireForConversation(String conversationId) { jdbc.update("UPDATE task_proposals SET status='RETIRED',update_time=? WHERE conversation_id=? AND status NOT IN ('CONFIRMED','CANCELLED','RETIRED')", time(java.time.Instant.now()), conversationId); }
    private TaskProposalFact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new TaskProposalFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)); }
    private MapValues values(String payload) { var body = json(payload); return new MapValues(text(body, "tenantCode"), text(body, "ownerKey"), text(body, "conversationId"), text(body, "turnId")); }
    private record MapValues(String tenant, String owner, String conversation, String turn) {}
}
