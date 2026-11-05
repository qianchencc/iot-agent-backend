package ltd.qianc.iotagent.conversation.internal.controller;

import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.error.RequestValues;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 会话与消息路由；会话状态迁移全部在 ConversationFacade。
 * 请求契约（source/content/reference 的类型与错误码）见 docs/api/java-contract.md。
 */
@RestController
@RequestMapping("/api/agent/conversations")
public final class ConversationController {
    private final ConversationFacade facade;

    public ConversationController(ConversationFacade facade) {
        this.facade = facade;
    }

    /**
     * 创建会话。请求字段：{@code source}（可缺省为 office；出现即必须是字符串 office/company——
     * 数字、布尔等非法类型返回 400 INVALID_AGENT_SOURCE，不再落到 500）。
     * 返回 201 与会话 id。
     */
    @PostMapping
    public ResponseEntity<Map<String,Object>> create(@RequestBody(required=false) Map<String,Object> body) {
        String source = RequestValues.optionalText(body, "source", null,
                "INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        return ResponseEntity.status(201).body(facade.create(PrincipalContext.getPrincipalInfo(), source));
    }

    /** 当前会话；{@code source} 可缺省，非法值 400 INVALID_AGENT_SOURCE。 */
    @GetMapping("/current")
    public Map<String,Object> current(@RequestParam(required=false) String source){return facade.current(PrincipalContext.getPrincipalInfo(),source);}

    /** 清空会话；{@code source} 可缺省，非法值 400 INVALID_AGENT_SOURCE。 */
    @DeleteMapping("/{id}")
    public Map<String,Object> clear(@PathVariable String id,@RequestParam(required=false) String source){return facade.clear(PrincipalContext.getPrincipalInfo(),id,source);}

    /** 丢弃一轮对话；{@code source} 可缺省，非法值 400 INVALID_AGENT_SOURCE。 */
    @DeleteMapping("/{id}/turns/{turnId}")
    public Map<String,Object> discard(@PathVariable String id,@PathVariable String turnId,@RequestParam(required=false) String source){return facade.discard(PrincipalContext.getPrincipalInfo(),id,turnId,source);}

    /**
     * 发送消息（NDJSON 流）。
     * 请求字段：{@code content}（必需字符串，空值 400 INVALID_MESSAGE）、{@code focusRegionId}
     * （可缺省；出现即必须是字符串，否则 400 INVALID_PARAMETERS）、{@code reference}
     * （可缺省；出现即必须是对象，否则 400 INVALID_REFERENCE）；query {@code source} 可缺省。
     * Company 来源携带 focusRegionId 时返回 400 SOURCE_CONTEXT_UNAVAILABLE。
     */
    @PostMapping(value="/{id}/messages",produces="application/x-ndjson")
    public ResponseEntity<ResponseBodyEmitter> message(@PathVariable String id,@RequestParam(required=false) String source,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false) String key){
        Object raw = body == null ? null : body.get("content");
        String content = raw instanceof String text ? text : null;
        String focus = RequestValues.optionalText(body, "focusRegionId", null,
                "INVALID_PARAMETERS", "focusRegionId 无效", 400);
        Object referenceValue = body == null ? null : body.get("reference");
        if (referenceValue != null && !(referenceValue instanceof Map<?,?>)) {
            throw new BusinessException("INVALID_REFERENCE","引用格式无效",400);
        }
        @SuppressWarnings("unchecked")
        Map<String,Object> reference = referenceValue instanceof Map<?,?> map ? (Map<String,Object>) map : null;
        if ("company".equals(source) && focus != null) {
            throw new BusinessException("SOURCE_CONTEXT_UNAVAILABLE","Company 不支持 focusRegionId",400);
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson; charset=utf-8"))
                .body(facade.message(PrincipalContext.getPrincipalInfo(),id,content,key,source,focus,reference));
    }

    /** 取消运行中的回复流；{@code source} 可缺省，非法值 400 INVALID_AGENT_SOURCE。 */
    @PostMapping("/{id}/runs/cancel")
    public Map<String,Object> cancel(@PathVariable String id,@RequestParam(required=false) String source){return facade.cancel(PrincipalContext.getPrincipalInfo(),id,source);}
}
