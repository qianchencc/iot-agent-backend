package com.ehe.iotagent.schema;

import java.util.Objects;
import javax.sql.DataSource;
import com.ehe.iotagent.schema.internal.JdbcSchemaMigrations;

/**
 * 统一结构迁移入口：唯一负责 MySQL 建库资源执行与旧库增量升级。
 *
 * <p>深模块约定：DDL 资源、兼容升级（含 task_phases 防御性补列）、迁移登记
 * （storage_migrations）与列注释全部隐藏在本入口之后，各业务模块
 * （storage/task/pisession/iot）只通过本入口初始化自己的 slice，不再各自持有
 * CREATE/ALTER 语句。本模块不依赖任何业务模块，禁止反向依赖。
 *
 * <p>语义保证：
 * <ul>
 *   <li>新库按 db/mysql 资源建表；旧库按登记的迁移键增量升级，可重复启动、崩溃可恢复；</li>
 *   <li>升级保留全部既有数据与自然唯一键（幂等约束不丢失），不清库、不改写历史引用；</li>
 *   <li>统一 bigint unsigned 代理主键 id 与 create_time/update_time 审计列（保留既有精度）。</li>
 * </ul>
 */
public final class SchemaMigrations {
    private final JdbcSchemaMigrations migrations;

    public SchemaMigrations(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.migrations = new JdbcSchemaMigrations(dataSource);
    }

    /** 共享业务事实基线：conversations/actions/task_facts/审计/白名单/配置修订/读快照屏障，含一次性会话退役迁移。 */
    public void ensureSharedFacts() {
        migrations.ensureSharedFacts();
    }

    /** 独立配置存储初始化；不触发业务会话的一次性退役或其他业务表升级。 */
    public void ensureAdminConfiguration() {
        migrations.ensureAdminConfiguration();
    }

    /** 任务生命周期恢复表与 task_phases 补列：执行身份托管、报告/通知补投、设备调度预约。 */
    public void ensureTaskLifecycle() {
        migrations.ensureTaskLifecycle();
    }

    /** 设备候选集事实表：含旧 JSON 句柄回填与会话内句柄唯一键。 */
    public void ensureDeviceCandidateSets() {
        migrations.ensureDeviceCandidateSets();
    }

    /** Principal-isolated Company/Office directories, grants and independently encrypted synchronization registration. */
    public void ensureLocalCompanyDirectory() {
        migrations.ensureLocalCompanyDirectory();
    }

    /** Pi 原生会话日志四表：范围租约、虚拟文件、分块内容与提交回执。 */
    public void ensurePiSessions() {
        migrations.ensurePiSessions();
    }
}
