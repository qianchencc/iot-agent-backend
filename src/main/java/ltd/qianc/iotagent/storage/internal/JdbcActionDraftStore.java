package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import ltd.qianc.iotagent.action.ActionDraftFact;
import ltd.qianc.iotagent.action.ActionDraftStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcActionDraftStore extends AbstractJdbcFactSupport implements ActionDraftStore {
    public JdbcActionDraftStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { super(jdbc, transactions, mapper); }
    @Override public ActionDraftFact create(ActionDraftFact draft) {
        try {
            jdbc.update("INSERT INTO action_drafts(draft_id,revision,status,payload_json,idempotency_key,create_time,update_time) VALUES (?,?,?,?,?,?,?)", draft.draftId(), draft.revision(), draft.status(), draft.payloadJson(), draft.idempotencyKey(), time(Instant.now()), time(Instant.now()));
            return draft;
        } catch (DuplicateKeyException duplicate) { return findByIdempotencyKey(draft.idempotencyKey()).orElseThrow(() -> duplicate); }
    }
    @Override public Optional<ActionDraftFact> find(String draftId) { return one("SELECT draft_id,revision,status,payload_json,idempotency_key FROM action_drafts WHERE draft_id=?", this::map, draftId); }
    @Override public ActionDraftFact update(String draftId, long expectedRevision, ActionDraftFact replacement) {
        if (jdbc.update("UPDATE action_drafts SET revision=?,status=?,payload_json=?,idempotency_key=?,update_time=? WHERE draft_id=? AND revision=?", replacement.revision(), replacement.status(), replacement.payloadJson(), replacement.idempotencyKey(), time(Instant.now()), draftId, expectedRevision) != 1) throw new IllegalStateException("action draft revision conflict");
        return replacement;
    }
    @Override public Optional<ActionDraftFact> findByIdempotencyKey(String key) { return one("SELECT draft_id,revision,status,payload_json,idempotency_key FROM action_drafts WHERE idempotency_key=?", this::map, key); }
    @Override public List<ActionDraftFact> findByConversation(String conversationId) { return jdbc.query("SELECT draft_id,revision,status,payload_json,idempotency_key FROM action_drafts WHERE JSON_UNQUOTE(JSON_EXTRACT(payload_json,'$.conversationId'))=? ORDER BY create_time,id", this::map, conversationId); }
    private ActionDraftFact map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new ActionDraftFact(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)); }
}
