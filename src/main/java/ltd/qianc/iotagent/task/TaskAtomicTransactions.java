package ltd.qianc.iotagent.task;

import java.util.function.Supplier;

/**
 * Composable transaction boundary shared by every task fact store. All task
 * JDBC stores use one agent DataSource, so the writes issued inside
 * {@link #write} — proposal/plan/task CAS, protected execution identity and
 * phase scheduling — commit or roll back together. Network verification
 * (catalog scope, device reads) MUST stay outside; only durable fact
 * mutations belong in here, mirroring the Runtime's verify-then-commit order.
 */
public interface TaskAtomicTransactions {

    <T> T write(Supplier<T> action);

    /** Synchronous transaction seam for in-memory stores; JDBC uses the shared transaction. */
    static TaskAtomicTransactions direct() {
        return new TaskAtomicTransactions() {
            @Override public <T> T write(Supplier<T> action) { return action.get(); }
        };
    }
}
