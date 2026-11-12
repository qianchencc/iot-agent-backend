package com.ehe.iotagent.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneId;
import com.ehe.iotagent.action.ActionDraftFacade;
import com.ehe.iotagent.admin.AdminConfigurationStore;
import com.ehe.iotagent.agent.AgentBusinessEngineFactory;
import com.ehe.iotagent.agent.AgentToolBridge;
import com.ehe.iotagent.conversation.AgentRunPolicyProvider;
import com.ehe.iotagent.conversation.ConversationFacade;
import com.ehe.iotagent.conversation.ReportGenerationPort;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.report.ReportService;
import com.ehe.iotagent.task.TaskFacade;
import com.ehe.iotagent.task.TaskProjectionStore;
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
            com.ehe.iotagent.allowlist.PhoneAllowlist allowlist,
            @Value("${agent.run.max-turns:6}") int maxTurns) {
        IotAdapter iot = iotAdapters.getIfAvailable();
        return AgentBusinessEngineFactory.policyProvider(() -> conversations.getObject(),
                iot, observations.getIfAvailable(), tasks.getIfAvailable(),
                reports.getIfAvailable(), adminConfigurations::getIfAvailable,
                Clock.system(ZoneId.of("Asia/Shanghai")), maxTurns, allowlist);
    }

    @Bean
    @ConditionalOnMissingBean(AgentToolBridge.class)
    AgentToolBridge agentToolBridge(ConversationFacade conversations, IotAdapter iot,
            ObjectProvider<ObservationAdapter> observations,
            ObjectProvider<TaskProjectionStore> projections,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper,
            ObjectProvider<ReportGenerationPort> reports,
            ObjectProvider<ReportService> savedReports,
            com.ehe.iotagent.allowlist.PhoneAllowlist allowlist,
            ObjectProvider<com.ehe.iotagent.agent.AgentCheckpointBridge> checkpoints) {
        AgentToolBridge interactive = AgentBusinessEngineFactory.toolBridge(conversations, iot,
                observations.getIfAvailable(), projections.getIfAvailable(), actions, tasks, mapper,
                reports.getIfAvailable(), savedReports.getIfAvailable(), Clock.system(ZoneId.of("Asia/Shanghai")), allowlist);
        return request -> {
            var checkpoint = checkpoints.getIfAvailable();
            var result = checkpoint == null ? java.util.Optional.<java.util.Map<String, Object>>empty() : checkpoint.executeTool(request);
            return result.isPresent() ? result.get() : interactive.execute(request);
        };
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "agent.storage.mysql.enabled", havingValue = "true")
    com.ehe.iotagent.agent.AgentCheckpointBridge checkpointBridge(ObjectMapper mapper, IotAdapter iot,
            ObservationAdapter observations, com.ehe.iotagent.report.ReportFactStore reports,
            ObjectProvider<AdminConfigurationStore> configurations,
            @Value("${agent.bridge.base-url:}") String baseUrl, @Value("${agent.bridge.token:}") String token) {
        Clock clock = Clock.systemUTC();
        return AgentBusinessEngineFactory.checkpointBridge(mapper, observations,
                com.ehe.iotagent.report.ReportGenerationServiceFactory.evidence(iot, observations, clock),
                reports, configurations::getIfAvailable, baseUrl, token, clock);
    }
}
