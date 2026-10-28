CREATE TABLE conversation_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_sequence (conversation_id, sequence_no),
    UNIQUE KEY uk_conversation_idempotency (conversation_id, idempotency_key)
) ENGINE=InnoDB;

CREATE TABLE action_drafts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    draft_id VARCHAR(64) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_action_draft_id (draft_id),
    UNIQUE KEY uk_action_draft_idempotency (idempotency_key)
) ENGINE=InnoDB;

CREATE TABLE task_facts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL,
    version BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_fact_id (task_id),
    UNIQUE KEY uk_task_fact_idempotency (idempotency_key)
) ENGINE=InnoDB;

CREATE TABLE report_facts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_fact_id (report_id)
) ENGINE=InnoDB;

CREATE TABLE audit_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_event_id (event_id)
) ENGINE=InnoDB;
