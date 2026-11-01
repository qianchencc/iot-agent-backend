CREATE TABLE IF NOT EXISTS read_snapshot_credential_barriers (
    gateway_namespace VARCHAR(128) NOT NULL,
    tenant_code VARCHAR(128) NOT NULL,
    credential_hash CHAR(64) NOT NULL,
    generation VARCHAR(64) NOT NULL,
    denial_status INT NOT NULL DEFAULT 0,
    active_until DATETIME(3) NOT NULL,
    issued_at DATETIME(3) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (gateway_namespace, tenant_code, credential_hash),
    KEY ix_read_snapshot_barrier_until (active_until)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS read_snapshot_recovery_checkpoints (
    gateway_namespace VARCHAR(128) NOT NULL,
    instance_id VARCHAR(64) NOT NULL,
    clean TINYINT NOT NULL DEFAULT 0,
    recovery_epoch VARCHAR(64) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (gateway_namespace)
) ENGINE=InnoDB;
