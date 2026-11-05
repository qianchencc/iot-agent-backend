package ltd.qianc.iotagent.agent.internal.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import ltd.qianc.iotagent.agent.AgentToolBridge;
import ltd.qianc.iotagent.agent.internal.AgentToolCatalogSupport;
import ltd.qianc.iotagent.pisession.PiSessionService;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<PiSessionService> sessions;

    public AgentToolBridgeController(AgentToolBridge bridge, ObjectProvider<PiSessionService> sessions) {
        this.bridge = bridge;
        this.sessions = sessions;
    }

    @PostMapping
    public Map<String, Object> execute(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) throw new BusinessException("INVALID_AGENT_TOOL", "工具请求不能为空", 400);
        Object protocolVersion = body.get("protocolVersion");
        if (!(protocolVersion instanceof Number number) || number.intValue() != 1) {
            throw new BusinessException("AGENT_PROTOCOL_VERSION_UNSUPPORTED", "Agent 工具协议版本不支持", 400);
        }
        PiSessionService store = sessions.getIfAvailable();
        if (store != null) {
            Map<String,Object> proof = new LinkedHashMap<>();
            for (String key : new String[] {"protocolVersion", "runId", "conversationId", "turnId", "toolContextId", "writerId", "epoch"}) {
                proof.put(key, body.get(key));
            }
            proof.put("operation", "check");
            store.execute(proof);
        }
        Map<String, Object> result = new LinkedHashMap<>(bridge.execute(body));
        // Run-scoped closing facts travel in the envelope, never inside the model result.
        Object closing = result.containsKey("closingContext") ? result.remove("closingContext") : null;
        Object modelResult = result.remove("modelResult");
        modelResult = AgentToolCatalogSupport.cardModelResult(text(body.get("toolName")), result, modelResult);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocolVersion", 1);
        response.put("result", result);
        if (modelResult != null) response.put("modelResult", modelResult);
        boolean halt = AgentToolCatalogSupport.haltsRun(text(body.get("toolName")), result);
        response.put("halt", halt);
        String completion = halt ? AgentToolCatalogSupport.completionText(text(body.get("toolName")), result,
                body.get("arguments") instanceof Map<?, ?> arguments ? arguments : Map.of()) : null;
        if (completion != null) response.put("completion", completion);
        if (closing instanceof String context && !context.isBlank()) {
            // Candidate names remain useful during selection, not as instructions to repeat a completed card.
            response.put("closingContext", halt && modelResult instanceof Map<?, ?> model && model.containsKey("cardContent")
                    ? "本轮待确认卡已生成，按模型结果中的状态和必要限制收尾；不再列候选或复述卡片，不代表已执行。"
                    : context);
        }
        return response;
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ltd.qianc.iotagent.agent.AgentToolHaltException.class)
    public org.springframework.http.ResponseEntity<Map<String, Object>> halt(
            ltd.qianc.iotagent.agent.AgentToolHaltException failure) {
        return org.springframework.http.ResponseEntity.status(failure.httpStatus()).body(Map.of(
                "protocolVersion", 1, "code", failure.code(), "message", failure.getMessage(), "halt", true));
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
