package com.ehe.iotagent.action;

import com.ehe.iotagent.error.BusinessException;

public final class ActionDraftRevisionConflictException extends BusinessException {
    public ActionDraftRevisionConflictException() {
        super("ACTION_DRAFT_STALE", "Action draft revision is stale", "请刷新草案后重试", 409);
    }
}
