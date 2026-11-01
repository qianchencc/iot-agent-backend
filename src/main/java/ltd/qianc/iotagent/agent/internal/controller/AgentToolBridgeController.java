package ltd.qianc.iotagent.agent.internal.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import ltd.qianc.iotagent.agent.AgentToolBridge;
import ltd.qianc.iotagent.agent.internal.AgentToolCatalogSupport;
import ltd.qianc.iotagent.error.BusinessException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated private transport for Java business tools; business work stays in the agent module. */
@RestController
@RequestMapping("/internal/agent/tools")
public final class AgentToolBridgeController {

    private final AgentToolBridge bridge;

    public AgentToolBridgeController(AgentToolBridge bridge) {
        this.bridge = bridge;
    }

    @PostMapping
    public Map<String, Object> execute(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) throw new BusinessException("INVALID_AGENT_TOOL", "工具请求不能为空", 400);
        Object protocolVersion = body.get("protocolVersion");
        if (!(protocolVersion instanceof Number number) || number.intValue() != 1) {
            throw new BusinessException("AGENT_PROTOCOL_VERSION_UNSUPPORTED", "Agent 工具协议版本不支持", 400);
        }
        Map<String, Object> result = bridge.execute(body);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocolVersion", 1);
        response.put("result", result);
        response.put("halt", AgentToolCatalogSupport.requiresConfirmation(
                text(body.get("toolName")), result));
        return response;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
