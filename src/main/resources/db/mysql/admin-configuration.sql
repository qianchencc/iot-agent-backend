CREATE TABLE IF NOT EXISTS admin_configuration_revisions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    revision BIGINT UNSIGNED NOT NULL,
    settings_ciphertext MEDIUMBLOB NOT NULL,
    changed_fields_json JSON NOT NULL,
    actor VARCHAR(128) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_configuration_revision (revision)
) ENGINE=InnoDB;
