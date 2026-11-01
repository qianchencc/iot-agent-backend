package ltd.qianc.iotagent.task;

/**
 * Durable recurring-plan projection boundary. Implementations must advance the
 * plan pointer only with a storage compare-and-set transaction.
 */
public interface TaskOccurrenceMaterializer {
    int materializeOnce();
}
