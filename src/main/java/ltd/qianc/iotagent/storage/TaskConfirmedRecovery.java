package ltd.qianc.iotagent.storage;

/**
 * Public seam for the confirmed-proposal recovery sweep: completes rows a
 * user confirmed but an interrupted write never fully materialized.
 */
public interface TaskConfirmedRecovery {

    RecoveryResult recoverOnce();

    /** How many rows the sweep completed and how many it had to leave behind. */
    record RecoveryResult(int recovered, int skipped) { }
}
