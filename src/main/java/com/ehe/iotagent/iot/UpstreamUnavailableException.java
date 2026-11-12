package com.ehe.iotagent.iot;

import com.ehe.iotagent.error.BusinessException;

/** Explicit fail-closed result for missing, malformed, timed out, or unavailable upstream. */
public final class UpstreamUnavailableException extends BusinessException {

    public UpstreamUnavailableException(String operation) {
        super("IOT_UPSTREAM_UNAVAILABLE", operation + "：物联上游当前不可用，请稍后重试", "请稍后重试", 503);
    }
}
