package ltd.qianc.iotagent.action.internal.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ltd.qianc.iotagent.action.ActionDraftFacade;
import ltd.qianc.iotagent.auth.PrincipalContext;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.error.RequestValues;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime-compatible action draft routes; control execution remains behind the facade.
 * 请求契约（source/revision/changes 的类型与错误码）见 docs/api/java-contract.md。
 */
@RestController
@RequestMapping("/api/agent/action-drafts")
public final class ActionDraftController {
    private final ActionDraftFacade facade;

    public ActionDraftController(ActionDraftFacade facade) {
        this.facade = facade;
    }

    /** 当前草案投影；query 参数 {@code source}（可缺省）必须是 office/company，否则 400 INVALID_AGENT_SOURCE。 */
    @GetMapping("/{id}")
    public Map<String, Object> current(@PathVariable String id, @RequestParam(required = false) String source) {
        return facade.view(source == null ? facade.current(PrincipalContext.getPrincipalInfo(), id)
                : facade.current(PrincipalContext.getPrincipalInfo(), id, source));
    }

    /** 草案覆盖设备的当前状态；{@code source} 同上。404 ACTION_DRAFT_NOT_FOUND 表示草案不存在或不属于当前身份。 */
    @GetMapping("/{id}/device-states")
    public Map<String, Object> deviceStates(@PathVariable String id, @RequestParam(required = false) String source) {
        return source == null ? facade.deviceStates(PrincipalContext.getPrincipalInfo(), id)
                : facade.deviceStates(PrincipalContext.getPrincipalInfo(), id, source);
    }

    /** 执行回执/回读摘要（durable receipt facts）；{@code source} 同上。 */
    @GetMapping("/{id}/summary")
    public Map<String, Object> summary(@PathVariable String id, @RequestParam(required = false) String source) {
        return source == null ? facade.summary(PrincipalContext.getPrincipalInfo(), id)
                : facade.summary(PrincipalContext.getPrincipalInfo(), id, source);
    }

    /** 与 GET summary 同一投影；草案执行后重新拉取 durable 事实。 */
    @PostMapping("/{id}/summary")
    public Map<String, Object> regenerateSummary(@PathVariable String id, @RequestParam(required = false) String source) {
        return source == null ? facade.summary(PrincipalContext.getPrincipalInfo(), id)
                : facade.summary(PrincipalContext.getPrincipalInfo(), id, source);
    }

    /**
     * 更新选中的动作集合。
     * 请求字段：{@code revision}（必需，>=0 的无损整数）、{@code selectedActionIds}（字符串数组；
     * 仅键真正缺失可缺省为清空选择，显式 null/非数组返回 400）。
     * 错误码：400 INVALID_PARAMETERS（revision 缺失/非法，含小数、字符串、溢出）、
     * 400 INVALID_SELECTION（selectedActionIds 非法、元素非字符串或引用不存在的动作）、409 草案已被并发更新。
     */
    @PatchMapping("/{id}/selection")
    public Map<String, Object> select(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return facade.view(facade.select(PrincipalContext.getPrincipalInfo(), id, revision(body), strings(body)));
    }

    /**
     * 批量编辑动作指令（lighting closeOrOpen/power、ac power/temperature/mode/fanSpeed）。
     * 请求字段：{@code revision}（必需，>=0 的无损整数）、{@code changes}（必需的对象数组：
     * 每元素含 actionId 与 patch，逐元素校验，任一非法则整批拒绝；缺失/显式 null/非数组是 400）。
     * 错误码：400 INVALID_PARAMETERS（revision/changes 结构非法）、422 INVALID_PARAMETERS
     * 或 UNSUPPORTED_ACTION_DRAFT（patch 值越界或小数/字符串/溢出；所有编辑先校验后落库，不会半应用）。
     */
    @PatchMapping("/{id}/parameters")
    public Map<String, Object> parameters(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("changes");
        if (!(raw instanceof List<?> list)) {
            // changes 必需且必须是数组：缺失/显式 null/对象在这里就是 400 参数错误，
            // 不依赖草案存在性或存储可用性。
            throw new BusinessException("INVALID_PARAMETERS", "参数修改不能为空", 400);
        }
        List<Map<String, Object>> changes = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> change)) {
                throw new BusinessException("INVALID_PARAMETERS", "设备参数或目标设备无效", 400);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) change;
            changes.add(typed);
        }
        return facade.view(facade.parameters(PrincipalContext.getPrincipalInfo(), id, revision(body), changes));
    }

    /**
     * 恢复到提案时的原始动作/选择/清单。
     * 请求字段：{@code revision}（必需，>=0 的无损整数）。
     * 错误码：400 INVALID_PARAMETERS（revision 缺失/非法）；409 修订冲突。
     */
    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.view(facade.restore(PrincipalContext.getPrincipalInfo(), id, revision(body)));
    }

    /**
     * 过期草案重核验：读取新鲜状态生成新草案并消费旧草案（旧 id 不可再执行）。
     * 请求字段：{@code revision}（必需，>=0 的无损整数）。
     * 错误码：400 INVALID_PARAMETERS；409 修订冲突；410 草案已过期且不可重验。
     */
    @PostMapping("/{id}/revalidate")
    public Map<String, Object> revalidate(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return facade.view(facade.revalidate(PrincipalContext.getPrincipalInfo(), id, revision(body)));
    }

    /**
     * 确认并下发（CAS 先于任何上游调用）。
     * 请求字段：{@code revision}（可缺省；出现即必须是 >=0 的无损整数——小数、字符串、溢出返回
     * 400 INVALID_PARAMETERS，绝不截断）、query {@code source}（可缺省）。
     * 返回：200 扁平执行结果；502 当结果状态为 REJECTED/INTERRUPTED（部分指令已下发）。
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String id,
                                                       @RequestParam(required = false) String source,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        var principal = PrincipalContext.getPrincipalInfo();
        var draft = source == null ? facade.confirm(principal, id, optionalRevision(body))
                : facade.confirm(principal, id, source, optionalRevision(body));
        int status = "REJECTED".equals(draft.status()) || "INTERRUPTED".equals(draft.status()) ? 502 : 200;
        // Confirm answers with the flat execution result the client acts on; the draft
        // wrapper stays on the ordinary draft reads.
        return ResponseEntity.status(status).body(facade.executionResult(draft));
    }

    /** 取消待确认草案（幂等：终态草案原样返回）。 */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        return facade.view(facade.cancel(PrincipalContext.getPrincipalInfo(), id));
    }

    private static long revision(Map<String, Object> body) {
        if (body == null || !body.containsKey("revision")) {
            throw new BusinessException("INVALID_PARAMETERS", "revision is required", 400);
        }
        return RequestValues.requiredLong(body, "revision", 0, "INVALID_PARAMETERS", "revision is invalid", 400);
    }

    private static Long optionalRevision(Map<String, Object> body) {
        return RequestValues.optionalLong(body, "revision", 0, "INVALID_PARAMETERS", "revision is invalid", 400);
    }

    /** 仅键真正缺失时视为清空选择；显式 null/字符串/对象或非字符串元素都是 400 INVALID_SELECTION。 */
    private static List<String> strings(Map<String, Object> body) {
        if (body == null || !body.containsKey("selectedActionIds")) return List.of();
        Object value = body.get("selectedActionIds");
        if (value == null || !(value instanceof List<?> list)) {
            throw new BusinessException("INVALID_SELECTION", "Invalid action selection", 400);
        }
        List<String> ids = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String id)) throw new BusinessException("INVALID_SELECTION", "Invalid action selection", 400);
            ids.add(id);
        }
        return ids;
    }
}
