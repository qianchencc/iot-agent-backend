package ltd.qianc.iotagent.admin.internal;

import java.nio.file.Path;
import java.util.Map;
import ltd.qianc.iotagent.admin.PiSessionOperations;

/** Explicitly unavailable when the Node sidecar boundary is not configured. */
public final class FailClosedPiSessionOperations implements PiSessionOperations {
    public FailClosedPiSessionOperations(String ignored) {
    }

    @Override
    public OperationResult capacity() {
        return unavailable();
    }

    @Override
    public OperationResult integrityCheck() {
        return unavailable();
    }

    @Override
    public OperationResult recoverWriterLease() {
        return unavailable();
    }

    @Override
    public OperationResult backup(Path destination, boolean force) {
        return unavailable();
    }

    @Override
    public OperationResult restore(Path backup, boolean apply) {
        return unavailable();
    }

    private OperationResult unavailable() {
        return new OperationResult(503, "PI_SESSION_UNAVAILABLE", "Pi Session sidecar 运维能力不可用", Map.of());
    }
}
