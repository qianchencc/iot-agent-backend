package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import javax.sql.DataSource;
import ltd.qianc.iotagent.action.ActionDraftStore;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.conversation.ConversationStore;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.storage.RedisCoordinationStore;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskNotificationStore;
import ltd.qianc.iotagent.task.TaskPhaseStore;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskProposalStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class StorageConfiguration {
    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    DataSource agentDataSource(org.springframework.core.env.Environment environment) {
        return DataSourceBuilder.create().url(required(environment, "AGENT_MYSQL_URL"))
                .username(required(environment, "AGENT_MYSQL_USERNAME"))
                .password(required(environment, "AGENT_MYSQL_PASSWORD")).build();
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    TransactionTemplate agentTransactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean(initMethod = "initialize")
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    StorageSchemaInitializer storageSchemaInitializer(DataSource dataSource) {
        return new StorageSchemaInitializer(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.redis.enabled", havingValue = "true")
    LettuceConnectionFactory agentRedisConnectionFactory(org.springframework.core.env.Environment environment) {
        URI uri = URI.create(required(environment, "AGENT_REDIS_URL"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379);
        if (uri.getPath() != null && uri.getPath().length() > 1) configuration.setDatabase(Integer.parseInt(uri.getPath().substring(1)));
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) configuration.setPassword(uri.getUserInfo().substring(uri.getUserInfo().indexOf(':') + 1));
        return new LettuceConnectionFactory(configuration);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.redis.enabled", havingValue = "true")
    StringRedisTemplate agentRedisTemplate(LettuceConnectionFactory connectionFactory) { return new StringRedisTemplate(connectionFactory); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean({ConversationStore.class, ConversationEventStore.class})
    JdbcConversationStore jdbcConversationStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcConversationStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(ActionDraftStore.class)
    JdbcActionDraftStore jdbcActionDraftStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcActionDraftStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskFactStore.class)
    JdbcTaskFactStore jdbcTaskFactStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcTaskFactStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskProposalStore.class)
    JdbcTaskProposalStore jdbcTaskProposalStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcTaskProposalStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskPlanStore.class)
    JdbcTaskPlanStore jdbcTaskPlanStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcTaskPlanStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskPhaseStore.class)
    JdbcTaskPhaseStore jdbcTaskPhaseStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcTaskPhaseStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskNotificationStore.class)
    JdbcTaskNotificationStore jdbcTaskNotificationStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcTaskNotificationStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean({ReportFactStore.class, AuditEventStore.class})
    JdbcReportAuditStore jdbcReportAuditStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcReportAuditStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.redis.enabled", havingValue = "true")
    @ConditionalOnMissingBean(RedisCoordinationStore.class)
    LettuceRedisCoordinationStore lettuceRedisCoordinationStore(StringRedisTemplate redis) { return new LettuceRedisCoordinationStore(redis); }

    private static String required(org.springframework.core.env.Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少存储配置：" + name);
        return value;
    }
}
