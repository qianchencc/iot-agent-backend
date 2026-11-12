package com.ehe.iotagent.task.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import com.ehe.iotagent.audit.AuditEventStore;
import com.ehe.iotagent.action.ActionCommandGateway;
import com.ehe.iotagent.iot.AutomationSummaryReader;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.region.RegionAttentionReader;
import com.ehe.iotagent.region.RegionSeenStore;
import com.ehe.iotagent.report.ReportFactProducer;
import com.ehe.iotagent.report.ReportFactStore;
import com.ehe.iotagent.task.TaskAtomicTransactions;
import com.ehe.iotagent.task.TaskDispatchGuard;
import com.ehe.iotagent.task.TaskExecutionGateway;
import com.ehe.iotagent.task.TaskExecutionIdentityStore;
import com.ehe.iotagent.task.TaskFactStore;
import com.ehe.iotagent.task.TaskFacade;
import com.ehe.iotagent.task.TaskNotificationStore;
import com.ehe.iotagent.task.TaskPhaseStore;
import com.ehe.iotagent.task.TaskPlanStore;
import com.ehe.iotagent.task.TaskProjectionStore;
import com.ehe.iotagent.task.TaskProposalStore;
import com.ehe.iotagent.task.TaskReportCheckpointComposer;
import com.ehe.iotagent.task.TaskReportRetryStore;
import com.ehe.iotagent.task.TaskSafetyLedgerReader;
import com.ehe.iotagent.task.TaskScanLoop;
import com.ehe.iotagent.task.TaskScheduler;
import com.ehe.iotagent.task.TaskScanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.ehe.iotagent.admin.AdminConfigurationStore;
import com.ehe.iotagent.admin.AdminSettingReader;
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
    TaskSchemaInitializer taskSchemaInitializer(DataSource dataSource, ObjectMapper mapper) {
        return new TaskSchemaInitializer(dataSource, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskExecutionIdentityStore.class)
    TaskExecutionIdentityStore taskExecutionIdentityStore(
            JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${agent.task.credential-key:}") String credentialKey) {
        return com.ehe.iotagent.task.TaskExecutionIdentityStoreFactory.jdbc(jdbc, mapper, credentialKey);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(TaskReportRetryStore.class)
    TaskReportRetryStore taskReportRetryStore(JdbcTemplate jdbc) {
        return new JdbcTaskReportRetryStore(jdbc);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    @ConditionalOnMissingBean(com.ehe.iotagent.task.TaskNotificationRetryStore.class)
    com.ehe.iotagent.task.TaskNotificationRetryStore taskNotificationRetryStore(JdbcTemplate jdbc) {
        return new JdbcTaskNotificationRetryStore(jdbc);
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
            ObjectProvider<TaskDispatchGuard> dispatchGuard,
            ObjectProvider<ObservationAdapter> observation,
            ObjectProvider<com.ehe.iotagent.task.TaskCheckpointRunner> checkpoints,
            @Value("${agent.task.scope-timeout-ms:30000}") long scopeTimeoutMs) {
        ActionCommandGateway actions = actionGateway.getIfAvailable();
        TaskExecutionIdentityStore identityStore = identities.getIfAvailable();
        TaskDispatchGuard guard = dispatchGuard.getIfAvailable();
        if (actions == null || identityStore == null || guard == null) {
            return new FailClosedTaskExecutionGateway("任务真实执行依赖未配置");
        }
        ObservationAdapter observations = observation.getIfAvailable();
        TaskSafetyLedgerReader safetyLedger = observations == null
                ? new FailClosedSafetyLedgerReader()
                : new ObservationSafetyLedgerReader(observations, Duration.ofMillis(Math.max(1000, scopeTimeoutMs)));
        return com.ehe.iotagent.task.TaskExecutionGatewayFactory.real(mapper, iot, actions, identityStore, guard, safetyLedger,
                Clock.systemUTC(), Duration.ofMillis(Math.max(1000, scopeTimeoutMs)), checkpoints::getIfAvailable);
    }

    @Bean @ConditionalOnMissingBean(TaskExecutionGateway.class)
    TaskExecutionGateway taskExecutionGateway() { return new FailClosedTaskExecutionGateway("任务执行适配器未配置"); }
    @Bean @ConditionalOnMissingBean(TaskProjectionStore.class)
    TaskProjectionStore taskProjectionStore() { return new FailClosedTaskProjectionStore("任务时间轴与日历存储未配置"); }
    @Bean
    @ConditionalOnMissingBean(AutomationSummaryReader.class)
    AutomationSummaryReader automationSummaryReader(TaskProjectionStore projections) {
        return identity -> Mono.fromCallable(() -> {
            List<Map<String, Object>> rows = projections.listByIdentity(identity, null, null);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("enabledPlans", null);
            summary.put("active", rows.stream().filter(row -> "EXECUTING".equalsIgnoreCase(String.valueOf(row.get("status")))).count());
            summary.put("upcoming", rows.stream().filter(row -> "SCHEDULED".equalsIgnoreCase(String.valueOf(row.get("status")))).limit(3).toList());
            summary.put("latestResults", rows.stream().filter(row -> row.containsKey("report") || row.containsKey("reportId")).limit(3).toList());
            return summary;
        }).subscribeOn(Schedulers.boundedElastic());
    }
    @Bean
    @ConditionalOnMissingBean(RegionAttentionReader.class)
    RegionAttentionReader regionAttentionReader(TaskProjectionStore projections,
                                                ObjectProvider<RegionSeenStore> seenStores) {
        return principal -> {
            RegionSeenStore seen = seenStores.getIfAvailable();
            if (seen == null) {
                throw new com.ehe.iotagent.error.BusinessException(
                        "REGION_ATTENTION_UNAVAILABLE", "区域异常结果暂不可用", 503);
            }
            return projections.regionAttention(principal,
                    seen.seenAtByRegion(principal.tenantCode(), principal.ownerKey()));
        };
    }

    @Bean @ConditionalOnMissingBean(TaskScheduler.class)
    TaskScheduler taskScheduler() { return new FailClosedTaskScheduler("任务调度器未配置"); }

    @Bean
    TaskFacade taskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals,
                          TaskPlanStore plans, TaskNotificationStore notifications, TaskScheduler scheduler,
                          TaskPhaseStore phases, IotAdapter iot,
                          ObjectProvider<TaskExecutionIdentityStore> identities,
                          com.ehe.iotagent.readmodel.ReadSnapshots snapshots,
                          ObjectProvider<TaskAtomicTransactions> atomic,
                          ObjectProvider<AuditEventStore> audit) {
        return new TaskFacade(mapper, tasks, proposals, plans, notifications, scheduler, phases, iot,
                identities.getIfAvailable(),
                Duration.ofSeconds(30),
                snapshots, atomic.getIfAvailable(TaskAtomicTransactions::direct),
                audit.getIfAvailable(), Clock.systemUTC());
    }
    @Bean
    com.ehe.iotagent.admin.AuditProposalProvider auditProposalProvider(TaskProposalStore proposals,
                                                                        ObjectMapper mapper) {
        return new com.ehe.iotagent.task.TaskProposalAuditProvider(proposals, mapper);
    }
    @Bean
    com.ehe.iotagent.task.TaskConfirmationService taskConfirmationService(TaskFacade tasks,
            com.ehe.iotagent.conversation.ConversationFacade conversations, ObjectMapper mapper) {
        return new com.ehe.iotagent.task.TaskConfirmationService(tasks, conversations, mapper);
    }
    @Bean
    TaskScanner taskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                            TaskNotificationStore notifications,
                            ObjectProvider<ReportFactProducer> reports,
                            ObjectProvider<TaskReportCheckpointComposer> composer,
                            ObjectProvider<TaskReportRetryStore> retries,
                            ObjectProvider<com.ehe.iotagent.task.TaskNotificationRetryStore> notificationRetries,
                            ObjectProvider<TaskExecutionIdentityStore> identities,
                            ObjectProvider<TaskPlanStore> plans,
                            ObjectProvider<TaskAtomicTransactions> atomic,
                            ObjectProvider<AuditEventStore> audit) {
        ReportFactProducer fallback = (checkpoint, identity) -> java.util.Optional.empty();
        return new TaskScanner(phases, tasks, gateway, notifications,
                reports.getIfAvailable(() -> fallback),
                composer.getIfAvailable(), retries.getIfAvailable(), notificationRetries.getIfAvailable(),
                identities.getIfAvailable(), plans.getIfAvailable(),
                atomic.getIfAvailable(TaskAtomicTransactions::direct),
                audit.getIfAvailable(), Clock.systemUTC());
    }
    @Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskPhaseScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("task-phase-scan-");
        return scheduler;
    }
    @Bean
    @org.springframework.context.annotation.Primary
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler backgroundTaskScheduler() {
        var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        // Report recovery is one serial job; leave capacity for ordinary refreshes.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("business-refresh-");
        return scheduler;
    }
    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    TaskScanLoop taskScanLoop(TaskScanner scanner,
            ObjectProvider<AdminConfigurationStore> adminConfigurations,
            @Qualifier("taskPhaseScheduler") org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler phaseScheduler,
            @Qualifier("backgroundTaskScheduler") org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler backgroundScheduler,
            @Value("${agent.task.scanner.poll-ms:1000}") long fallbackPollMs) {
        return new TaskScanLoop(scanner,
                AdminSettingReader.longSetting(adminConfigurations, "scanIntervalMs", 1000, 60_000, fallbackPollMs),
                phaseScheduler, backgroundScheduler);
    }

}
