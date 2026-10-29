package ltd.qianc.iotagent.admin;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.web.reactive.function.client.WebClient;
import ltd.qianc.iotagent.admin.internal.FailClosedPiSessionOperations;
import ltd.qianc.iotagent.admin.internal.NodePiSessionOperations;
import ltd.qianc.iotagent.admin.internal.SqlitePiSessionOperations;

/** Construction entry point; Pi Session implementations remain internal. */
public final class PiSessionOperationsFactory {
    private PiSessionOperationsFactory() {
    }

    public static PiSessionOperations node(WebClient client, String bridgeToken) {
        return new NodePiSessionOperations(Objects.requireNonNull(client, "client"), bridgeToken);
    }

    public static PiSessionOperations local(Path database, long minimumFreeBytes) {
        return new SqlitePiSessionOperations(Objects.requireNonNull(database, "database"), minimumFreeBytes);
    }

    public static PiSessionOperations failClosed(String reason) {
        return new FailClosedPiSessionOperations(reason);
    }
}
