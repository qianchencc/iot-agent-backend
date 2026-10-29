package ltd.qianc.iotagent.admin.internal;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import ltd.qianc.iotagent.admin.PiSessionOperations;

/** Local-only fixture implementation; production Pi facts remain owned by the Node sidecar. */
public final class SqlitePiSessionOperations implements PiSessionOperations {
    private final Path database;
    private final long minimumFreeBytes;

    public SqlitePiSessionOperations(Path database, long minimumFreeBytes) {
        this.database = requireLocal(database);
        if (minimumFreeBytes < 0) throw new IllegalArgumentException("minimumFreeBytes must not be negative");
        this.minimumFreeBytes = minimumFreeBytes;
    }

    @Override
    public OperationResult capacity() {
        if (!Files.isRegularFile(database)) return new OperationResult(503, "PI_SESSION_CAPACITY_FAILED", "Pi Session 存储不存在", Map.of());
        try {
            Path parent = parent(database);
            long free = Files.getFileStore(parent).getUsableSpace();
            if (free < minimumFreeBytes) {
                return new OperationResult(507, "PI_SESSION_CAPACITY_LOW", "Pi Session 存储空间不足",
                        Map.of("freeBytes", free, "minimumFreeBytes", minimumFreeBytes));
            }
            return new OperationResult(200, "PI_SESSION_CAPACITY_OK", "Pi Session 存储空间充足",
                    Map.of("freeBytes", free, "minimumFreeBytes", minimumFreeBytes));
        } catch (Exception ignored) {
            return new OperationResult(503, "PI_SESSION_CAPACITY_FAILED", "Pi Session 存储空间检查失败", Map.of());
        }
    }

    @Override
    public OperationResult integrityCheck() {
        if (!Files.isRegularFile(database)) return failure("PI_SESSION_INTEGRITY_FAILED");
        try (Connection connection = readOnly(database);
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            String value = result.next() ? result.getString(1) : "";
            if (!"ok".equalsIgnoreCase(value)) return failure("PI_SESSION_INTEGRITY_FAILED");
            return new OperationResult(200, "PI_SESSION_INTEGRITY_OK", "Pi Session 完整性检查通过",
                    Map.of("integrity", "ok"));
        } catch (Exception ignored) {
            return failure("PI_SESSION_INTEGRITY_FAILED");
        }
    }

    @Override
    public OperationResult recoverWriterLease() {
        if (!Files.isRegularFile(database)) return failure("PI_SESSION_WRITER_RECOVERY_FAILED");
        try (Connection connection = writable(database); var statement = connection.prepareStatement(
                "DELETE FROM writer_leases WHERE expires_at_ms <= ?")) {
            statement.setLong(1, System.currentTimeMillis());
            int recovered = statement.executeUpdate();
            connection.commit();
            return new OperationResult(200, "PI_SESSION_WRITER_RECOVERY_OK", "Pi Session writer lease recovery complete",
                    Map.of("recoveredLeases", recovered));
        } catch (SQLException ignored) {
            return failure("PI_SESSION_WRITER_RECOVERY_FAILED");
        }
    }

    @Override
    public OperationResult backup(Path destination, boolean force) {
        Path target = requireLocal(destination);
        if (target.equals(database)) return new OperationResult(400, "PI_SESSION_INVALID", "备份目标无效", Map.of());
        if (!Files.isRegularFile(database)) return failure("PI_SESSION_BACKUP_FAILED");
        if (Files.exists(target) && !force) {
            return new OperationResult(409, "PI_SESSION_BACKUP_EXISTS", "备份目标已存在，请显式确认覆盖", Map.of());
        }
        try {
            Files.createDirectories(parent(target));
        } catch (Exception ignored) {
            return new OperationResult(503, "PI_SESSION_BACKUP_FAILED", "Pi Session 备份失败", Map.of());
        }
        OperationResult capacity = capacityAt(target);
        if (capacity.status() != 200) return capacity;
        OperationResult integrity = integrityCheck();
        if (integrity.status() != 200) return integrity;
        Path temporary = temporary(target, ".backup-");
        try {
            copySnapshot(database, temporary);
            OperationResult copied = checkFile(temporary, "PI_SESSION_BACKUP_FAILED");
            if (copied.status() != 200) return copied;
            replace(temporary, target);
            return new OperationResult(200, "PI_SESSION_BACKUP_OK", "Pi Session 备份完成",
                    Map.of("integrity", "ok", "freeBytes", capacity.details().get("freeBytes")));
        } catch (Exception ignored) {
            return failure("PI_SESSION_BACKUP_FAILED");
        } finally {
            deleteQuietly(temporary);
        }
    }

    @Override
    public OperationResult restore(Path backup, boolean apply) {
        Path source = requireLocal(backup);
        if (source.equals(database)) return new OperationResult(400, "PI_SESSION_INVALID", "恢复源无效", Map.of());
        if (!Files.isRegularFile(source)) return failure("PI_SESSION_RESTORE_FAILED");
        OperationResult sourceCheck = checkFile(source, "PI_SESSION_RESTORE_FAILED");
        if (sourceCheck.status() != 200) return sourceCheck;
        Path target = database;
        if (Files.exists(target) && !apply) {
            return new OperationResult(409, "PI_SESSION_RESTORE_REQUIRES_APPLY", "恢复目标已存在，请显式确认应用", Map.of());
        }
        try {
            Files.createDirectories(parent(target));
        } catch (Exception ignored) {
            return new OperationResult(503, "PI_SESSION_RESTORE_FAILED", "Pi Session 恢复失败", Map.of());
        }
        OperationResult capacity = capacityAt(target);
        if (capacity.status() != 200) return capacity;
        Path temporary = temporary(target, ".restore-");
        try {
            Files.createDirectories(parent(target));
            copySnapshot(source, temporary);
            OperationResult restored = checkFile(temporary, "PI_SESSION_RESTORE_FAILED");
            if (restored.status() != 200) return restored;
            if (apply) replace(temporary, target);
            return new OperationResult(200, "PI_SESSION_RESTORE_OK", apply ? "Pi Session 恢复完成" : "Pi Session 恢复校验通过",
                    Map.of("integrity", "ok", "applied", apply));
        } catch (Exception ignored) {
            return failure("PI_SESSION_RESTORE_FAILED");
        } finally {
            deleteQuietly(temporary);
        }
    }

    private OperationResult capacityAt(Path path) {
        try {
            long free = Files.getFileStore(parent(path)).getUsableSpace();
            if (free < minimumFreeBytes) return new OperationResult(507, "PI_SESSION_CAPACITY_LOW", "Pi Session 存储空间不足",
                    Map.of("freeBytes", free, "minimumFreeBytes", minimumFreeBytes));
            return new OperationResult(200, "PI_SESSION_CAPACITY_OK", "", Map.of("freeBytes", free));
        } catch (Exception ignored) {
            return new OperationResult(503, "PI_SESSION_CAPACITY_FAILED", "Pi Session 存储空间检查失败", Map.of());
        }
    }

    private static OperationResult checkFile(Path file, String code) {
        try (Connection connection = readOnly(file);
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA quick_check")) {
            String value = result.next() ? result.getString(1) : "";
            return "ok".equalsIgnoreCase(value)
                    ? new OperationResult(200, code.replace("_FAILED", "_OK"), "", Map.of("integrity", "ok"))
                    : failure(code);
        } catch (Exception ignored) {
            return failure(code);
        }
    }

    private static void copySnapshot(Path source, Path destination) throws SQLException {
        String uri = "jdbc:sqlite:file:" + source.toAbsolutePath() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(uri);
                var statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + sqlQuote(destination.toAbsolutePath().toString()) + "'");
        }
    }

    private static Connection readOnly(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:file:" + path.toAbsolutePath() + "?mode=ro");
    }

    private static Connection writable(Path path) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        connection.setAutoCommit(false);
        return connection;
    }

    private static String sqlQuote(String value) {
        return value.replace("'", "''");
    }

    private static void replace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path temporary(Path target, String suffix) {
        return parent(target).resolve("." + target.getFileName() + suffix + ProcessHandle.current().pid());
    }

    private static Path parent(Path path) {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null) throw new IllegalArgumentException("path must have a parent");
        return parent;
    }

    private static Path requireLocal(Path path) {
        Objects.requireNonNull(path, "path");
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.isAbsolute()) throw new IllegalArgumentException("path must be local");
        return absolute;
    }

    private static OperationResult failure(String code) {
        return new OperationResult(422, code, "Pi Session SQLite 运维操作失败", Map.of());
    }

    private static void deleteQuietly(Path path) {
        try {
            if (path != null) Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }
}
