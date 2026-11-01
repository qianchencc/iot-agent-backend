package ltd.qianc.iotagent.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.agent.AgentBusinessEngineFactory;
import ltd.qianc.iotagent.agent.AgentToolBridge;
import ltd.qianc.iotagent.conversation.AgentRunPolicyProvider;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.report.ReportService;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskProjectionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition for the Java-owned business definition and the private tool bridge. */
@Configuration
public class AgentBusinessConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentRunPolicyProvider.class)
    AgentRunPolicyProvider businessAgentRunPolicyProvider(ObjectProvider<ConversationFacade> conversations,
            ObjectProvider<IotAdapter> iotAdapters,
            ObjectProvider<ObservationAdapter> observations,
            ObjectProvider<TaskFacade> tasks,
            ObjectProvider<ReportService> reports,
            ObjectProvider<AdminConfigurationStore> adminConfigurations,
            @Value("${agent.run.max-turns:8}") int maxTurns) {
        return AgentBusinessEngineFactory.policyProvider(() -> conversations.getObject(),
                iotAdapters.getIfAvailable(), observations.getIfAvailable(), tasks.getIfAvailable(),
                reports.getIfAvailable(), adminConfigurations::getIfAvailable,
                Clock.system(ZoneId.of("Asia/Shanghai")), maxTurns);
    }

    @Bean
    @ConditionalOnMissingBean(AgentToolBridge.class)
    AgentToolBridge agentToolBridge(ConversationFacade conversations, IotAdapter iot,
            ObjectProvider<ObservationAdapter> observations,
            ObjectProvider<TaskProjectionStore> projections,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper,
            ObjectProvider<ReportGenerationPort> reports) {
        return AgentBusinessEngineFactory.toolBridge(conversations, iot, observations.getIfAvailable(),
                projections.getIfAvailable(), actions, tasks, mapper, reports.getIfAvailable());
    }
}
