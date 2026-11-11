-- Local alarm facts share an exact authorized physical-device set; grants remain principal-local.
CREATE TABLE IF NOT EXISTS local_safety_grants (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '已核验主体来源的目录域',
    credential_generation CHAR(36) NOT NULL COMMENT '授权凭据代次',
    directory_version BIGINT UNSIGNED NOT NULL COMMENT '对应的完整目录版本',
    devices_json JSON NOT NULL COMMENT '专用安全授权设备和服务端绑定，不含凭据',
    create_time DATETIME(6) NOT NULL COMMENT '首次发布授权时间',
    update_time DATETIME(6) NOT NULL COMMENT '最近发布授权时间',
    UNIQUE KEY uk_local_safety_grant (scope_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_safety_sync (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '网关租户来源及同类授权物理设备集合',
    publication_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '事实发布版本',
    attempt_at DATETIME(6) NULL COMMENT '最近采集尝试时间',
    success_at DATETIME(6) NULL COMMENT '最近成功同步时间，不是告警发生时间',
    covered_until DATETIME(6) NULL COMMENT '增量采集的已覆盖时刻',
    full_success_at DATETIME(6) NULL COMMENT '最近完整历史发布时刻',
    next_due_at DATETIME(6) NOT NULL COMMENT '下一次采集或中断恢复时刻',
    full_next_due_at DATETIME(6) NOT NULL COMMENT '下一次上海深夜完整扫描时刻',
    failure_code VARCHAR(80) NULL COMMENT '脱敏采集失败类别',
    create_time DATETIME(6) NOT NULL COMMENT '首次登记时间',
    update_time DATETIME(6) NOT NULL COMMENT '最近同步状态变更时间',
    UNIQUE KEY uk_local_safety_sync (scope_key)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_safety_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '相同授权物理设备集合的共享事实域',
    event_key CHAR(64) NOT NULL COMMENT '台账设备来源记录和发生时间的身份摘要',
    ledger VARCHAR(16) NOT NULL COMMENT '来源台账gas或smoke或flooding',
    event_id VARCHAR(300) NOT NULL COMMENT '真实来源记录ID，字符串保留',
    device_id VARCHAR(300) NOT NULL COMMENT '真实来源设备ID',
    recorded_device_name VARCHAR(512) NOT NULL COMMENT '告警记录保存的设备名称',
    occurred_at DATETIME(6) NOT NULL COMMENT '有效告警发生时间，不以同步时间补造',
    order_key VARCHAR(700) NOT NULL COMMENT '与原台账游标一致的稳定排序键',
    alarm BOOLEAN NULL COMMENT '来源告警标记，缺失保持未知',
    report BOOLEAN NULL COMMENT '来源上报标记，不代表处置状态',
    synchronized_at DATETIME(6) NOT NULL COMMENT '事实最近完整提交时间',
    create_time DATETIME(6) NOT NULL COMMENT '首次本地保存时间',
    update_time DATETIME(6) NOT NULL COMMENT '来源修订保存时间',
    UNIQUE KEY uk_local_safety_event (scope_key,event_key),
    KEY ix_local_safety_window (scope_key,occurred_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS local_safety_batches (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键',
    run_id CHAR(36) NOT NULL COMMENT '不可见采集批次',
    scope_key CHAR(64) NOT NULL COMMENT '共享事实域',
    event_key CHAR(64) NOT NULL COMMENT '真实来源记录身份摘要',
    ledger VARCHAR(16) NOT NULL COMMENT '来源台账',
    event_id VARCHAR(300) NOT NULL COMMENT '真实来源记录ID',
    device_id VARCHAR(300) NOT NULL COMMENT '来源设备ID',
    recorded_device_name VARCHAR(512) NOT NULL COMMENT '记录名称',
    occurred_at DATETIME(6) NOT NULL COMMENT '真实有效发生时间',
    order_key VARCHAR(700) NOT NULL COMMENT '稳定排序键',
    alarm BOOLEAN NULL COMMENT '来源告警标记',
    report BOOLEAN NULL COMMENT '来源上报标记',
    create_time DATETIME(6) NOT NULL COMMENT '暂存时间',
    update_time DATETIME(6) NOT NULL COMMENT '暂存更新时间',
    UNIQUE KEY uk_local_safety_batch (run_id,event_key)
) ENGINE=InnoDB;
