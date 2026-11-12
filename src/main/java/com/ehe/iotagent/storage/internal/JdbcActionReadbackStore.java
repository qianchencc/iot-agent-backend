package com.ehe.iotagent.storage.internal;

import java.time.Instant;
import java.util.List;
import com.ehe.iotagent.action.ActionReadbackFact;
import com.ehe.iotagent.action.ActionReadbackStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JdbcActionReadbackStore extends AbstractJdbcFactSupport implements ActionReadbackStore {
    public JdbcActionReadbackStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        super(jdbc, transactions, mapper);
    }

    @Override
    public void append(ActionReadbackFact fact) {
        try {
            jdbc.update("INSERT INTO action_readbacks(draft_id,draft_revision,action_id,outcome,observed_state_json,observed_at,create_time,update_time) VALUES (?,?,?,?,?,?,?,?)",
                    fact.draftId(), fact.draftRevision(), fact.actionId(), fact.outcome(), fact.observedStateJson(), fact.observedAt(), time(Instant.now()), time(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // A terminal confirm replay must not create a second readback fact.
        }
    }

    @Override
    public List<ActionReadbackFact> findByDraft(String draftId) {
        return jdbc.query("SELECT draft_id,draft_revision,action_id,outcome,observed_state_json,observed_at FROM action_readbacks WHERE draft_id=? ORDER BY id",
                (rs, row) -> new ActionReadbackFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), draftId);
    }
}
