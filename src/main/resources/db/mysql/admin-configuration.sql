CREATE TABLE IF NOT EXISTS admin_configuration_revisions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，修订行标识',
    revision BIGINT UNSIGNED NOT NULL COMMENT '配置修订号（单位：次，非负），全局唯一，单调递增',
    settings_ciphertext MEDIUMBLOB NOT NULL COMMENT '配置密文（AES 加密后的完整设置字节），不以明文回显',
    changed_fields_json JSON NOT NULL COMMENT '本次变更字段清单（密文外明文元数据）',
    actor VARCHAR(128) NOT NULL COMMENT '操作者标识（管理动作发起人）',
    changed_at DATETIME(6) NOT NULL COMMENT '变更生效时间（微秒精度）',
    create_time DATETIME(6) NOT NULL COMMENT '行创建时间（微秒精度）',
    update_time DATETIME(6) NOT NULL COMMENT '行更新时间（微秒精度）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_configuration_revision (revision)
) ENGINE=InnoDB COMMENT='管理配置修订史：密文保存设置快照，按修订号唯一追溯';
