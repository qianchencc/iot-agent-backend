package com.ehe.iotagent.action;

/** Adapter failure that preserves whether an upstream command may already be in flight. */
public final class ActionDispatchException extends RuntimeException {
    private final boolean dispatchStarted;

    public ActionDispatchException(String message, boolean dispatchStarted) {
        super(message);
        this.dispatchStarted = dispatchStarted;
    }

    public ActionDispatchException(String message, Throwable cause, boolean dispatchStarted) {
        super(message, cause);
        this.dispatchStarted = dispatchStarted;
    }

    public boolean dispatchStarted() {
        return dispatchStarted;
    }
}
