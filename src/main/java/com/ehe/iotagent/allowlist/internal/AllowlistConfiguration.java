package com.ehe.iotagent.allowlist.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import com.ehe.iotagent.allowlist.PhoneAllowlist;
import com.ehe.iotagent.audit.AuditEventStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 白名单装配：MySQL 启用时使用 JDBC 事实存储（建表见 db/mysql/agent-allowlist.sql，
 * 由共享 StorageSchemaInitializer 执行），否则公司任务资格关闭，其他业务保留。
 */
@Configuration(proxyBeanMethods = false)
public class AllowlistConfiguration {

    @Bean
    @DependsOn("storageSchemaInitializer")
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(PhoneAllowlist.class)
    PhoneAllowlist mysqlPhoneAllowlist(
            ObjectProvider<JdbcTemplate> jdbcProvider,
            ObjectProvider<TransactionTemplate> transactionProvider,
            ObjectMapper mapper,
            ObjectProvider<AuditEventStore> auditProvider) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        TransactionTemplate transactions = transactionProvider.getIfAvailable();
        if (jdbc == null || transactions == null) {
            return PhoneAllowlist.failClosed("白名单存储未就绪");
        }
        return PhoneAllowlist.mysql(jdbc, transactions, mapper, auditProvider.getIfAvailable(),
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(PhoneAllowlist.class)
    PhoneAllowlist failClosedPhoneAllowlist() {
        return PhoneAllowlist.failClosed("MySQL 存储未启用");
    }
}
