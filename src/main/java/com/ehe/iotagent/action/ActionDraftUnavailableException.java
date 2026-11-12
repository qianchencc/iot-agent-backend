package com.ehe.iotagent.action;

import com.ehe.iotagent.error.BusinessException;

public final class ActionDraftUnavailableException extends BusinessException {
    public ActionDraftUnavailableException(String message) {
        super("ACTION_DRAFT_UNAVAILABLE", message, "请稍后重试", 503);
    }

    public ActionDraftUnavailableException(String message, Throwable cause) {
        this(message);
        initCause(cause);
    }
}
