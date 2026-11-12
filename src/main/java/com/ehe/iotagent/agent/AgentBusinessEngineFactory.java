package com.ehe.iotagent.agent;

import java.time.Clock;
import java.util.function.Supplier;
import com.ehe.iotagent.action.ActionDraftFacade;
import com.ehe.iotagent.admin.AdminConfigurationStore;
import com.ehe.iotagent.agent.internal.AgentBusinessRunPolicyProvider;
import com.ehe.iotagent.agent.internal.AgentBusinessToolDispatcher;
import com.ehe.iotagent.conversation.AgentRunPolicyProvider;
import com.ehe.iotagent.conversation.ConversationFacade;
import com.ehe.iotagent.conversation.ReportGenerationPort;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.report.ReportService;
import com.ehe.iotagent.task.TaskFacade;
import com.ehe.iotagent.task.TaskProjectionStore;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Construction entry point for the Java-owned business engine definition and tool bridge. */
public final class AgentBusinessEngineFactory {
    private AgentBusinessEngineFactory() { }

    public static AgentRunPolicyProvider policyProvider(Supplier<ConversationFacade> conversations,
            IotAdapter iot, Clock clock, int maxTurns) {
        return new AgentBusinessRunPolicyProvider(conversations, iot, clock, maxTurns);
    }

    public static AgentRunPolicyProvider policyProvider(Supplier<ConversationFacade> conversations,
            IotAdapter iot, Supplier<AdminConfigurationStore> adminConfigurations,
            Clock clock, int maxTurns) {
        return new AgentBusinessRunPolicyProvider(conversations, iot, adminConfigurations, clock, maxTurns);
    }

    public static AgentRunPolicyProvider policyProvider(Supplier<ConversationFacade> conversations,
            IotAdapter iot, ObservationAdapter observations, TaskFacade tasks, ReportService reports,
            Clock clock, int maxTurns) {
        return new AgentBusinessRunPolicyProvider(conversations, iot, observations, tasks, reports, clock, maxTurns);
    }

    public static AgentRunPolicyProvider policyProvider(Supplier<ConversationFacade> conversations,
            IotAdapter iot, ObservationAdapter observations, TaskFacade tasks, ReportService reports,
            Supplier<AdminConfigurationStore> adminConfigurations, Clock clock, int maxTurns,
            com.ehe.iotagent.allowlist.PhoneAllowlist allowlist) {
        return new AgentBusinessRunPolicyProvider(conversations, iot, observations, tasks, reports,
                adminConfigurations, clock, maxTurns, allowlist);
    }

    public static AgentToolBridge toolBridge(ConversationFacade conversations, IotAdapter iot,
            ObservationAdapter observations, TaskProjectionStore projections, ActionDraftFacade actions,
            TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports, ReportService savedReports) {
        return new AgentBusinessToolDispatcher(conversations, iot, observations, projections, actions, tasks,
                mapper, reports, savedReports);
    }

    /** Production construction: the dispatcher owns time-sensitive alert window validation. */
    public static AgentToolBridge toolBridge(ConversationFacade conversations, IotAdapter iot,
            ObservationAdapter observations, TaskProjectionStore projections, ActionDraftFacade actions,
            TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports, ReportService savedReports,
            Clock clock, com.ehe.iotagent.allowlist.PhoneAllowlist allowlist) {
        return new AgentBusinessToolDispatcher(conversations, iot, observations, projections, actions, tasks,
                mapper, reports, savedReports, clock, allowlist);
    }

    /** Convenience for local contract fixtures that only exercise a subset of tools. */
    public static AgentToolBridge toolBridge(ConversationFacade conversations, IotAdapter iot,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports) {
        return toolBridge(conversations, iot, null, null, actions, tasks, mapper, reports, null);
    }

    public static AgentCheckpointBridge checkpointBridge(ObjectMapper mapper, ObservationAdapter observations,
            com.ehe.iotagent.report.ReportEvidencePort evidence, com.ehe.iotagent.report.ReportFactStore reports,
            Supplier<AdminConfigurationStore> configurations, String baseUrl, String token, Clock clock) {
        return new com.ehe.iotagent.agent.internal.DefaultAgentCheckpointBridge(
                mapper, observations, evidence, reports, configurations, baseUrl, token, clock);
    }
}
