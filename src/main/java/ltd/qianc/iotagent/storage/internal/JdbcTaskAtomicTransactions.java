package ltd.qianc.iotagent.storage.internal;

import java.util.Objects;
import java.util.function.Supplier;
import ltd.qianc.iotagent.task.TaskAtomicTransactions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JDBC transaction boundary over the shared agent DataSource. Store methods
 * called inside join the ongoing transaction; failures roll back every task
 * fact mutation together instead of leaving a half-confirmed task behind.
 */
public final class JdbcTaskAtomicTransactions implements TaskAtomicTransactions {
    private final TransactionTemplate transactions;

    public JdbcTaskAtomicTransactions(TransactionTemplate transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public <T> T write(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }
}
