package ltd.qianc.iotagent.agent;

import java.time.Clock;
import java.util.function.Supplier;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.admin.AdminConfigurationStore;
import ltd.qianc.iotagent.agent.internal.AgentBusinessRunPolicyProvider;
import ltd.qianc.iotagent.agent.internal.AgentBusinessToolDispatcher;
import ltd.qianc.iotagent.conversation.AgentRunPolicyProvider;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.report.ReportService;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskProjectionStore;
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
            ltd.qianc.iotagent.allowlist.PhoneAllowlist allowlist) {
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
            Clock clock, ltd.qianc.iotagent.allowlist.PhoneAllowlist allowlist) {
        return new AgentBusinessToolDispatcher(conversations, iot, observations, projections, actions, tasks,
                mapper, reports, savedReports, clock, allowlist);
    }

    /** Convenience for local contract fixtures that only exercise a subset of tools. */
    public static AgentToolBridge toolBridge(ConversationFacade conversations, IotAdapter iot,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports) {
        return toolBridge(conversations, iot, null, null, actions, tasks, mapper, reports, null);
    }

    public static AgentCheckpointBridge checkpointBridge(ObjectMapper mapper, ObservationAdapter observations,
            ltd.qianc.iotagent.report.ReportEvidencePort evidence, ltd.qianc.iotagent.report.ReportFactStore reports,
            Supplier<AdminConfigurationStore> configurations, String baseUrl, String token, Clock clock) {
        return new ltd.qianc.iotagent.agent.internal.DefaultAgentCheckpointBridge(
                mapper, observations, evidence, reports, configurations, baseUrl, token, clock);
    }
}
