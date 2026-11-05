CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，会话行标识',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话公开标识，全局唯一',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，租户隔离域，非空',
    owner_key CHAR(64) NOT NULL COMMENT '所有者隔离键（SHA-256 十六进制，不可逆，非展示名）',
    source VARCHAR(16) NOT NULL COMMENT '来源：office=办公，company=企业（枚举域）',
    cleared TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑清除标志：0=活跃，1=已清除（软清除不是物理删除，也不是审计保留策略）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间（活跃窗口判定依据）',
    PRIMARY KEY (id), UNIQUE KEY uk_conversation_id (conversation_id), KEY ix_conversation_owner (tenant_code, owner_key, source, cleared, update_time)
) ENGINE=InnoDB COMMENT='公开会话身份与活跃窗口：按租户、所有者、来源隔离';
CREATE TABLE IF NOT EXISTS conversation_turns (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，轮次行标识',
    turn_id VARCHAR(64) NOT NULL COMMENT '轮次公开标识，全局唯一',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属会话标识（归属经会话校验）',
    content TEXT NOT NULL COMMENT '用户正文（可能含敏感业务内容，未经应用加密）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '会话内幂等键，(conversation_id,idempotency_key) 唯一，重复提交不重复建轮',
    started_at DATETIME NOT NULL COMMENT '轮次开始时间',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_turn_id (turn_id), UNIQUE KEY uk_turn_idempotency (conversation_id, idempotency_key)
) ENGINE=InnoDB COMMENT='用户会话轮次：正文与幂等键；末轮撤回时随事件一并删除';
CREATE TABLE IF NOT EXISTS conversation_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，事件行标识',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属会话标识（归属经会话校验）',
    turn_id VARCHAR(64) NOT NULL COMMENT '所属轮次标识',
    sequence_no BIGINT UNSIGNED NOT NULL COMMENT '轮内事件序号（单位：个，从 1 递增），(conversation_id,turn_id,sequence_no) 唯一',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型（如 message.completed 等应用枚举域）',
    payload_json JSON NOT NULL COMMENT '事件负载（含 tenant、owner、source 时写入与读取同样核验）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '会话内幂等键，(conversation_id,idempotency_key) 唯一',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_conversation_turn_sequence (conversation_id, turn_id, sequence_no), UNIQUE KEY uk_conversation_idempotency (conversation_id, idempotency_key)
) ENGINE=InnoDB COMMENT='公开事件流：顺序与幂等由数据库约束保证，末轮撤回可删除';
CREATE TABLE IF NOT EXISTS action_drafts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，草案行标识',
    draft_id VARCHAR(64) NOT NULL COMMENT '行动草案公开标识，全局唯一',
    revision BIGINT UNSIGNED NOT NULL COMMENT '草案修订号（单位：次，非负），状态推进按期望修订 CAS',
    status VARCHAR(32) NOT NULL COMMENT '草案状态（应用枚举域：待确认、已解决、已取消、已中断等）',
    payload_json JSON NOT NULL COMMENT '草案负载（tenant、owner、source、conversationId、actions 等，含设备动作明细）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '提案幂等键，全局唯一，重复提交回放原草案',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_action_draft_id (draft_id), UNIQUE KEY uk_action_draft_idempotency (idempotency_key)
) ENGINE=InnoDB COMMENT='设备行动草案：确认前证据与状态机，修订推进受版本 CAS 保护';
CREATE TABLE IF NOT EXISTS action_receipts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，回执行标识',
    draft_id VARCHAR(64) NOT NULL COMMENT '所属行动草案标识（归属经草案核验）',
    draft_revision BIGINT UNSIGNED NOT NULL COMMENT '回执对应的草案修订号（单位：次，非负）',
    action_id VARCHAR(128) NOT NULL COMMENT '动作标识，(draft_id,action_id) 唯一，重复回执忽略不覆盖原证据',
    outcome VARCHAR(32) NOT NULL COMMENT '动作结果（应用枚举域：成功、失败等）',
    payload_json JSON NOT NULL COMMENT '回执负载（网关侧返回证据）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_action_receipt_action (draft_id, action_id)
) ENGINE=InnoDB COMMENT='设备行动回执：网关侧执行结果证据，追加不覆盖';
CREATE TABLE IF NOT EXISTS action_readbacks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，回读行标识',
    draft_id VARCHAR(64) NOT NULL COMMENT '所属行动草案标识（归属经草案核验）',
    draft_revision BIGINT UNSIGNED NOT NULL COMMENT '回读对应的草案修订号（单位：次，非负）',
    action_id VARCHAR(128) NOT NULL COMMENT '动作标识，(draft_id,action_id) 唯一，重复回读忽略',
    outcome VARCHAR(32) NOT NULL COMMENT '回读结论（应用枚举域）',
    observed_state_json JSON NOT NULL COMMENT '回读观测到的设备状态（敏感设备状态，禁止任意表浏览）',
    observed_at VARCHAR(64) NULL COMMENT '观测时刻（ISO-8601 字符串）；NULL=未带回时刻',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_action_readback_action (draft_id, action_id)
) ENGINE=InnoDB COMMENT='设备行动回读：动作后的实测状态证据，不作为已执行证明的替代';
CREATE TABLE IF NOT EXISTS task_facts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，任务行标识',
    task_id VARCHAR(64) NOT NULL COMMENT '任务公开标识，全局唯一',
    version BIGINT UNSIGNED NOT NULL COMMENT '任务版本（单位：次，非负），状态更新按期望版本 CAS',
    status VARCHAR(32) NOT NULL COMMENT '任务状态（应用枚举域：SCHEDULED、EXECUTING、COMPLETED 等）',
    payload_json JSON NOT NULL COMMENT '任务负载（tenant、owner、source、phases、reportRequest 等，含凭据键过滤）',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '任务幂等键，全局唯一，重复创建回放原任务',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_task_fact_id (task_id), UNIQUE KEY uk_task_fact_idempotency (idempotency_key)
) ENGINE=InnoDB COMMENT='任务当前事实：版本与状态机权威，更新受版本 CAS 保护';
CREATE TABLE IF NOT EXISTS report_facts (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，报告行标识',
    report_id VARCHAR(64) NOT NULL COMMENT '报告公开标识，全局唯一',
    source VARCHAR(32) NOT NULL COMMENT '报告来源（应用枚举域：会话产出、检查点产出等）',
    payload_json JSON NOT NULL COMMENT '报告快照（title、status、blocks、taskId 等；正文属敏感业务内容）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_report_fact_id (report_id)
) ENGINE=InnoDB COMMENT='企业报告事实：报告生成快照，首写为准不覆盖';
CREATE TABLE IF NOT EXISTS audit_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，事件行标识',
    event_id VARCHAR(64) NOT NULL COMMENT '事件公开标识，全局唯一',
    tenant_code VARCHAR(64) NOT NULL COMMENT '租户编码（历史宽度 64，与多数表 128 不同，暂不迁移）',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型（应用枚举域：proposed、resolved、cancelled 等）',
    payload_json JSON NOT NULL COMMENT '事件负载（行动草案审计视角；当前为 upsert，不是不可篡改审计）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_audit_event_id (event_id),
    KEY idx_audit_tenant_time (tenant_code, create_time, id)
) ENGINE=InnoDB COMMENT='行动草案审计事件：租户维度可查，upsert 语义，不等同于全平台不可篡改审计';
CREATE TABLE IF NOT EXISTS task_proposals (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，提案行标识',
    proposal_id VARCHAR(64) NOT NULL COMMENT '提案公开标识，全局唯一',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，租户隔离域，非空',
    owner_key CHAR(64) NOT NULL COMMENT '所有者隔离键（SHA-256 十六进制，不可逆，非展示名）',
    conversation_id VARCHAR(64) NOT NULL COMMENT '发起会话标识',
    turn_id VARCHAR(64) NOT NULL COMMENT '发起轮次标识',
    version BIGINT UNSIGNED NOT NULL COMMENT '提案版本（单位：次，非负），状态推进按期望版本 CAS',
    status VARCHAR(32) NOT NULL COMMENT '提案状态（应用枚举域：待确认、已接受、已拒绝、已过期等）',
    payload_json JSON NOT NULL COMMENT '提案负载（任务变更内容）',
    idempotency_key VARCHAR(256) NOT NULL COMMENT '提案幂等键，(tenant_code,owner_key,idempotency_key) 唯一',
    kind VARCHAR(32) NOT NULL DEFAULT 'TASK' COMMENT '提案类别：TASK=任务（预留扩展枚举域）',
    target_json JSON NULL COMMENT '操作目标对象；NULL=无目标（如新建）',
    before_json JSON NULL COMMENT '操作前状态快照；NULL=无前置状态',
    after_json JSON NULL COMMENT '操作后状态快照；NULL=无后置状态',
    operation VARCHAR(32) NOT NULL DEFAULT 'create' COMMENT '操作类型：create=新建，update=变更，cancel=取消等（枚举域）',
    source VARCHAR(16) NOT NULL DEFAULT 'office' COMMENT '来源：office=办公，company=企业（枚举域）',
    expires_at DATETIME NULL COMMENT '提案过期时间；NULL=不过期（过期是拒绝依据，不是删除策略）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_task_proposal_id (proposal_id),
    UNIQUE KEY uk_task_proposal_idempotency (tenant_code, owner_key, idempotency_key)
) ENGINE=InnoDB COMMENT='任务变更提案：确认前证据与目标前后快照，幂等键按租户与所有者隔离';
CREATE TABLE IF NOT EXISTS task_plans (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，计划行标识',
    plan_id VARCHAR(64) NOT NULL COMMENT '周期计划公开标识，全局唯一',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，租户隔离域，非空',
    owner_key CHAR(64) NOT NULL COMMENT '所有者隔离键（SHA-256 十六进制，不可逆，非展示名）',
    source VARCHAR(16) NOT NULL COMMENT '来源：office=办公，company=企业（枚举域）',
    version BIGINT UNSIGNED NOT NULL COMMENT '计划版本（单位：次，非负），更新按期望版本 CAS',
    status VARCHAR(32) NOT NULL COMMENT '计划状态（应用枚举域：ACTIVE、PAUSED、DELETED 等；DELETED 为逻辑删除）',
    next_at DATETIME NULL COMMENT '下次物化时间；NULL=暂停或按日期驱动',
    next_date DATE NULL COMMENT '下次物化日期；NULL=按时间驱动',
    repeat_json JSON NOT NULL COMMENT '重复规则（周期计划调度依据）',
    payload_json JSON NOT NULL COMMENT '计划负载（任务模板）',
    idempotency_key VARCHAR(256) NOT NULL COMMENT '计划幂等键，(tenant_code,owner_key,idempotency_key) 唯一',
    pause_reason VARCHAR(1024) NULL COMMENT '暂停原因（最长 1024 字符）；NULL=未暂停',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_task_plan_id (plan_id),
    UNIQUE KEY uk_task_plan_idempotency (tenant_code, owner_key, idempotency_key),
    KEY ix_task_plan_due (status, next_at)
) ENGINE=InnoDB COMMENT='周期计划：重复规则与物化指针，逻辑删除不是物理删除';
CREATE TABLE IF NOT EXISTS task_plan_occurrences (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，出现行标识',
    plan_id VARCHAR(64) NOT NULL COMMENT '所属周期计划标识，(plan_id,occurrence_date) 唯一',
    occurrence_date DATE NOT NULL COMMENT '出现日期（按日期幂等物化）',
    task_id VARCHAR(64) NULL COMMENT '物化出的任务标识；NULL=尚未物化',
    status VARCHAR(32) NOT NULL COMMENT '出现状态（应用枚举域：SKIPPED、SCHEDULED 等）',
    override_payload_json JSON NULL COMMENT '当日覆盖负载；NULL=按计划模板',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '出现版本（单位：次，非负，初始 1）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_task_plan_occurrence (plan_id, occurrence_date),
    KEY ix_task_plan_occurrence_status (status, occurrence_date)
) ENGINE=InnoDB COMMENT='周期计划出现日：按日期幂等，记录物化任务与覆盖';

CREATE TABLE IF NOT EXISTS task_phases (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，阶段行标识',
    phase_id VARCHAR(64) NOT NULL COMMENT '阶段公开标识，全局唯一',
    task_id VARCHAR(64) NOT NULL COMMENT '所属任务标识（归属经任务核验）',
    source VARCHAR(16) NOT NULL COMMENT '来源：office=办公，company=企业（枚举域）',
    version BIGINT UNSIGNED NOT NULL COMMENT '阶段版本（单位：次，非负），领取与完结按期望版本 CAS',
    status VARCHAR(32) NOT NULL COMMENT '阶段状态（应用枚举域：PENDING、EXECUTING、COMPLETED 等）',
    due_at DATETIME NOT NULL COMMENT '计划执行时间（调度扫描依据）',
    payload_json JSON NOT NULL COMMENT '阶段负载（原始定义与观测证据）',
    definition_json JSON NULL COMMENT '阶段定义（读数与调温模板等）；旧行回填自 payload_json，NULL=未迁移',
    result_json JSON NULL COMMENT '阶段执行结果（观测与设备处置证据）；NULL=未执行完',
    failure_reason VARCHAR(1024) NULL COMMENT '失败原因摘要（最长 1024 字符）；NULL=无失败',
    claimed_at DATETIME NULL COMMENT '领取时间；NULL=未领取',
    ended_at DATETIME NULL COMMENT '结束时间；NULL=未结束',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_task_phase_id (phase_id),
    KEY ix_task_phase_due (status, due_at), KEY ix_task_phase_task (task_id)
) ENGINE=InnoDB COMMENT='任务执行阶段：调度、领取与完结的状态机，定义与结果为过程证据';
CREATE TABLE IF NOT EXISTS task_notifications (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，通知行标识',
    notification_id VARCHAR(64) NOT NULL COMMENT '通知公开标识，全局唯一',
    task_id VARCHAR(64) NOT NULL COMMENT '所属任务标识，(task_id,recipient_key) 唯一',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，收件隔离域',
    recipient_key CHAR(64) NOT NULL COMMENT '收件人隔离键（所有者哈希，不可逆，非展示名）',
    source VARCHAR(16) NOT NULL COMMENT '来源：office=办公，company=企业（枚举域）',
    outcome VARCHAR(32) NOT NULL COMMENT '任务结局（应用枚举域：COMPLETED、PARTIAL、FAILED 等）',
    ended_at DATETIME NOT NULL COMMENT '任务结束时间（收件箱排序依据）',
    read_at DATETIME NULL COMMENT '阅读时间；NULL=未读',
    payload_json JSON NOT NULL COMMENT '通知负载（收件箱展示内容）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id), UNIQUE KEY uk_task_notification_id (notification_id),
    UNIQUE KEY uk_task_notification_recipient (task_id, recipient_key),
    KEY ix_task_notification_inbox (tenant_code, recipient_key, source, ended_at)
) ENGINE=InnoDB COMMENT='任务收件箱通知：按收件人隔离的结局投递，非外部送达审计';

CREATE TABLE IF NOT EXISTS region_seen (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '代理主键，认知行标识',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，租户隔离域，非空',
    owner_key CHAR(64) NOT NULL COMMENT '所有者隔离键（不可逆，非展示名）',
    region_id VARCHAR(128) NOT NULL COMMENT '区域标识（服务端绑定的办公区域），(tenant_code,owner_key,region_id) 唯一',
    seen_at DATETIME NOT NULL COMMENT '最近认知时间（只增不减）',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_region_seen_owner (tenant_code, owner_key, region_id),
    KEY ix_region_seen_lookup (tenant_code, owner_key, seen_at)
) ENGINE=InnoDB COMMENT='区域已读认知：按租户与所有者记录区域关注时间';
