package ltd.qianc.iotagent.conversation.internal.controller;

import java.util.Map;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.conversation.ConversationFacade;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@RestController
@RequestMapping("/api/agent/conversations")
public final class ConversationController {
    private final ConversationFacade facade;
    public ConversationController(ConversationFacade facade){this.facade=facade;}
    @PostMapping public ResponseEntity<Map<String,Object>> create(@RequestBody(required=false) Map<String,Object> body){String source=body==null?null:(String)body.get("source");return ResponseEntity.status(201).body(facade.create(PrincipalContext.getPrincipalInfo(),source));}
    @GetMapping("/current") public Map<String,Object> current(@RequestParam(required=false) String source){return facade.current(PrincipalContext.getPrincipalInfo(),source);}
    @DeleteMapping("/{id}") public Map<String,Object> clear(@PathVariable String id,@RequestParam(required=false) String source){return facade.clear(PrincipalContext.getPrincipalInfo(),id,source);}
    @DeleteMapping("/{id}/turns/{turnId}") public Map<String,Object> discard(@PathVariable String id,@PathVariable String turnId,@RequestParam(required=false) String source){return facade.discard(PrincipalContext.getPrincipalInfo(),id,turnId,source);}
    @PostMapping(value="/{id}/messages",produces="application/x-ndjson") public ResponseEntity<ResponseBodyEmitter> message(@PathVariable String id,@RequestParam(required=false) String source,@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false) String key){Object raw=body==null?null:body.get("content");String content=raw instanceof String text?text:null;String focus=body==null?null:(body.get("focusRegionId") instanceof String text?text:null);Object referenceValue=body==null?null:body.get("reference");if(referenceValue!=null&&!(referenceValue instanceof Map<?,?>))throw new ltd.qianc.iotagent.error.BusinessException("INVALID_REFERENCE","引用格式无效",400);@SuppressWarnings("unchecked") Map<String,Object> reference=referenceValue instanceof Map<?,?> map?(Map<String,Object>)map:null;if("company".equals(source)&&focus!=null)throw new ltd.qianc.iotagent.error.BusinessException("SOURCE_CONTEXT_UNAVAILABLE","Company 不支持 focusRegionId",400);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson; charset=utf-8")).body(facade.message(PrincipalContext.getPrincipalInfo(),id,content,key,source,focus,reference));}
    @PostMapping("/{id}/runs/cancel") public Map<String,Object> cancel(@PathVariable String id,@RequestParam(required=false) String source){return facade.cancel(PrincipalContext.getPrincipalInfo(),id,source);}
}
