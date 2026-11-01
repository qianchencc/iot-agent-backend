package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import ltd.qianc.iotagent.action.ActionCommandGateway;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.AutomationSummaryReader;
import ltd.qianc.iotagent.report.ReportFactProducer;
import ltd.qianc.iotagent.report.ReportFactStore;
import ltd.qianc.iotagent.task.TaskExecutionGateway;
import ltd.qianc.iotagent.task.TaskExecutionIdentityStore;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskNotificationStore;
import ltd.qianc.iotagent.task.TaskPhaseStore;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import ltd.qianc.iotagent.task.TaskProposalStore;
import ltd.qianc.iotagent.task.TaskReportRetryStore;
import ltd.qianc.iotagent.task.TaskScheduler;
import ltd.qianc.iotagent.task.TaskScanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.jdbc.core.JdbcTemplate;

/** All durable and external task seams fail closed until real adapters are supplied. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TaskConfiguration {
    @Bean @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(TaskFactStore.class)
    TaskFactStore taskFactStore() { return new FailClosedTaskFactStore("任务事实存储未配置"); }
    @Bean @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(TaskProposalStore.class)
    TaskProposalStore taskProposalStore() { return new FailClosedTaskProposalStore("任务提案存储未配置"); }
    @Bean @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(TaskPlanStore.class)
    TaskPlanStore taskPlanStore() { return new FailClosedTaskPlanStore("周期计划存储未配置"); }
    @Bean @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(TaskNotificationStore.class)
    TaskNotificationStore taskNotificationStore() { return new FailClosedTaskNotificationStore("任务通知存储未配置"); }
    @Bean @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(TaskPhaseStore.class)
    TaskPhaseStore taskPhaseStore() { return new FailClosedTaskPhaseStore("任务阶段存储未配置"); }

    @Bean(initMethod = "initialize")
    @org.springframework.context.annotation.DependsOn("storageSchemaInitializer")
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    TaskSchemaInitializer taskSchemaInitializer(DataSource dataSource) {
        return new TaskSchemaInitializer(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskExecutionIdentityStore.class)
    TaskExecutionIdentityStore taskExecutionIdentityStore(
            JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${TASK_CREDENTIAL_KEY:${agent.task.credential-key:}}") String credentialKey) {
        return new JdbcTaskExecutionIdentityStore(jdbc, mapper, credentialKey);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskReportRetryStore.class)
    TaskReportRetryStore taskReportRetryStore(JdbcTemplate jdbc) {
        return new JdbcTaskReportRetryStore(jdbc);
    }

    @Bean
    @ConditionalOnMissingBean(TaskReportComposer.class)
    TaskReportComposer taskReportComposer(ObjectMapper mapper) { return new TaskReportComposer(mapper); }

    @Bean
    @ConditionalOnProperty(name = "agent.control.mode", havingValue = "fixture")
    TaskExecutionGateway fixtureTaskExecutionGateway(
            ObjectMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${agent.control.fixture.task-outcome:COMPLETED}") String outcome) {
        return new FixtureTaskExecutionGateway(mapper, outcome);
    }

    @Bean
    @ConditionalOnExpression("'${agent.task.execution.mode:}' == 'real' || '${agent.control.mode:}' == 'real'")
    TaskExecutionGateway realTaskExecutionGateway(
            ObjectMapper mapper,
            IotAdapter iot,
            ObjectProvider<ActionCommandGateway> actionGateway,
            ObjectProvider<TaskExecutionIdentityStore> identities,
            @Value("${agent.task.scope-timeout-ms:30000}") long scopeTimeoutMs) {
        ActionCommandGateway actions = actionGateway.getIfAvailable();
        TaskExecutionIdentityStore identityStore = identities.getIfAvailable();
        if (actions == null || identityStore == null) {
            return new FailClosedTaskExecutionGateway("任务真实执行依赖未配置");
        }
        return new RealTaskExecutionGateway(mapper, iot, actions, identityStore, Clock.systemUTC(),
                Duration.ofMillis(Math.max(1000, scopeTimeoutMs)));
    }

    @Bean @ConditionalOnMissingBean(TaskExecutionGateway.class)
    TaskExecutionGateway taskExecutionGateway() { return new FailClosedTaskExecutionGateway("任务执行适配器未配置"); }
    @Bean @ConditionalOnMissingBean(TaskProjectionStore.class)
    TaskProjectionStore taskProjectionStore() { return new FailClosedTaskProjectionStore("任务时间轴与日历存储未配置"); }
    @Bean
    @ConditionalOnMissingBean(AutomationSummaryReader.class)
    AutomationSummaryReader automationSummaryReader(TaskProjectionStore projections) {
        return identity -> Mono.fromCallable(() -> {
            List<Map<String, Object>> rows = projections.listByOwner(
                    identity.tenantCode(), identity.ownerKey(), identity.source().wireValue(), null, null);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("enabledPlans", null);
            summary.put("active", rows.stream().filter(row -> "EXECUTING".equalsIgnoreCase(String.valueOf(row.get("status")))).count());
            summary.put("upcoming", rows.stream().filter(row -> "SCHEDULED".equalsIgnoreCase(String.valueOf(row.get("status")))).limit(3).toList());
            summary.put("latestResults", rows.stream().filter(row -> row.containsKey("report") || row.containsKey("reportId")).limit(3).toList());
            return summary;
        });
    }
    @Bean @ConditionalOnMissingBean(TaskScheduler.class)
    TaskScheduler taskScheduler() { return new FailClosedTaskScheduler("任务调度器未配置"); }

    @Bean
    TaskFacade taskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals, TaskPlanStore plans,
                          TaskNotificationStore notifications, TaskScheduler scheduler, TaskPhaseStore phases, IotAdapter iot,
                          ObjectProvider<ReportFactStore> reports, ObjectProvider<TaskExecutionIdentityStore> identities) {
        return new TaskFacade(mapper, tasks, proposals, plans, notifications, scheduler, phases, iot,
                reports.getIfAvailable(), identities.getIfAvailable(),
                new TaskProposalNormalizer(mapper, iot, Clock.systemUTC(), Duration.ofSeconds(30)), Clock.systemUTC());
    }
    @Bean
    TaskScanner taskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                            TaskNotificationStore notifications,
                            ObjectProvider<ReportFactProducer> reports,
                            ObjectProvider<TaskReportComposer> composer,
                            ObjectProvider<TaskReportRetryStore> retries,
                            ObjectProvider<TaskExecutionIdentityStore> identities) {
        ReportFactProducer fallback = (checkpoint, identity) -> java.util.Optional.empty();
        return new TaskScanner(phases, tasks, gateway, notifications,
                reports.orElse(fallback),
                composer.getIfAvailable(), retries.getIfAvailable(), identities.getIfAvailable(), Clock.systemUTC());
    }
    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    TaskScanLoop taskScanLoop(TaskScanner scanner) { return new TaskScanLoop(scanner); }

}
