package ltd.qianc.iotagent.admin;

import java.nio.file.Path;
import java.util.Map;

/** Operational boundary for the Node-owned Pi Session SQLite store. */
public interface PiSessionOperations {
    OperationResult capacity();

    OperationResult integrityCheck();

    OperationResult recoverWriterLease();

    OperationResult backup(Path destination, boolean force);

    OperationResult restore(Path backup, boolean apply);

    record OperationResult(int status, String code, String message, Map<String, Object> details) {
        public static OperationResult unavailable() {
            return new OperationResult(503, "PI_SESSION_UNAVAILABLE", "Pi Session sidecar 运维能力不可用", Map.of());
        }
    }
}
