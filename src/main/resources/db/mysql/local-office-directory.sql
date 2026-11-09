-- LDS-20261106 Office: membership elementId/id is the source region identity.
-- Device membership uses local_company_offices, isolated by the same source-qualified scope_key.
-- Regions, devices, membership, grants and batch publication are committed in one transaction.
CREATE TABLE IF NOT EXISTS local_office_regions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键',
    scope_key CHAR(64) NOT NULL COMMENT '来源实例及核验主体Office授权域',
    source_region_id VARCHAR(255) NOT NULL COMMENT '成员接口elementId或id，不以名称替代',
    region_name VARCHAR(512) NOT NULL COMMENT '来源区域名称，空区域也保存',
    batch_version BIGINT UNSIGNED NOT NULL COMMENT '完整目录发布版本',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地记录建立时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地记录同步时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_local_office_region (scope_key,source_region_id)
) ENGINE=InnoDB COMMENT='Office成员区域，与同批目录关系原子发布';
