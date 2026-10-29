package ltd.qianc.iotagent.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class PiSessionOperationsContractTest {
    @Test
    void localFixtureChecksCapacityIntegrityAndRecoversExpiredWriterLease() throws Exception {
        Path directory = Files.createTempDirectory("pi-session-ops-");
        Path database = directory.resolve("runtime.sqlite.sessions");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("CREATE TABLE writer_leases (session_id TEXT PRIMARY KEY, expires_at_ms INTEGER NOT NULL)");
            connection.createStatement().execute("INSERT INTO writer_leases VALUES ('expired', 0)");
        }
        PiSessionOperations operations = PiSessionOperationsFactory.local(database, 1);

        assertThat(operations.capacity().status()).isEqualTo(200);
        assertThat(operations.integrityCheck().code()).isEqualTo("PI_SESSION_INTEGRITY_OK");
        assertThat(operations.recoverWriterLease().details()).containsEntry("recoveredLeases", 1);
        assertThat(operations.integrityCheck().status()).isEqualTo(200);
        delete(directory);
    }

    @Test
    void localFixtureBacksUpRestoresAndRejectsCorruptOrUnconfirmedOperations() throws Exception {
        Path directory = Files.createTempDirectory("pi-session-backup-");
        Path database = directory.resolve("runtime.sqlite.sessions");
        Path backup = directory.resolve("backups/session.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("CREATE TABLE messages (id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
            connection.createStatement().execute("INSERT INTO messages VALUES (1, 'before')");
        }
        PiSessionOperations operations = PiSessionOperationsFactory.local(database, 1);

        assertThat(operations.backup(backup, false).status()).isEqualTo(200);
        assertThat(operations.backup(backup, false).code()).isEqualTo("PI_SESSION_BACKUP_EXISTS");
        assertThat(operations.restore(backup, false).code()).isEqualTo("PI_SESSION_RESTORE_REQUIRES_APPLY");
        assertThat(operations.restore(backup, true).status()).isEqualTo(200);

        Files.writeString(backup, "not sqlite");
        assertThat(operations.restore(backup, true).status()).isNotEqualTo(200);
        delete(directory);
    }

    private static void delete(Path path) throws Exception {
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
