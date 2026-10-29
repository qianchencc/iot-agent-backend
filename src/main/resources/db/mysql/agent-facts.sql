CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(128) NOT NULL,
    owner_key CHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL,
    cleared TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_conversation_id (conversation_id), KEY ix_conversation_owner (tenant_code, owner_key, source, cleared, update_time)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS conversation_turns (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    turn_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    started_at DATETIME NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_turn_id (turn_id), UNIQUE KEY uk_turn_idempotency (conversation_id, idempotency_key)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS conversation_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    conversation_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_conversation_turn_sequence (conversation_id, turn_id, sequence_no), UNIQUE KEY uk_conversation_idempotency (conversation_id, idempotency_key)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS action_drafts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    draft_id VARCHAR(64) NOT NULL,
    revision BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_action_draft_id (draft_id), UNIQUE KEY uk_action_draft_idempotency (idempotency_key)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS action_receipts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    draft_id VARCHAR(64) NOT NULL,
    draft_revision BIGINT UNSIGNED NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_action_receipt_action (draft_id, action_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS action_readbacks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    draft_id VARCHAR(64) NOT NULL,
    draft_revision BIGINT UNSIGNED NOT NULL,
    action_id VARCHAR(128) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    observed_state_json JSON NOT NULL,
    observed_at VARCHAR(64) NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_action_readback_action (draft_id, action_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS task_facts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL,
    version BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_task_fact_id (task_id), UNIQUE KEY uk_task_fact_idempotency (idempotency_key)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS report_facts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    report_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_report_fact_id (report_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS audit_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_audit_event_id (event_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS task_proposals (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    proposal_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(128) NOT NULL,
    owner_key CHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    turn_id VARCHAR(64) NOT NULL,
    version BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_task_proposal_id (proposal_id),
    UNIQUE KEY uk_task_proposal_idempotency (tenant_code, owner_key, idempotency_key)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS task_plans (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plan_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(128) NOT NULL,
    owner_key CHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL,
    version BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    next_at DATETIME NULL,
    next_date DATE NULL,
    repeat_json JSON NOT NULL,
    payload_json JSON NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_task_plan_id (plan_id),
    UNIQUE KEY uk_task_plan_idempotency (tenant_code, owner_key, idempotency_key),
    KEY ix_task_plan_due (status, next_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS task_plan_occurrences (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    plan_id VARCHAR(64) NOT NULL,
    occurrence_date DATE NOT NULL,
    task_id VARCHAR(64) NULL,
    status VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_task_plan_occurrence (plan_id, occurrence_date),
    KEY ix_task_plan_occurrence_status (status, occurrence_date)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_phases (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    phase_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL,
    version BIGINT UNSIGNED NOT NULL,
    status VARCHAR(32) NOT NULL,
    due_at DATETIME NOT NULL,
    payload_json JSON NOT NULL,
    claimed_at DATETIME NULL,
    ended_at DATETIME NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_task_phase_id (phase_id),
    KEY ix_task_phase_due (status, due_at), KEY ix_task_phase_task (task_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS task_notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    notification_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(128) NOT NULL,
    recipient_key CHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    ended_at DATETIME NOT NULL,
    read_at DATETIME NULL,
    payload_json JSON NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_task_notification_id (notification_id),
    UNIQUE KEY uk_task_notification_recipient (task_id, recipient_key),
    KEY ix_task_notification_inbox (tenant_code, recipient_key, source, ended_at)
) ENGINE=InnoDB;
