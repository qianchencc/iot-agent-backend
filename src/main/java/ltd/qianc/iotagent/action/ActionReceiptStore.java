package ltd.qianc.iotagent.action;

import java.util.List;

/** Durable action gateway receipt facts. */
public interface ActionReceiptStore {
    void append(ActionReceiptFact fact);

    default List<ActionReceiptFact> findByDraft(String draftId) {
        return List.of();
    }
}
