package com.ehe.iotagent.iot.internal;

import javax.sql.DataSource;
import com.ehe.iotagent.schema.SchemaMigrations;

/** Control-owned candidate-set schema; idempotent and safe to run beside the fact schema. */
public final class DeviceCandidateSchemaInitializer {
    private final SchemaMigrations migrations;

    public DeviceCandidateSchemaInitializer(DataSource dataSource) {
        this.migrations = new SchemaMigrations(dataSource);
    }

    public void initialize() {
        migrations.ensureDeviceCandidateSets();
    }
}
