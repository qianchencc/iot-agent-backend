package com.ehe.iotagent.iot;

/** Sanitised failure for one observation area or device; upstream response text is never exposed. */
public record ObservationFailure(String area, String code, String message) {
    public ObservationFailure {
        if (area == null || area.isBlank()) throw new IllegalArgumentException("area is required");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("message is required");
    }
}
