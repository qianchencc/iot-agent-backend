-- 结构迁移登记表：迁移键唯一，保证旧库升级幂等可恢复、可重复启动。
-- 本文件是唯一结构权威；旧库（migration_key 主键、TIMESTAMP(3)、无审计列）由
-- SchemaMigrations 解析本文件生成受控升级；旧行 create_time/update_time 回填自
-- applied_at（真实迁移生效时间），保留原值不伪造迁移时刻。
CREATE TABLE IF NOT EXISTS storage_migrations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，迁移登记行标识',
    migration_key VARCHAR(128) NOT NULL COMMENT '迁移键，全局唯一；INSERT IGNORE 依赖唯一键保证同键迁移只执行一次',
    applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移实际生效时间（毫秒精度，服务器本地时间）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行创建时间（毫秒精度）；旧行回填自 applied_at，保留原值不伪造',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行更新时间（毫秒精度）；旧行回填自 applied_at，保留原值不伪造',
    UNIQUE KEY uk_storage_migration_key (migration_key)
) ENGINE=InnoDB COMMENT='迁移登记表：已执行的结构迁移键与生效时间，保证旧库升级幂等可恢复、可重复启动';
