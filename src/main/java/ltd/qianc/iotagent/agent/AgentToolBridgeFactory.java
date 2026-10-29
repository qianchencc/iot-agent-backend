package ltd.qianc.iotagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.agent.internal.controller.AgentToolBridgeController;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.conversation.ReportGenerationPort;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.task.TaskFacade;

/** Construction entry point for local contract tests and application composition. */
public final class AgentToolBridgeFactory {
    private AgentToolBridgeFactory() { }

    public static AgentToolBridge local(ConversationFacade conversations, IotAdapter iot,
            ActionDraftFacade actions, TaskFacade tasks, ObjectMapper mapper, ReportGenerationPort reports) {
        AgentToolBridgeController controller = new AgentToolBridgeController(
                conversations, iot, actions, tasks, mapper, reports);
        return controller::execute;
    }
}
