-- Current observations only. Source sampling time is never replaced by collection time.
-- Directory authorization remains authoritative; retained observations never grant visibility.
CREATE TABLE IF NOT EXISTS local_device_states (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '来源实例及已核验主体来源隔离域',
    device_key CHAR(64) NOT NULL COMMENT '设备类型与来源ID摘要',
    source_device_id VARCHAR(255) NOT NULL COMMENT '来源设备ID',
    device_kind VARCHAR(64) NOT NULL COMMENT '来源设备类型',
    value_shape VARCHAR(16) NOT NULL COMMENT 'UNKNOWN或BOOLEAN或OBJECT',
    boolean_value TINYINT NULL COMMENT '照明开关或视频在线，未知不补false',
    power_state VARCHAR(32) NULL COMMENT '空调电源状态',
    temperature DECIMAL(65,30) NULL COMMENT '空调温度',
    target_temperature DECIMAL(65,30) NULL COMMENT '空调目标温度',
    mode_value VARCHAR(64) NULL COMMENT '空调模式',
    fan_speed VARCHAR(64) NULL COMMENT '空调风速',
    current_a DECIMAL(65,30) NULL COMMENT '电表A相电流',
    current_b DECIMAL(65,30) NULL COMMENT '电表B相电流',
    current_c DECIMAL(65,30) NULL COMMENT '电表C相电流',
    active_power DECIMAL(65,30) NULL COMMENT '电表当前有功功率',
    environment_temperature DECIMAL(65,30) NULL COMMENT '环境当前温度',
    humidity DECIMAL(65,30) NULL COMMENT '环境当前湿度',
    pm25 DECIMAL(65,30) NULL COMMENT '来源pm25值，不推断单位',
    pm10 DECIMAL(65,30) NULL COMMENT '来源pm10值，业务映射保留既有口径',
    pm101 DECIMAL(65,30) NULL COMMENT '来源pm101值，业务映射保留既有口径',
    observation_origin VARCHAR(32) NULL COMMENT 'device或shadow等实际来源，shadow不是设备测量',
    extensions_json JSON NOT NULL COMMENT '非核心稀疏扩展字段，不存整份响应',
    observed_at DATETIME(6) NULL COMMENT '来源明确提供的采样时间，未知为null',
    synchronized_at DATETIME(6) NOT NULL COMMENT '该状态最后成功采集提交时间',
    credential_generation CHAR(36) NOT NULL COMMENT '采集提交时核验的凭据代次',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '本地事实建立时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '本地事实更新时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_device_state (scope_key,device_key)
) ENGINE=InnoDB COMMENT='主体隔离的当前设备观测；旧观测不作真实控制证据';

CREATE TABLE IF NOT EXISTS local_device_state_sync (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '已核验主体来源隔离域',
    state VARCHAR(24) NOT NULL COMMENT 'PREPARING或READY或FAILED',
    batch_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '完整采集提交版本',
    credential_generation CHAR(36) NOT NULL COMMENT '最近尝试的凭据代次',
    last_attempt_at DATETIME(6) NOT NULL COMMENT '最近采集尝试时间',
    last_success_at DATETIME(6) NULL COMMENT '最近完整采集提交时间',
    failure_code VARCHAR(64) NULL COMMENT '脱敏失败类别，不保存上游正文',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '状态登记时间',
    update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '进度更新时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_device_state_sync (scope_key)
) ENGINE=InnoDB COMMENT='状态采集进度，与目录成功状态分开；失败不推进成功时间';
