package com.ehe.iotagent.action;

import java.util.List;

/** Durable action device readback facts. */
public interface ActionReadbackStore {
    void append(ActionReadbackFact fact);

    default List<ActionReadbackFact> findByDraft(String draftId) {
        return List.of();
    }
}
