package ltd.qianc.iotagent.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Local-only password rotation helper; deployment-managed ADMIN_PASSWORD is never overwritten. */
public final class AdminPasswordFile {
    private AdminPasswordFile() {
    }

    public static void write(Path path, String password) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, password + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows development filesystems may not expose POSIX permissions.
        }
        Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
