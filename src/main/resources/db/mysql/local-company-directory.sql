-- LDS-20261106: Java-owned, principal-isolated company catalog. No credentials in Redis.
-- Source identity: gateway instance / verified tenant+owner / source / kind+source ID.
-- Catalog replacement and publication occur in one transaction. No historical business facts are deleted.
CREATE TABLE IF NOT EXISTS local_sync_scopes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '来源实例及核验主体来源的数据域摘要',
    gateway_instance CHAR(64) NOT NULL COMMENT '上游实例摘要，不含凭据',
    tenant_code VARCHAR(128) NOT NULL COMMENT '已核验租户',
    owner_key CHAR(64) NOT NULL COMMENT '已核验稳定主体摘要',
    source VARCHAR(16) NOT NULL COMMENT '授权来源company或office',
    data_kind VARCHAR(32) NOT NULL COMMENT '同步数据类别',
    credential_hash CHAR(64) NOT NULL COMMENT '凭据识别摘要，不作业务对象ID',
    credential_cipher LONGTEXT NOT NULL COMMENT '独立AES-GCM同步凭据，禁止展示',
    credential_generation CHAR(36) NOT NULL COMMENT '凭据代次，拒绝旧在途发布',
    authorization_generation CHAR(36) NOT NULL COMMENT '既有授权屏障代次，撤权及重新准入后须重新采集',
    published_generation CHAR(36) NULL COMMENT '当前完整批次通过的凭据代次',
    batch_version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已发布完整批次版本',
    state VARCHAR(24) NOT NULL COMMENT 'PREPARING或READY或FAILED或DENIED',
    denial_status INT NOT NULL DEFAULT 0 COMMENT '上游明确拒绝状态，仅0或401或403',
    failure_code VARCHAR(64) NULL COMMENT '脱敏失败类别，不保存上游正文',
    source_observed_at DATETIME(3) NULL COMMENT '来源明确提供的采样时间，未知为null',
    last_attempt_at DATETIME(3) NULL COMMENT '最近同步尝试时间',
    last_success_at DATETIME(3) NULL COMMENT '最近完整批次提交时间',
    next_due_at DATETIME(3) NOT NULL COMMENT '下一次同步或身份重核时间',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '登记时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近登记或同步状态变更时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_sync_scope (scope_key),
    KEY ix_local_sync_due (gateway_instance,data_kind,next_due_at)
) ENGINE=InnoDB COMMENT='独立同步登记及完整覆盖状态，页面关闭或缓存失效不删除';

CREATE TABLE IF NOT EXISTS local_company_devices (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '已核验主体隔离域',
    device_key CHAR(64) NOT NULL COMMENT '设备类型和来源ID的摘要',
    source_device_id VARCHAR(255) NOT NULL COMMENT '客户来源设备ID，原样保留',
    device_kind VARCHAR(64) NOT NULL COMMENT '设备类型',
    device_name VARCHAR(512) NOT NULL COMMENT '设备名称，运营数据，按主体隔离',
    install_address TEXT NOT NULL COMMENT '安装位置，未知为空',
    observation_only TINYINT UNSIGNED NOT NULL COMMENT '仅观察设备标记',
    control_ref TEXT NULL COMMENT '仅服务端使用的控制引用',
    office_device_ref TEXT NULL COMMENT '办公室设备引用',
    hub_device_id TEXT NULL COMMENT '服务端物理绑定ID',
    smart_area_code TEXT NULL COMMENT '智能区域绑定',
    metadata_json JSON NOT NULL COMMENT '适配器稀疏扩展属性，不存整份HTTP正文',
    catalog_fingerprint CHAR(64) NOT NULL COMMENT 'AuthorizedDevice既有目录指纹',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地记录建立时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地记录同步时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_company_device (scope_key,device_key)
) ENGINE=InnoDB COMMENT='主体独立公司目录正文，不跨主体共享授权结果';

CREATE TABLE IF NOT EXISTS local_company_grants (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '已核验主体隔离域',
    device_key CHAR(64) NOT NULL COMMENT '设备类型和来源ID摘要',
    batch_version BIGINT UNSIGNED NOT NULL COMMENT '完整目录发布版本',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '授权批次建立时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '授权批次同步时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_company_grant (scope_key,device_key)
) ENGINE=InnoDB COMMENT='公司总目录可见授权，不代替能耗安全视频专用授权';

CREATE TABLE IF NOT EXISTS local_company_locations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '已核验主体隔离域',
    device_key CHAR(64) NOT NULL COMMENT '设备身份摘要',
    path_index INT NOT NULL COMMENT '同一设备位置路径序号',
    segment_index INT NOT NULL COMMENT '位置路径层级序号',
    segment_name VARCHAR(512) NOT NULL COMMENT '来源位置层级名称',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '关系建立时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '关系同步时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_company_location (scope_key,device_key,path_index,segment_index)
) ENGINE=InnoDB COMMENT='公司设备位置层级关系，非独立授权事实';

CREATE TABLE IF NOT EXISTS local_company_offices (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '已核验主体隔离域',
    device_key CHAR(64) NOT NULL COMMENT '设备身份摘要',
    office_id VARCHAR(255) NOT NULL COMMENT '来源办公室ID，不以名称替代',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '关系建立时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '关系同步时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_company_office (scope_key,device_key,office_id)
) ENGINE=InnoDB COMMENT='公司设备办公室关联，不代表办公室访问授权';
