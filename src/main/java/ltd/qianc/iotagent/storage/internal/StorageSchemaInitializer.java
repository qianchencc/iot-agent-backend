package ltd.qianc.iotagent.storage.internal;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
public final class StorageSchemaInitializer {
    private final DataSource dataSource;

    public StorageSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initialize() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/mysql/agent-facts.sql"),
                new ClassPathResource("db/mysql/admin-configuration.sql"),
                new ClassPathResource("db/mysql/read-snapshot-barrier.sql"));
        populator.setContinueOnError(false);
        DatabasePopulatorUtils.execute(populator, dataSource);
        ensureBarrierColumns();
        ensureTaskProposalColumns();
    }

    private void ensureBarrierColumns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name='read_snapshot_credential_barriers' AND column_name='issued_at'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE read_snapshot_credential_barriers ADD COLUMN issued_at DATETIME(3) NULL");
            jdbc.update("UPDATE read_snapshot_credential_barriers SET issued_at=update_time WHERE issued_at IS NULL");
            jdbc.execute("ALTER TABLE read_snapshot_credential_barriers MODIFY COLUMN issued_at DATETIME(3) NOT NULL");
        }
    }

    private void ensureTaskProposalColumns() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ensureColumn(jdbc, "kind", "VARCHAR(32) NOT NULL DEFAULT 'TASK'");
        ensureColumn(jdbc, "target_json", "JSON NULL");
        ensureColumn(jdbc, "before_json", "JSON NULL");
        ensureColumn(jdbc, "after_json", "JSON NULL");
        ensureColumn(jdbc, "operation", "VARCHAR(32) NOT NULL DEFAULT 'create'");
        ensureColumn(jdbc, "source", "VARCHAR(16) NOT NULL DEFAULT 'office'");
        ensureColumn(jdbc, "expires_at", "DATETIME NULL");
    }

    private static void ensureColumn(JdbcTemplate jdbc, String name, String definition) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name='task_proposals' AND column_name=?",
                Integer.class, name);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE task_proposals ADD COLUMN " + name + " " + definition);
        }
    }
}
