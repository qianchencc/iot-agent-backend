package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import javax.sql.DataSource;
import ltd.qianc.iotagent.action.ActionReadbackStore;
import ltd.qianc.iotagent.action.ActionReceiptStore;
import ltd.qianc.iotagent.action.ActionDraftStore;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.conversation.ConversationStore;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.storage.RedisCoordinationStore;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskNotificationStore;
import ltd.qianc.iotagent.task.TaskPhaseStore;
import ltd.qianc.iotagent.task.TaskOccurrenceMaterializer;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskProposalStore;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import ltd.qianc.iotagent.task.TaskScheduler;
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
        return DataSourceBuilder.create().url(required(environment, "spring.datasource.url"))
                .username(required(environment, "spring.datasource.username"))
                .password(required(environment, "spring.datasource.password")).build();
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
        URI uri = URI.create(required(environment, "spring.data.redis.url"));
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379);
        if (uri.getPath() != null && uri.getPath().length() > 1) configuration.setDatabase(Integer.parseInt(uri.getPath().substring(1)));
        if (uri.getUserInfo() != null && uri.getUserInfo().contains(":")) configuration.setPassword(uri.getUserInfo().substring(uri.getUserInfo().indexOf(':') + 1));
        var client = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                .commandTimeout(java.time.Duration.ofSeconds(2))
                .shutdownTimeout(java.time.Duration.ofMillis(100))
                .clientOptions(io.lettuce.core.ClientOptions.builder().socketOptions(io.lettuce.core.SocketOptions.builder()
                        .connectTimeout(java.time.Duration.ofSeconds(2)).build()).build()).build();
        return new LettuceConnectionFactory(configuration, client);
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
    @ConditionalOnMissingBean(ActionReceiptStore.class)
    JdbcActionReceiptStore jdbcActionReceiptStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new JdbcActionReceiptStore(jdbc, transactions, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(ActionReadbackStore.class)
    JdbcActionReadbackStore jdbcActionReadbackStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new JdbcActionReadbackStore(jdbc, transactions, mapper);
    }

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
    @ConditionalOnMissingBean(ltd.qianc.iotagent.task.TaskDispatchGuard.class)
    JdbcTaskDispatchGuard jdbcTaskDispatchGuard(JdbcTemplate jdbc) { return new JdbcTaskDispatchGuard(jdbc); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskScheduler.class)
    JdbcTaskScheduler jdbcTaskScheduler(JdbcTemplate jdbc, ObjectMapper mapper) { return new JdbcTaskScheduler(jdbc, mapper); }
    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    JdbcTaskRecurrenceMaterializer jdbcTaskRecurrenceMaterializer(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
            org.springframework.core.env.Environment environment,
            org.springframework.beans.factory.ObjectProvider<ltd.qianc.iotagent.task.TaskExecutionIdentityStore> identities) {
        int batch = environment.getProperty("agent.task.recurrence.batch-size", Integer.class, 25);
        return new JdbcTaskRecurrenceMaterializer(jdbc, transactions, mapper, java.time.Clock.systemUTC(), batch,
                identities.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    JdbcTaskRecurrenceLoop jdbcTaskRecurrenceLoop(TaskOccurrenceMaterializer materializer) {
        return new JdbcTaskRecurrenceLoop(materializer);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskProjectionStore.class)
    TaskProjectionStore jdbcTaskProjectionStore(JdbcTemplate jdbc, ObjectMapper mapper,
            org.springframework.beans.factory.ObjectProvider<ltd.qianc.iotagent.task.TaskFacade> facades) {
        return ltd.qianc.iotagent.storage.TaskFactStoresFactory.taskProjections(jdbc, mapper, new ltd.qianc.iotagent.task.TaskReadScope.Provider() {
            @Override public ltd.qianc.iotagent.task.TaskReadScope scope(
                    ltd.qianc.iotagent.auth.PrincipalInfo principal, String source) {
                return facades.getObject().scope(principal, source);
            }
            @Override public ltd.qianc.iotagent.task.TaskReadScope loadScope(
                    ltd.qianc.iotagent.iot.RequestIdentity identity) {
                return facades.getObject().loadScope(identity);
            }
        });
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskNotificationStore.class)
    JdbcTaskNotificationStore jdbcTaskNotificationStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcTaskNotificationStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    JdbcImmediateActionHistory immediateActionHistory(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        return new JdbcImmediateActionHistory(jdbc, transactions, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean({ReportFactStore.class, AuditEventStore.class})
    JdbcReportAuditStore jdbcReportAuditStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) { return new JdbcReportAuditStore(jdbc, transactions, mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.redis.enabled", havingValue = "true")
    @ConditionalOnMissingBean(RedisCoordinationStore.class)
    LettuceRedisCoordinationStore lettuceRedisCoordinationStore(StringRedisTemplate redis) { return new LettuceRedisCoordinationStore(redis); }

    @Bean
    @org.springframework.context.annotation.DependsOn("storageSchemaInitializer")
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(ReadSnapshots.Barrier.class)
    ReadSnapshots.Barrier readSnapshotBarrier(JdbcTemplate jdbc, TransactionTemplate transactions,
            org.springframework.core.env.Environment environment) {
        return new JdbcCredentialBarrier(jdbc, transactions, readModelNamespace(environment));
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.redis.enabled", havingValue = "true")
    ReadSnapshots.Persistence readSnapshotPersistence(RedisCoordinationStore store, org.springframework.core.env.Environment environment) {
        final String namespace = readModelNamespace(environment);
        return new ReadSnapshots.Persistence() {
            @Override public java.util.Optional<String> get(String key) { return store.get(namespace + key); }
            @Override public void put(String key, String value, java.time.Duration retention) { store.put(namespace + key, value, retention); }
            @Override public void delete(String key) { store.delete(namespace + key); }
        };
    }

    private static String readModelNamespace(org.springframework.core.env.Environment environment) {
        String origin = environment.getProperty("iot.gateway.base-url", "").strip().replaceAll("/+$", "");
        try {
            return "readmodel:gateway:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(origin.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + ":";
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String required(org.springframework.core.env.Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少存储配置：" + name);
        return value;
    }
}
