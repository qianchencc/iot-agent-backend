CREATE TABLE IF NOT EXISTS device_candidate_sets (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    candidate_set_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    tenant_code VARCHAR(128) NOT NULL,
    owner_key CHAR(64) NOT NULL,
    source VARCHAR(16) NOT NULL,
    catalog_fingerprint CHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    complete TINYINT NOT NULL,
    total_matches INT NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_candidate_set_id (candidate_set_id),
    KEY ix_device_candidate_conversation (conversation_id, tenant_code, owner_key, source)
) ENGINE=InnoDB;
