package com.ehe.iotagent.iot;

import com.ehe.iotagent.error.BusinessException;

/** Missing customer observation protocol is a typed 503, never a fake successful result. */
public final class ObservationUnavailableException extends BusinessException {
    public ObservationUnavailableException(String operation) {
        super("OBSERVATION_UPSTREAM_UNAVAILABLE", operation + "：只读观察上游当前不可用，请稍后重试", "请稍后重试", 503);
    }
}
