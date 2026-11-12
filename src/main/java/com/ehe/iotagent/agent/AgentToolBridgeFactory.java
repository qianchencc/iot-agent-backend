package com.ehe.iotagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ehe.iotagent.action.ActionDraftFacade;
import com.ehe.iotagent.conversation.ConversationFacade;
import com.ehe.iotagent.conversation.ReportGenerationPort;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.task.TaskFacade;
import com.ehe.iotagent.task.TaskProjectionStore;

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
                tasks, mapper, reports, null);
    }
}
