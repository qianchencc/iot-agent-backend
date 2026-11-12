package com.ehe.iotagent.admin;

/** Explicit process-apply seam. The default backend never restarts a process. */
public interface AdminApplyCoordinator {
    /** Whether this deployment has a real supervised apply implementation. */
    default boolean enabled() {
        return false;
    }

    ApplyResult apply();

    record ApplyResult(int status, String code, String message) {
    }
}
