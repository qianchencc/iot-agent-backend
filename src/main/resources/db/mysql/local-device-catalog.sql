-- Company-wide display data. Principal-scoped grants remain in local_company_*.
CREATE TABLE IF NOT EXISTS local_device_catalog_scopes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    catalog_key CHAR(64) NOT NULL COMMENT '上游实例及租户展示域摘要，不含用户',
    gateway_instance CHAR(64) NOT NULL COMMENT '上游实例摘要',
    tenant_code VARCHAR(128) NOT NULL COMMENT '已核验租户展示范围',
    batch_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '完整目录发布版本',
    state VARCHAR(24) NOT NULL COMMENT 'PREPARING或SYNCING或READY或FAILED',
    last_attempt_at DATETIME(3) NULL COMMENT '最近同步尝试时间',
    last_success_at DATETIME(3) NULL COMMENT '最近完整目录提交时间，不是设备采样时间',
    next_due_at DATETIME(3) NOT NULL COMMENT '下一次全量同步时间',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '登记时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近状态更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_device_catalog_scope (catalog_key)
) ENGINE=InnoDB COMMENT='公司共享设备目录同步进度，无授权及控制绑定';

CREATE TABLE IF NOT EXISTS local_device_catalog (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    catalog_key CHAR(64) NOT NULL COMMENT '公司展示域摘要',
    source_device_id VARCHAR(255) NOT NULL COMMENT '底层设备ID，不可用于替代授权业务ID',
    device_kind VARCHAR(64) NOT NULL COMMENT '归一化七类设备类型',
    device_name VARCHAR(512) NOT NULL COMMENT '设备展示名称',
    install_address TEXT NOT NULL COMMENT '已有安装位置，未知为空字符串',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地记录建立时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '完整批次提交时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_device_catalog_entry (catalog_key,device_kind,source_device_id)
) ENGINE=InnoDB COMMENT='公司完整展示目录，不授予访问及控制权限';
