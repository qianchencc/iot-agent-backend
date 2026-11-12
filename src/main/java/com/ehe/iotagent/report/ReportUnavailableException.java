package com.ehe.iotagent.report;

import com.ehe.iotagent.error.BusinessException;

/** Sanitised 503 for durable report scope or renderer failures. */
public final class ReportUnavailableException extends BusinessException {
    public ReportUnavailableException(String code, String message) {
        super(code, message, "请稍后重试", 503);
    }
}
