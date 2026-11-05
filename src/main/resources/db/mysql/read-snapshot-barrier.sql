-- 读快照凭据屏障与进程恢复检查点（readmodel 共享）。
-- 本文件是唯一结构权威：新库直接按此建表；旧库（自然主键、TINYINT 布尔、缺 create_time）
-- 由 SchemaMigrations 解析本文件生成受控升级（代理主键化、自然键降级唯一键、布尔值域
-- 校验后 TINYINT UNSIGNED、审计列回填），升级后结构一致。
CREATE TABLE IF NOT EXISTS read_snapshot_credential_barriers (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，屏障行标识',
    gateway_namespace VARCHAR(128) NOT NULL COMMENT '网关命名空间，多网关部署的隔离域，非空',
    tenant_code VARCHAR(128) NOT NULL COMMENT '租户编码，租户隔离域，非空',
    credential_hash CHAR(64) NOT NULL COMMENT '凭据 SHA-256 十六进制摘要（64 位 hex），仅存摘要不存凭据本体',
    generation VARCHAR(64) NOT NULL COMMENT '凭据代次标识（服务端生成随机串），同一三元组下新旧代次互斥',
    denial_status INT NOT NULL DEFAULT 0 COMMENT '拒绝状态码：0=未拒绝（正常发放），非 0=拒绝原因编码（整数枚举域）',
    active_until DATETIME(3) NOT NULL COMMENT '凭据活跃截止时间（毫秒精度；到期即失效，不等同于审计保留期）',
    issued_at DATETIME(3) NOT NULL COMMENT '凭据发放时间（毫秒精度）；历史行回填自 update_time，保留原值',
    create_time DATETIME NOT NULL COMMENT '行创建时间',
    update_time DATETIME NOT NULL COMMENT '行更新时间',
    UNIQUE KEY uk_read_snapshot_barrier_credential (gateway_namespace, tenant_code, credential_hash),
    KEY ix_read_snapshot_barrier_until (active_until)
) ENGINE=InnoDB COMMENT='读快照凭据屏障：同网关同租户同凭据哈希只允许一个活跃代次，防止旧凭据复活';

CREATE TABLE IF NOT EXISTS read_snapshot_recovery_checkpoints (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '代理主键，检查点行标识',
    gateway_namespace VARCHAR(128) NOT NULL COMMENT '网关命名空间，每网关单行（唯一键）',
    instance_id VARCHAR(64) NOT NULL COMMENT '最近登记的实例标识；非当前实例时该行视为脏',
    clean TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '干净关闭标志：0=非干净（默认），1=干净（布尔规范 TINYINT UNSIGNED，升级前已校验值域 0/1）',
    recovery_epoch VARCHAR(64) NOT NULL COMMENT '恢复纪元（UUID 串）；脏启动换新纪元使旧凭据屏障失效',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '行创建时间（毫秒精度）；历史行回填自 update_time（该表此前无创建时间列），保留原值不伪造',
    update_time DATETIME(3) NOT NULL COMMENT '行最近刷新时间（毫秒精度）',
    UNIQUE KEY uk_read_snapshot_recovery_checkpoint (gateway_namespace)
) ENGINE=InnoDB COMMENT='读快照进程恢复检查点：每网关单行，记录实例恢复纪元与干净关闭状态';
