package ltd.qianc.iotagent.storage.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import ltd.qianc.iotagent.task.TaskAtomicTransactions;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task transaction slice wiring: the shared atomic-write boundary over the
 * agent DataSource, the additive plan schema it relies on, and the confirmed
 * recovery sweep. MySQL-disabled deployments keep their fail-closed stores.
 */
@Configuration(proxyBeanMethods = false)
public class TaskTransactionConfiguration {

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskAtomicTransactions.class)
    TaskAtomicTransactions taskAtomicTransactions(TransactionTemplate transactions) {
        return new JdbcTaskAtomicTransactions(transactions);
    }

    @Bean
    @DependsOn("storageSchemaInitializer")
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    JdbcTaskConfirmedRecoveryStartup jdbcTaskConfirmedRecoveryStartup(JdbcTemplate jdbc, TransactionTemplate transactions,
            TaskPlanStore plans, TaskFactStore tasks, TaskScheduler scheduler, ObjectMapper mapper) {
        Objects.requireNonNull(plans, "plans");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(scheduler, "scheduler");
        return new JdbcTaskConfirmedRecoveryStartup(new JdbcTaskConfirmedRecovery(
                jdbc, transactions, plans, tasks, scheduler, mapper));
    }
}
