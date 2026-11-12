package com.ehe.iotagent.storage.internal;

import jakarta.annotation.PostConstruct;

/**
 * Repairs gaps left by pre-transaction confirmations once at startup.
 * New confirmations commit their facts, credentials and phases atomically;
 * no recurring scan of immutable confirmation history is needed.
 */
public final class JdbcTaskConfirmedRecoveryStartup {
    private final JdbcTaskConfirmedRecovery recovery;

    public JdbcTaskConfirmedRecoveryStartup(JdbcTaskConfirmedRecovery recovery) {
        this.recovery = recovery;
    }

    @PostConstruct
    public void recoverOnStartup() {
        recovery.recoverOnce();
    }
}
