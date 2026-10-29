package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import ltd.qianc.iotagent.task.TaskExecutionGateway;
import ltd.qianc.iotagent.task.TaskFactStore;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskNotificationStore;
import ltd.qianc.iotagent.task.TaskPhaseStore;
import ltd.qianc.iotagent.task.TaskPlanStore;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import ltd.qianc.iotagent.task.TaskProposalStore;
import ltd.qianc.iotagent.task.TaskScheduler;
import ltd.qianc.iotagent.task.TaskScanner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

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

    @Bean
    @ConditionalOnProperty(name = "agent.control.mode", havingValue = "fixture")
    TaskExecutionGateway fixtureTaskExecutionGateway(
            ObjectMapper mapper,
            @org.springframework.beans.factory.annotation.Value("${agent.control.fixture.task-outcome:COMPLETED}") String outcome) {
        return new FixtureTaskExecutionGateway(mapper, outcome);
    }
    @Bean @ConditionalOnMissingBean(TaskExecutionGateway.class)
    TaskExecutionGateway taskExecutionGateway() { return new FailClosedTaskExecutionGateway("任务执行适配器未配置"); }
    @Bean @ConditionalOnMissingBean(TaskProjectionStore.class)
    TaskProjectionStore taskProjectionStore() { return new FailClosedTaskProjectionStore("任务时间轴与日历存储未配置"); }
    @Bean @ConditionalOnMissingBean(TaskScheduler.class)
    TaskScheduler taskScheduler() { return new FailClosedTaskScheduler("任务调度器未配置"); }

    @Bean
    TaskFacade taskFacade(ObjectMapper mapper, TaskFactStore tasks, TaskProposalStore proposals, TaskPlanStore plans,
                          TaskNotificationStore notifications, TaskScheduler scheduler) {
        return new TaskFacade(mapper, tasks, proposals, plans, notifications, scheduler, Clock.systemUTC());
    }
    @Bean
    TaskScanner taskScanner(TaskPhaseStore phases, TaskFactStore tasks, TaskExecutionGateway gateway,
                            TaskNotificationStore notifications) {
        return new TaskScanner(phases, tasks, gateway, notifications, Clock.systemUTC());
    }
    @Bean
    @ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    TaskScanLoop taskScanLoop(TaskScanner scanner) { return new TaskScanLoop(scanner); }

}
