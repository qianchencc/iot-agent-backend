-- Task lifecycle recovery (tickets 10-13). Additive, task-owned schema.
-- TaskSchemaInitializer applies the same statements defensively at startup.
ALTER TABLE task_phases ADD COLUMN definition_json JSON NULL;
ALTER TABLE task_phases ADD COLUMN result_json JSON NULL;
ALTER TABLE task_phases ADD COLUMN failure_reason VARCHAR(1024) NULL;
UPDATE task_phases SET definition_json=payload_json WHERE definition_json IS NULL;

CREATE TABLE IF NOT EXISTS task_execution_identities (
    owner_id VARCHAR(64) NOT NULL,
    owner_kind VARCHAR(16) NOT NULL,
    tenant_code VARCHAR(128) NOT NULL,
    owner_key CHAR(64) NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (owner_id),
    KEY ix_task_execution_identity_kind (owner_kind)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_report_attempts (
    task_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1024) NULL,
    next_attempt_at DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (task_id)
) ENGINE=InnoDB;
