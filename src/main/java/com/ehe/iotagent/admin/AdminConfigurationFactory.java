package com.ehe.iotagent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import com.ehe.iotagent.admin.internal.EncryptedFileAdminConfigurationStore;
import com.ehe.iotagent.admin.internal.JdbcAdminConfigurationStore;
/** Construction boundary for the approved local encrypted admin configuration adapter. */
public final class AdminConfigurationFactory {
    private AdminConfigurationFactory() {
    }

    public static AdminConfigurationStore encryptedFile(
            ObjectMapper mapper, Path path, byte[] key, Supplier<Map<String, Object>> initial) {
        return new EncryptedFileAdminConfigurationStore(
                Objects.requireNonNull(mapper), Objects.requireNonNull(path), Objects.requireNonNull(key),
                Objects.requireNonNull(initial));
    }

    public static AdminConfigurationStore mysql(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, byte[] key,
            Supplier<Map<String, Object>> initial) {
        return new JdbcAdminConfigurationStore(
                Objects.requireNonNull(jdbc), Objects.requireNonNull(transactions), Objects.requireNonNull(mapper),
                Objects.requireNonNull(key), Objects.requireNonNull(initial));
    }
}
