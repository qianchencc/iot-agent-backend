-- Pi 原生会话日志四表（Java 权威，MySQL 持久化）。
-- 本文件是唯一结构权威：新库直接按此建表；旧库（复合主键、id 非主键、TIMESTAMP 审计列）
-- 由 SchemaMigrations 解析本文件生成受控升级（代理主键化、自然键降级唯一键、审计列
-- TIMESTAMP 转 DATETIME、补注释），租约与 commit 幂等语义不变，升级后结构一致。
CREATE TABLE IF NOT EXISTS pi_session_scopes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，范围行标识',
    scope_key VARCHAR(64) NOT NULL COMMENT '会话范围键（由租户/所有者/来源派生的稳定标识），全局唯一',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，租户隔离域，非空',
    owner_key VARCHAR(128) NOT NULL COMMENT '所有者隔离键，不可逆，非展示名',
    source VARCHAR(16) NOT NULL COMMENT '来源：office=办公，company=企业（枚举域）',
    conversation_id VARCHAR(128) NOT NULL COMMENT '关联会话标识',
    writer_id VARCHAR(64) NULL COMMENT '当前写者标识；NULL=无活跃写者',
    run_id VARCHAR(128) NULL COMMENT '写者运行标识；NULL=无活跃写者',
    server_id VARCHAR(64) NULL COMMENT '持写者服务器实例标识（UUID）；NULL=无活跃写者',
    binding_json LONGTEXT NULL COMMENT '写者绑定校验 JSON（runId、conversationId、turnId、toolContextId）；NULL=未持有租约',
    epoch BIGINT NOT NULL DEFAULT 0 COMMENT '会话纪元，写者每次接管递增（单位：次，非负）',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '会话内容版本，每次提交递增（单位：次，非负）',
    lease_until BIGINT NOT NULL DEFAULT 0 COMMENT '写者租约截止（Unix 毫秒时间戳，非负；0=无租约）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行创建时间（毫秒精度；旧库由 TIMESTAMP(3) 规范化转换，保留原显示值）',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行更新时间（毫秒精度；旧库由 TIMESTAMP(3) 规范化转换，保留原显示值）',
    UNIQUE KEY uk_pi_session_scope (scope_key)
) ENGINE=InnoDB COMMENT='Pi 会话范围：每 scope 单行写者租约与纪元，提交幂等由 pi_session_commits 保证';

CREATE TABLE IF NOT EXISTS pi_session_documents (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，对象行标识',
    scope_key VARCHAR(64) NOT NULL COMMENT '会话范围键（唯一键首列）',
    document_key VARCHAR(64) NOT NULL COMMENT '对象键（完整路径的 SHA-256 十六进制），与 scope_key 联合唯一',
    parent_key VARCHAR(64) NULL COMMENT '父目录键；NULL=根目录 /sessions',
    path_json LONGTEXT NOT NULL COMMENT '完整路径（JSON 字符串编码）',
    kind VARCHAR(16) NOT NULL COMMENT '对象类别：file=文件，directory=目录（枚举域）',
    next_chunk BIGINT NOT NULL DEFAULT 0 COMMENT '已写入分块数（单位：块，非负；读完整性校验依据）',
    content_size BIGINT NOT NULL DEFAULT 0 COMMENT '内容 UTF-8 字节数（单位：字节，非负）',
    modified_at BIGINT NOT NULL COMMENT '最近修改时间（Unix 毫秒时间戳，非负）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行创建时间（毫秒精度；旧库由 TIMESTAMP(3) 规范化转换，保留原显示值）',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行更新时间（毫秒精度；旧库由 TIMESTAMP(3) 规范化转换，保留原显示值）',
    UNIQUE KEY uk_pi_session_document (scope_key, document_key)
) ENGINE=InnoDB COMMENT='Pi 会话虚拟文件与目录元数据；文件内容由 pi_session_chunks 分块存储';

CREATE TABLE IF NOT EXISTS pi_session_chunks (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，分块行标识',
    scope_key VARCHAR(64) NOT NULL COMMENT '会话范围键（唯一键首列）',
    document_key VARCHAR(64) NOT NULL COMMENT '所属对象键（路径 SHA-256 十六进制）',
    chunk_no BIGINT NOT NULL COMMENT '分块序号，从 0 起连续（单位：块序，非负），(scope_key,document_key,chunk_no) 唯一',
    content_json LONGTEXT NOT NULL COMMENT '分块内容（JSON 字符串编码，可含多行文本）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行创建时间（毫秒精度；旧库由 TIMESTAMP(3) 规范化转换，保留原显示值）',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行更新时间（毫秒精度；旧库由 TIMESTAMP(3) 规范化转换，保留原显示值）',
    UNIQUE KEY uk_pi_session_chunk (scope_key, document_key, chunk_no)
) ENGINE=InnoDB COMMENT='Pi 会话分块内容：追加式日志分块，顺序由 chunk_no 保证，缺失即判定记录损坏';

CREATE TABLE IF NOT EXISTS pi_session_commits (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，回执行标识',
    scope_key VARCHAR(64) NOT NULL COMMENT '会话范围键（唯一键首列）',
    commit_id VARCHAR(64) NOT NULL COMMENT '提交标识（客户端提供），与 scope_key 联合唯一',
    request_hash VARCHAR(64) NOT NULL COMMENT '提交请求体 SHA-256 十六进制；同 commit_id 不同内容判定冲突（409）',
    response_json LONGTEXT NOT NULL COMMENT '首次提交时的完整响应（幂等重放原样返回）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行创建时间（毫秒精度；旧库由 TIMESTAMP(3) 规范化转换，保留原显示值）',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行更新时间（毫秒精度；旧库由 TIMESTAMP(3) 规范化转换，保留原显示值）',
    UNIQUE KEY uk_pi_session_commit (scope_key, commit_id)
) ENGINE=InnoDB COMMENT='Pi 会话提交回执：按 commit_id 幂等重放，请求哈希不一致即冲突拒绝';
