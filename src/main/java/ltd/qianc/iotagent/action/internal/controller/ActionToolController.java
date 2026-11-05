package ltd.qianc.iotagent.action.internal.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.error.RequestValues;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal Agent-tool boundary for materializing a server-validated action draft. */
@RestController
@RequestMapping("/api/agent/tools")
public final class ActionToolController {
    private final ActionDraftFacade facade;
    private final ObjectMapper mapper;

    public ActionToolController(ActionDraftFacade facade, ObjectMapper mapper) {
        this.facade = facade;
        this.mapper = mapper;
    }

    @PostMapping("/propose_action_draft")
    public ResponseEntity<Map<String, Object>> propose(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null || !(body.get("payload") instanceof Map<?, ?> payload)) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "action draft payload is required");
        }
        try {
            String payloadJson = mapper.writeValueAsString(payload);
            var draft = facade.propose(
                    PrincipalContext.getPrincipalInfo(),
                    RequestValues.optionalText(body, "source", "office",
                            "INVALID_AGENT_SOURCE", "Agent 来源无效", 400),
                    RequestValues.optionalText(body, "conversationId", "",
                            "INVALID_PARAMETERS", "conversationId 无效", 400),
                    RequestValues.optionalText(body, "turnId", "",
                            "INVALID_PARAMETERS", "turnId 无效", 400),
                    payloadJson,
                    RequestValues.optionalText(body, "idempotencyKey", "",
                            "INVALID_PARAMETERS", "idempotencyKey 无效", 400));
            return ResponseEntity.status(202).body(facade.view(draft));
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "action draft payload is invalid");
        }
    }

}
