package ltd.qianc.iotagent.storage.internal;

import java.time.Instant;
import java.util.List;
import ltd.qianc.iotagent.action.ActionReceiptFact;
import ltd.qianc.iotagent.action.ActionReceiptOutcome;
import ltd.qianc.iotagent.action.ActionReceiptStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JdbcActionReceiptStore extends JdbcFactSupport implements ActionReceiptStore {
    public JdbcActionReceiptStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        super(jdbc, transactions, mapper);
    }

    @Override
    public void append(ActionReceiptFact fact) {
        try {
            jdbc.update("INSERT INTO action_receipts(draft_id,draft_revision,action_id,outcome,payload_json,create_time,update_time) VALUES (?,?,?,?,?,?,?)",
                    fact.draftId(), fact.draftRevision(), fact.actionId(), fact.outcome().name(), fact.payloadJson(), time(Instant.now()), time(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // A terminal confirm replay must not create a second receipt fact.
        }
    }

    @Override
    public List<ActionReceiptFact> findByDraft(String draftId) {
        return jdbc.query("SELECT draft_id,draft_revision,action_id,outcome,payload_json FROM action_receipts WHERE draft_id=? ORDER BY id",
                (rs, row) -> new ActionReceiptFact(rs.getString(1), rs.getLong(2), rs.getString(3), ActionReceiptOutcome.valueOf(rs.getString(4)), rs.getString(5)), draftId);
    }
}
