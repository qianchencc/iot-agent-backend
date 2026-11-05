-- 设备候选集事实表与会话句柄序号表。
-- 本文件是唯一结构权威：新库直接按此建表；旧库由 SchemaMigrations 解析本文件生成
-- 受控升级（补列/回填句柄/加唯一键/补注释），新库与旧库增量升级结构一致。
-- 句柄分配：序号表每会话单行，事务内 UPSERT+自增+回读（O(1)，行锁串行化），
-- 与候选事实同事务提交，失败整体回滚不消耗序号；(conversation_id,handle) 唯一键兜底。
CREATE TABLE IF NOT EXISTS device_candidate_sets (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，候选集行标识',
    candidate_set_id VARCHAR(64) NOT NULL COMMENT '候选集全局唯一标识（服务端生成），唯一键',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属会话标识，句柄唯一域',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，租户隔离域，非空',
    owner_key CHAR(64) NOT NULL COMMENT '所有者隔离键（SHA-256 十六进制，不可逆，非展示名）',
    source VARCHAR(16) NOT NULL COMMENT '来源：office=办公，company=企业（枚举域）',
    catalog_fingerprint CHAR(64) NOT NULL COMMENT '授权设备目录指纹（SHA-256 十六进制），成员证据稳定性依据',
    payload_json JSON NOT NULL COMMENT '候选集完整快照（query、members、complete、totalMatches、handle 等），成员含控制物理绑定，禁止任意表浏览',
    complete TINYINT UNSIGNED NOT NULL COMMENT '筛选完成标志：0=未完成，1=完成（布尔规范 TINYINT UNSIGNED，升级前已校验值域 0/1）',
    total_matches INT NOT NULL COMMENT '目录匹配总数（单位：台，非负）',
    created_at VARCHAR(64) NOT NULL COMMENT '业务创建时间（ISO-8601 字符串）',
    handle VARCHAR(64) NULL COMMENT '会话内短句柄 sN，(conversation_id,handle) 唯一；旧版仅有完整 ID 的事实保留 NULL，不编造历史短引用；新句柄由序号表原子分配，已发布旧短句柄从 payload_json 原样回填',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    UNIQUE KEY uk_device_candidate_set_id (candidate_set_id),
    UNIQUE KEY uk_device_candidate_set_handle (conversation_id, handle),
    KEY ix_device_candidate_conversation (conversation_id, tenant_code, owner_key, source)
) ENGINE=InnoDB COMMENT='设备候选集事实：一次授权筛选的持久快照，成员仅服务端物化使用，不作为 HTTP 视图';

CREATE TABLE IF NOT EXISTS device_candidate_set_sequences (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，序号行标识',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话标识，每会话单行（唯一键），句柄序号隔离域',
    last_handle_no BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已分配的最大句柄序号（单位：个，非负，0=未分配）；迁移时按旧句柄初始化最大值',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间（最近一次序号推进）',
    UNIQUE KEY uk_device_candidate_sequence_conversation (conversation_id)
) ENGINE=InnoDB COMMENT='设备候选集会话句柄序号：行锁串行化分配，分配与候选事实同事务，失败回滚不消耗序号';
