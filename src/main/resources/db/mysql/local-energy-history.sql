CREATE TABLE IF NOT EXISTS local_energy_sync (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key CHAR(64) NOT NULL,
    credential_generation VARCHAR(64) NOT NULL,
    grant_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    grants_published_at DATETIME(6) NULL,
    current_attempt_at DATETIME(6) NULL,
    current_success_at DATETIME(6) NULL,
    current_failure_code VARCHAR(80) NULL,
    full_attempt_at DATETIME(6) NULL,
    full_success_at DATETIME(6) NULL,
    full_covered_until DATETIME(6) NULL,
    full_next_due_at DATETIME(6) NOT NULL,
    full_state VARCHAR(20) NOT NULL DEFAULT 'PREPARING',
    full_failure_code VARCHAR(80) NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    UNIQUE KEY uk_local_energy_sync (scope_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_energy_meters (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key CHAR(64) NOT NULL,
    meter_key CHAR(64) NOT NULL,
    source_meter_id VARCHAR(255) NOT NULL,
    meter_name VARCHAR(512) NOT NULL,
    install_address VARCHAR(1024) NOT NULL,
    location_paths_json JSON NOT NULL,
    metadata_json JSON NOT NULL,
    credential_generation VARCHAR(64) NOT NULL,
    grant_version BIGINT UNSIGNED NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    UNIQUE KEY uk_local_energy_meter (scope_key,meter_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_energy_samples (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key CHAR(64) NOT NULL,
    sample_key CHAR(64) NOT NULL,
    meter_key CHAR(64) NOT NULL,
    source_meter_id VARCHAR(255) NOT NULL,
    source_record_id VARCHAR(255) NULL,
    source_id_missing BOOLEAN NOT NULL,
    read_time DATETIME(6) NOT NULL,
    epf DECIMAL(65,30) NULL,
    epz DECIMAL(65,30) NULL,
    total_electric DECIMAL(65,30) NULL,
    power_factor DECIMAL(65,30) NULL,
    conflicted BOOLEAN NOT NULL DEFAULT FALSE,
    credential_generation VARCHAR(64) NOT NULL,
    synchronized_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    UNIQUE KEY uk_local_energy_sample (scope_key,sample_key),
    KEY ix_local_energy_window (scope_key,meter_key,read_time)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_energy_sample_batches (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    scope_key CHAR(64) NOT NULL,
    sample_key CHAR(64) NOT NULL,
    meter_key CHAR(64) NOT NULL,
    source_meter_id VARCHAR(255) NOT NULL,
    source_record_id VARCHAR(255) NULL,
    source_id_missing BOOLEAN NOT NULL,
    read_time DATETIME(6) NOT NULL,
    epf DECIMAL(65,30) NULL,
    epz DECIMAL(65,30) NULL,
    total_electric DECIMAL(65,30) NULL,
    power_factor DECIMAL(65,30) NULL,
    conflicted BOOLEAN NOT NULL DEFAULT FALSE,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    UNIQUE KEY uk_local_energy_batch_sample (run_id,sample_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_energy_coverage (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key CHAR(64) NOT NULL,
    meter_key CHAR(64) NOT NULL,
    credential_generation VARCHAR(64) NOT NULL,
    covered_until DATETIME(6) NOT NULL,
    synchronized_at DATETIME(6) NOT NULL,
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    UNIQUE KEY uk_local_energy_coverage (scope_key,meter_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_energy_requests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    scope_key CHAR(64) NOT NULL,
    request_key CHAR(64) NOT NULL,
    meter_key CHAR(64) NOT NULL,
    range_from DATETIME(6) NOT NULL,
    range_to DATETIME(6) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PREPARING',
    create_time DATETIME(6) NOT NULL,
    update_time DATETIME(6) NOT NULL,
    UNIQUE KEY uk_local_energy_request (scope_key,request_key)
) ENGINE=InnoDB;
