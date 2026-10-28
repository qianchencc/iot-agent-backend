package ltd.qianc.iotagent.action.internal.controller;

import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runtime-compatible action draft routes; control execution remains behind the facade. */
@RestController
@RequestMapping("/api/agent/action-drafts")
public final class ActionDraftController {
    private final ActionDraftFacade facade;

    public ActionDraftController(ActionDraftFacade facade) {
        this.facade = facade;
    }

    @GetMapping("/{id}")
    public Map<String, Object> current(@PathVariable String id) {
        return facade.view(facade.current(PrincipalContext.getPrincipalInfo(), id));
    }
    @GetMapping("/{id}/device-states")
    public Map<String, Object> deviceStates(@PathVariable String id) {
        return facade.deviceStates(PrincipalContext.getPrincipalInfo(), id);
    }

    @GetMapping("/{id}/summary")
    public Map<String, Object> summary(@PathVariable String id) {
        return facade.summary(PrincipalContext.getPrincipalInfo(), id);
    }

    @PostMapping("/{id}/summary")
    public Map<String, Object> regenerateSummary(@PathVariable String id) {
        return facade.summary(PrincipalContext.getPrincipalInfo(), id);
    }


    @PatchMapping("/{id}/selection")
    public Map<String, Object> select(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return facade.view(facade.select(PrincipalContext.getPrincipalInfo(), id, revision(body), strings(body.get("selectedActionIds"))));
    }

    @PatchMapping("/{id}/parameters")
    public Map<String, Object> parameters(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object raw = body.get("changes");
        if (!(raw instanceof List<?> list)) {
            return facade.view(facade.parameters(PrincipalContext.getPrincipalInfo(), id, revision(body), List.of()));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changes = (List<Map<String, Object>>) (List<?>) list;
        return facade.view(facade.parameters(PrincipalContext.getPrincipalInfo(), id, revision(body), changes));
    }

    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.view(facade.restore(PrincipalContext.getPrincipalInfo(), id, revision(body)));
    }

    @PostMapping("/{id}/revalidate")
    public Map<String, Object> revalidate(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.view(facade.revalidate(PrincipalContext.getPrincipalInfo(), id, revision(body)));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        var draft = facade.confirm(PrincipalContext.getPrincipalInfo(), id, optionalRevision(body));
        int status = "REJECTED".equals(draft.status()) || "INTERRUPTED".equals(draft.status()) ? 502 : 200;
        return ResponseEntity.status(status).body(facade.view(draft));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        return facade.view(facade.cancel(PrincipalContext.getPrincipalInfo(), id));
    }

    private static long revision(Map<String, Object> body) {
        Long value = optionalRevision(body);
        if (value == null) throw new IllegalArgumentException("revision is required");
        return value;
    }

    private static Long optionalRevision(Map<String, Object> body) {
        if (body == null || body.get("revision") == null) return null;
        Object value = body.get("revision");
        if (value instanceof Number number && number.longValue() >= 0) return number.longValue();
        throw new IllegalArgumentException("revision is invalid");
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
