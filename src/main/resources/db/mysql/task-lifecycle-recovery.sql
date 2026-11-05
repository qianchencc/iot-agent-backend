-- Task lifecycle recovery (tickets 10-13). Additive, task-owned schema.
-- 本文件是唯一结构权威：新库直接按此建表；旧库（自然/复合主键）由 SchemaMigrations
-- 解析本文件生成受控升级（代理主键化、自然键降级唯一键、补注释），每任务一行与
-- 每（任务,设备）一次的幂等语义不变。task_phases 的阶段三列在 agent-facts.sql 基线中，
-- 由任务切片防御性补列升级（见 SchemaMigrations.ensureTaskLifecycle）。
CREATE TABLE IF NOT EXISTS task_execution_identities (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，托管行标识',
    owner_id VARCHAR(64) NOT NULL COMMENT '托管对象标识（任务 ID 或计划 ID，解释由 owner_kind 决定），唯一键',
    owner_kind VARCHAR(16) NOT NULL COMMENT '托管对象类别：TASK=任务，PLAN=周期计划（枚举域）',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，租户隔离域，非空',
    owner_key CHAR(64) NOT NULL COMMENT '所有者隔离键（SHA-256 十六进制，不可逆，非展示名）',
    payload MEDIUMTEXT NOT NULL COMMENT '执行身份密文（AES-256-GCM 编码，含 IV 与认证标签），不允许为空，禁止经管理端回显',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    UNIQUE KEY uk_task_execution_identity_owner (owner_id),
    KEY ix_task_execution_identity_kind (owner_kind)
) ENGINE=InnoDB COMMENT='任务或计划执行身份托管：仅存 AES-256-GCM 密文，密钥来自 TASK_CREDENTIAL_KEY，不以明文回显';

CREATE TABLE IF NOT EXISTS task_report_attempts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，重试行标识',
    task_id VARCHAR(64) NOT NULL COMMENT '任务标识，唯一键，重试状态按任务维度幂等（ON DUPLICATE KEY 更新）',
    status VARCHAR(16) NOT NULL COMMENT '最近尝试状态：FAILED=失败待重试（枚举域）',
    attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试次数（单位：次；达到 5 次后放弃）',
    last_error VARCHAR(1024) NULL COMMENT '最近一次失败摘要（最长 1024 字符）；NULL=尚无失败记录',
    next_attempt_at DATETIME NULL COMMENT '下次允许尝试时间；NULL=立即可试',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    UNIQUE KEY uk_task_report_attempt_task (task_id)
) ENGINE=InnoDB COMMENT='任务报告补投重试状态：每个待补投任务一行，补投成功后删除';

CREATE TABLE IF NOT EXISTS task_notification_attempts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，重试行标识',
    task_id VARCHAR(64) NOT NULL COMMENT '任务标识，唯一键，重试状态按任务维度幂等（ON DUPLICATE KEY 更新）',
    status VARCHAR(16) NOT NULL COMMENT '最近尝试状态：PENDING=待补投（枚举域）',
    attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试次数（单位：次；达到 5 次后放弃）',
    last_error VARCHAR(1024) NULL COMMENT '最近一次失败摘要（最长 1024 字符）；NULL=尚无失败记录',
    next_attempt_at DATETIME NULL COMMENT '下次允许尝试时间；NULL=立即可试',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    UNIQUE KEY uk_task_notification_attempt_task (task_id)
) ENGINE=InnoDB COMMENT='任务通知补投重试状态：每个待补投任务一行，补投成功后删除';

-- Ticket 10/12: durable per-task device dispatch facts. One row per (task, device)
-- is written atomically immediately before each single AC adjust dispatch; yields
-- are recorded with kind='yielded'. The unique key (task_id, device_id) makes a
-- repeat dispatch by a later checkpoint, concurrent scanner or restarted process
-- impossible. SchemaMigrations seeds historical adjust-mode checkpoint results
-- (TaskSchemaInitializer) so existing once-per-task facts are preserved.
CREATE TABLE IF NOT EXISTS task_device_dispatch_reservations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，预约行标识',
    task_id VARCHAR(64) NOT NULL COMMENT '任务标识，与 device_id 联合唯一（每任务每设备一次）',
    device_id VARCHAR(64) NOT NULL COMMENT '设备标识（网关侧设备 ID）',
    phase_id VARCHAR(64) NOT NULL COMMENT '首次产生该事实的阶段标识（索引列）',
    kind VARCHAR(16) NOT NULL COMMENT '预约类别：attempted=实际下发，yielded=有意让行（枚举域）',
    reserved_at DATETIME NOT NULL COMMENT '预约事实发生时间（阶段下发或结束时刻）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    UNIQUE KEY uk_task_dispatch_reservation (task_id, device_id),
    KEY ix_task_dispatch_reservation_phase (phase_id)
) ENGINE=InnoDB COMMENT='任务设备调度预约：每（任务,设备）至多一次实际下发或有意让行，防止重复调温';
