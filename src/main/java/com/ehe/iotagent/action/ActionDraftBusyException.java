package com.ehe.iotagent.action;

import com.ehe.iotagent.error.BusinessException;

public final class ActionDraftBusyException extends BusinessException {
    public ActionDraftBusyException(String message) {
        super("ACTION_DRAFT_BUSY", message, "请稍后重试", 409);
    }
}
