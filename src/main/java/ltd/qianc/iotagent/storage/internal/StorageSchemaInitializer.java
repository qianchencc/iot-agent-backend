package ltd.qianc.iotagent.storage.internal;

import javax.sql.DataSource;
import ltd.qianc.iotagent.schema.SchemaMigrations;

/**
 * 共享存储基线初始化入口（Spring 装配锚点）。建表资源、兼容升级与迁移登记
 * 统一收拢到 {@link SchemaMigrations}，本类不再持有 DDL。
 */
public final class StorageSchemaInitializer {
    private final SchemaMigrations migrations;

    public StorageSchemaInitializer(DataSource dataSource) {
        this.migrations = new SchemaMigrations(dataSource);
    }

    public void initialize() {
        migrations.ensureSharedFacts();
    }
}
