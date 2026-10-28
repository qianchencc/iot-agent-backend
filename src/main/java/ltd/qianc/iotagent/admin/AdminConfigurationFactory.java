package ltd.qianc.iotagent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ltd.qianc.iotagent.admin.internal.EncryptedFileAdminConfigurationStore;

/** Construction boundary for the approved local encrypted admin configuration adapter. */
public final class AdminConfigurationFactory {
    private AdminConfigurationFactory() {
    }

    public static AdminConfigurationStore encryptedFile(
            ObjectMapper mapper, Path path, byte[] key, Map<String, Object> initial, Set<String> locked) {
        return new EncryptedFileAdminConfigurationStore(
                Objects.requireNonNull(mapper), Objects.requireNonNull(path), Objects.requireNonNull(key),
                Objects.requireNonNull(initial), Objects.requireNonNull(locked));
    }
}
