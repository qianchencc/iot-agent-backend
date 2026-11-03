package ltd.qianc.iotagent.admin;

import java.util.Objects;
import ltd.qianc.iotagent.pisession.PiSessionService;

public final class PiSessionOperationsFactory {
    private PiSessionOperationsFactory() {}

    public static PiSessionOperations mysql(PiSessionService sessions) {
        Objects.requireNonNull(sessions, "sessions");
        return () -> {
            try {
                var health = sessions.health();
                if (!"ok".equals(health.get("status"))) return PiSessionOperations.OperationResult.unavailable();
                return new PiSessionOperations.OperationResult(200, "PI_SESSION_MYSQL_OK", "MySQL 会话存储在线", health);
            } catch (RuntimeException unavailable) {
                return PiSessionOperations.OperationResult.unavailable();
            }
        };
    }

    public static PiSessionOperations unavailable() {
        return PiSessionOperations.OperationResult::unavailable;
    }
}
