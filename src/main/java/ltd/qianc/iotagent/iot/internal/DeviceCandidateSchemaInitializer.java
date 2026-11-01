package ltd.qianc.iotagent.iot.internal;

import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Control-owned candidate-set schema; idempotent and safe to run beside the fact schema. */
public final class DeviceCandidateSchemaInitializer {
    private final DataSource dataSource;

    public DeviceCandidateSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initialize() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("db/mysql/device-candidate-sets.sql"));
        populator.setContinueOnError(false);
        DatabasePopulatorUtils.execute(populator, dataSource);
    }
}
