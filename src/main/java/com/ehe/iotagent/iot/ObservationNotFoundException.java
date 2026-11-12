package com.ehe.iotagent.iot;

import com.ehe.iotagent.error.BusinessException;

/** A requested observation record is absent from the current authorized scope. */
public final class ObservationNotFoundException extends BusinessException {
    public ObservationNotFoundException(String message) {
        super("SAFETY_EVENT_UNAVAILABLE", message, 404);
    }
}
