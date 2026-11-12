package com.ehe.iotagent.action;

/** Persisted action-draft states; terminal states are immutable and replayable. */
public enum ActionDraftStatusEnum {
    PENDING_CONFIRMATION,
    EXECUTING,
    ACCEPTED,
    PARTIALLY_ACCEPTED,
    REJECTED,
    NO_CHANGE,
    INTERRUPTED,
    CANCELLED,
    EXPIRED;

    public boolean terminal() {
        return this != PENDING_CONFIRMATION && this != EXECUTING;
    }
}
