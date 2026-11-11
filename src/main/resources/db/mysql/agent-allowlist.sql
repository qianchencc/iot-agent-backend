-- 旧身份三元组白名单（历史保留）：数据不迁移、不删除，任何代码不再读写。
CREATE TABLE IF NOT EXISTS company_agent_allowlist (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，白名单行标识',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，隔离域',
    identity_realm VARCHAR(255) NOT NULL COMMENT '身份域（认证来源域），三元组之一',
    principal_id VARCHAR(255) NOT NULL COMMENT '主体标识（域内唯一账号），三元组之一',
    added_by VARCHAR(128) NOT NULL COMMENT '添加人（管理操作者标识）',
    create_time DATETIME(6) NOT NULL COMMENT '行创建时间（微秒精度）',
    update_time DATETIME(6) NOT NULL COMMENT '行更新时间（微秒精度）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_agent_allowlist_identity (tenant_code, identity_realm, principal_id)
) ENGINE=InnoDB COMMENT='旧身份三元组白名单（历史保留）：数据不迁移、不删除，任何代码不再读写';
CREATE TABLE IF NOT EXISTS company_agent_allowlist_state (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，状态行标识',
    singleton TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '单行哨兵：恒为 1（唯一键），保证状态单行',
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '白名单修订号（单位：次，非负），变更推进',
    create_time DATETIME(6) NOT NULL COMMENT '行创建时间（微秒精度）',
    update_time DATETIME(6) NOT NULL COMMENT '行更新时间（微秒精度）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_agent_allowlist_state (singleton)
) ENGINE=InnoDB COMMENT='旧身份白名单修订状态（历史保留）：单行修订计数';
-- 手机号白名单：仅维护手机号；展示姓名关联本地已核验业务记录，不影响提前加白。
CREATE TABLE IF NOT EXISTS phone_allowlist (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，白名单行标识',
    mobile_number VARCHAR(32) NOT NULL COMMENT '手机号（全局唯一键），用于公司任务资格匹配',
    name VARCHAR(128) NULL COMMENT '历史手工备注保留；当前代码不读写，展示姓名关联本地业务记录',
    added_by VARCHAR(128) NOT NULL COMMENT '添加人（管理操作者标识）',
    create_time DATETIME(6) NOT NULL COMMENT '行创建时间（微秒精度）',
    update_time DATETIME(6) NOT NULL COMMENT '行更新时间（微秒精度）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone_allowlist_mobile (mobile_number)
) ENGINE=InnoDB COMMENT='手机号白名单：公司任务功能资格';
CREATE TABLE IF NOT EXISTS phone_allowlist_state (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，状态行标识',
    singleton TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '单行哨兵：恒为 1（唯一键），保证状态单行',
    revision BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '白名单修订号（单位：次，非负），变更推进',
    create_time DATETIME(6) NOT NULL COMMENT '行创建时间（微秒精度）',
    update_time DATETIME(6) NOT NULL COMMENT '行更新时间（微秒精度）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone_allowlist_state (singleton)
) ENGINE=InnoDB COMMENT='手机号白名单修订状态：单行修订计数';
