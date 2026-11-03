package ltd.qianc.iotagent.pisession;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import ltd.qianc.iotagent.pisession.internal.JdbcPiSessionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Construction seam; Pi storage tables and transactions remain hidden. */
public final class PiSessionServiceFactory {
    private PiSessionServiceFactory() {}
    public static PiSessionService jdbc(JdbcTemplate jdbc, TransactionTemplate transactions,
            ObjectMapper mapper, PiSessionService.ContextResolver resolver, Clock clock) {
        return new JdbcPiSessionService(jdbc, transactions, mapper, resolver, clock);
    }
}
