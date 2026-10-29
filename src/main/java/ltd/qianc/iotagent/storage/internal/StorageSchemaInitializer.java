package ltd.qianc.iotagent.storage.internal;

import javax.sql.DataSource;
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
                new ClassPathResource("db/mysql/admin-configuration.sql"));
        populator.setContinueOnError(false);
        DatabasePopulatorUtils.execute(populator, dataSource);
    }
}
