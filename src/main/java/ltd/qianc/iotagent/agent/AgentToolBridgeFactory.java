package ltd.qianc.iotagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.ObservationAdapter;
import ltd.qianc.iotagent.task.TaskFacade;
import ltd.qianc.iotagent.task.TaskProjectionStore;

/** Construction entry point for local contract tests and application composition. */
public final class AgentToolBridgeFactory {
    private AgentToolBridgeFactory() { }

    public static AgentToolBridge local(ConversationFacade conversations, IotAdapter iot,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports) {
        return local(conversations, iot, null, null, actions, tasks, mapper, reports);
    }

    public static AgentToolBridge local(ConversationFacade conversations, IotAdapter iot,
            ObservationAdapter observations, TaskProjectionStore projections,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports) {
        return AgentBusinessEngineFactory.toolBridge(conversations, iot, observations, projections, actions,
                tasks, mapper, reports);
    }
}
