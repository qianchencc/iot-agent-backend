package ltd.qianc.iotagent.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.conversation.ConversationEventFact;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import reactor.core.publisher.Mono;

/**
 * The only service allowed to move an Action Draft into execution. The draft
 * fact is the idempotency and CAS authority; the command gateway is an explicit
 * adapter seam. Production wiring remains fail-closed; fixture wiring is local-only
 * and enabled only by the explicit control mode.
 */
public final class ActionDraftFacade {
    private final ObjectMapper mapper;
    private final ActionDraftStore store;
    private final IotAdapter iot;
    private final ActionCommandGateway gateway;
    private final ConversationEventStore conversationEvents;
    private final AuditEventStore audit;
    private final ActionReceiptStore receiptStore;
    private final ActionReadbackStore readbackStore;
    private final Clock clock;
    private final Duration scopeTimeout;
    private final Duration draftTtl;

    public ActionDraftFacade(ObjectMapper mapper, ActionDraftStore store, IotAdapter iot,
                             ActionCommandGateway gateway, Clock clock, Duration scopeTimeout,
                             Duration draftTtl) {
        this(mapper, store, iot, gateway, null, null, null, null, clock, scopeTimeout, draftTtl);
    }

    public ActionDraftFacade(ObjectMapper mapper, ActionDraftStore store, IotAdapter iot,
                             ActionCommandGateway gateway, ConversationEventStore conversationEvents,
                             AuditEventStore audit, Clock clock, Duration scopeTimeout,
                             Duration draftTtl) {
        this(mapper, store, iot, gateway, conversationEvents, audit, null, null, clock, scopeTimeout, draftTtl);
    }

    public ActionDraftFacade(ObjectMapper mapper, ActionDraftStore store, IotAdapter iot,
                             ActionCommandGateway gateway, ConversationEventStore conversationEvents,
                             AuditEventStore audit, ActionReceiptStore receiptStore,
                             ActionReadbackStore readbackStore, Clock clock, Duration scopeTimeout,
                             Duration draftTtl) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.store = Objects.requireNonNull(store, "store");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.conversationEvents = conversationEvents;
        this.audit = audit;
        this.receiptStore = receiptStore;
        this.readbackStore = readbackStore;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scopeTimeout = requirePositive(scopeTimeout, "scopeTimeout");
        this.draftTtl = requirePositive(draftTtl, "draftTtl");
    }

    /** Materialize a proposal only after the current server-derived scope is checked. */
    public ActionDraftFact propose(PrincipalInfo principal, String source, String conversationId,
                                   String turnId, String payloadJson, String idempotencyKey) {
        Objects.requireNonNull(principal, "principal");
        IotSource iotSource = parseSource(source);
        if (conversationId == null || conversationId.isBlank() || turnId == null || turnId.isBlank()) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "conversationId and turnId are required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY", "Idempotency key is required");
        }
        String scopedKey = principal.tenantCode() + "\u0000" + ownerKey(principal) + "\u0000" + idempotencyKey;
        Optional<ActionDraftFact> prior = store.findByIdempotencyKey(scopedKey);
        if (prior.isPresent() && tenantOf(prior.get()).equals(principal.tenantCode())
                && ownerKey(principal).equals(payload(prior.get()).path("ownerKey").asText(""))) return prior.get();
        for (ActionDraftFact existing : store.findByConversation(conversationId)) {
            if (tenantOf(existing).equals(principal.tenantCode())
                    && (existing.status().equals(ActionDraftStatus.EXECUTING.name())
                    || existing.status().equals(ActionDraftStatus.PENDING_CONFIRMATION.name()) && !expired(existing))) {
                throw new ActionDraftBusyException("当前会话已有待确认草案");
            }
        }
        ObjectNode payload = object(payloadJson);
        AuthorizedDeviceScope scope = loadScope(principal, iotSource);
        ArrayNode actions = normalizeActions(payload, scope);
        if (actions.isEmpty()) throw new BusinessException("INVALID_ACTION_DRAFT", "Action draft must contain actions");
        ArrayNode selected = selectedIds(payload, actions);
        Instant now = clock.instant();
        payload.put("source", iotSource.wireValue());
        payload.put("tenantCode", principal.tenantCode());
        payload.put("ownerKey", ownerKey(principal));
        payload.put("conversationId", conversationId);
        payload.put("turnId", turnId);
        payload.put("expiresAt", now.plus(draftTtl).toString());
        payload.set("actions", actions);
        payload.set("originalActions", actions.deepCopy());
        payload.set("selectedActionIds", selected);
        payload.set("originalSelectedActionIds", selected.deepCopy());
        payload.put("status", ActionDraftStatus.PENDING_CONFIRMATION.name());
        payload.put("dispatchStarted", false);
        ActionDraftFact draft = new ActionDraftFact(UUID.randomUUID().toString(), 1,
                ActionDraftStatus.PENDING_CONFIRMATION.name(), json(payload), scopedKey);
        ActionDraftFact created;
        try {
            created = store.create(draft);
        } catch (RuntimeException duplicate) {
            Optional<ActionDraftFact> retry = store.findByIdempotencyKey(scopedKey);
            if (retry.isPresent() && tenantOf(retry.get()).equals(principal.tenantCode())
                    && ownerKey(principal).equals(payload(retry.get()).path("ownerKey").asText(""))) return retry.get();
            throw new ActionDraftUnavailableException("草案事实暂不可保存", duplicate);
        }
        emit(created, "action.proposed");
        audit(created, "ACTION_DRAFT_PROPOSED");
        return created;
    }

    public ActionDraftFact current(PrincipalInfo principal, String draftId) {
        ActionDraftFact draft = find(principal, draftId);
        if (draft.status().equals(ActionDraftStatus.PENDING_CONFIRMATION.name()) && expired(draft)) {
            expire(draft);
            return store.find(draft.draftId()).orElse(draft);
        }
        if (draft.status().equals(ActionDraftStatus.PENDING_CONFIRMATION.name())
                || draft.status().equals(ActionDraftStatus.EXECUTING.name())) {
            verifyScope(principal, payload(draft));
        }
        return draft;
    }

    public ActionDraftFact select(PrincipalInfo principal, String draftId, long revision, List<String> ids) {
        ActionDraftFact draft = pending(principal, draftId, revision);
        ObjectNode body = payload(draft);
        ArrayNode actions = array(body, "actions");
        Set<String> valid = actionIds(actions);
        if (ids == null || new HashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(id -> id == null || !valid.contains(id))) {
            throw new BusinessException("INVALID_SELECTION", "Invalid action selection");
        }
        ArrayNode selected = mapper.createArrayNode();
        ids.forEach(selected::add);
        body.set("selectedActionIds", selected);
        return replace(draft, body, draft.status());
    }

    public ActionDraftFact parameters(PrincipalInfo principal, String draftId, long revision,
                                      List<Map<String, Object>> changes) {
        ActionDraftFact draft = pending(principal, draftId, revision);
        if (changes == null || changes.isEmpty()) throw new BusinessException("INVALID_PARAMETERS", "参数修改不能为空");
        ObjectNode body = payload(draft);
        ArrayNode actions = array(body, "actions");
        Set<String> changed = new HashSet<>();
        for (Map<String, Object> change : changes) {
            if (change == null || !(change.get("actionId") instanceof String id) || !changed.add(id)) {
                throw new BusinessException("INVALID_PARAMETERS", "设备参数或目标设备无效");
            }
            ObjectNode action = findAction(actions, id);
            if (action == null || !(change.get("patch") instanceof Map<?, ?> patchMap)) {
                throw new BusinessException("INVALID_PARAMETERS", "设备参数或目标设备无效");
            }
            JsonNode patch = mapper.valueToTree(patchMap);
            if (!patch.isObject() || patch.size() == 0) throw new BusinessException("INVALID_PARAMETERS", "设备参数或目标设备无效");
            ObjectNode command = action.with("command");
            if ("off".equals(patch.path("power").asText(null))) {
                command.removeAll();
                command.put("power", "off");
            } else patch.fields().forEachRemaining(e -> command.set(e.getKey(), e.getValue()));
        }
        return replace(draft, body, draft.status());
    }

    public ActionDraftFact restore(PrincipalInfo principal, String draftId, long revision) {
        ActionDraftFact draft = pending(principal, draftId, revision);
        ObjectNode body = payload(draft);
        body.set("actions", array(body, "originalActions").deepCopy());
        body.set("selectedActionIds", array(body, "originalSelectedActionIds", array(body, "selectedActionIds")).deepCopy());
        body.putNull("question");
        return replace(draft, body, draft.status());
    }

    public ActionDraftFact revalidate(PrincipalInfo principal, String draftId, long revision) {
        ActionDraftFact draft = find(principal, draftId);
        if (!draft.status().equals(ActionDraftStatus.EXPIRED.name())
                && !(draft.status().equals(ActionDraftStatus.PENDING_CONFIRMATION.name()) && expired(draft))) {
            throw new ActionDraftExpiredException();
        }
        if (draft.revision() != revision) throw new ActionDraftRevisionConflictException();
        ObjectNode body = payload(draft);
        AuthorizedDeviceScope scope = verifyScope(principal, body);
        ArrayNode actions = normalizeActions(body, scope);
        body.set("actions", actions);
        body.set("selectedActionIds", retainSelected(array(body, "selectedActionIds"), actions));
        body.put("expiresAt", clock.instant().plus(draftTtl).toString());
        body.put("status", ActionDraftStatus.PENDING_CONFIRMATION.name());
        return replace(draft, body, ActionDraftStatus.PENDING_CONFIRMATION.name());
    }

    /** Confirm is CAS-gated before the fresh scope call, so concurrent confirms cannot dispatch twice. */
    public ActionDraftFact confirm(PrincipalInfo principal, String draftId, Long expectedRevision) {
        ActionDraftFact initial = find(principal, draftId);
        if (terminal(initial)) return initial;
        if (!initial.status().equals(ActionDraftStatus.PENDING_CONFIRMATION.name())) {
            throw new ActionDraftBusyException("Action draft is already executing");
        }
        if (expectedRevision != null && initial.revision() != expectedRevision) throw new ActionDraftRevisionConflictException();
        if (expired(initial)) {
            expire(initial);
            throw new ActionDraftExpiredException();
        }
        ObjectNode body = payload(initial);
        ActionDraftFact executing = cas(initial, body, ActionDraftStatus.EXECUTING.name());
        IotSource source = parseSource(body.path("source").asText(null));
        AuthorizedDeviceScope scope;
        try {
            scope = loadScope(principal, source);
            verifyActions(body, scope);
        } catch (RuntimeException error) {
            rollback(executing);
            throw error;
        }
        ArrayNode selected = array(body, "selectedActionIds");
        if (selected.isEmpty()) {
            rollback(executing);
            throw new BusinessException("EMPTY_ACTION_SELECTION", "Select at least one action", 422);
        }
        ArrayNode selectedActions = mapper.createArrayNode();
        Set<String> ids = actionIds(selected);
        for (JsonNode action : array(body, "actions")) if (ids.contains(action.path("id").asText())) selectedActions.add(action);
        if (selectedActions.size() != selected.size()) {
            rollback(executing);
            throw new BusinessException("UNSUPPORTED_ACTION_DRAFT", "Unsupported action draft", 422);
        }
        try {
            ActionDispatchResult result = gateway.dispatch(new RequestIdentity(principal.authorization(), principal.tenantCode(), source), scope, json(selectedActions));
            body.put("status", result.status().name());
            ObjectNode resultBody = object(result.resultJson());
            if (!resultBody.has("outcome")) resultBody.put("outcome", result.receiptOutcome().name());
            body.set("result", resultBody);
            body.put("dispatchStarted", result.dispatchStarted());
            ActionDraftFact done = cas(executing, body, result.status().name());
            persistExecutionFacts(done, resultBody);
            emit(done, "action.resolved");
            audit(done, "ACTION_DRAFT_" + result.status().name());
            return done;
        } catch (GatewayAuthorizationException error) {
            rollback(executing);
            throw error;
        } catch (ActionDispatchException error) {
            if (!error.dispatchStarted()) {
                rollback(executing);
                throw new ActionDraftUnavailableException("设备指令未能下发", error);
            }
            return interrupted(executing, body, error);
        } catch (ActionDraftUnavailableException error) {
            rollback(executing);
            throw error;
        } catch (RuntimeException error) {
            return interrupted(executing, body, error);
        }
    }

    public ActionDraftFact cancel(PrincipalInfo principal, String draftId) {
        ActionDraftFact draft = find(principal, draftId);
        if (terminal(draft)) return draft;
        if (!draft.status().equals(ActionDraftStatus.PENDING_CONFIRMATION.name())) {
            throw new ActionDraftBusyException("Action draft is already executing");
        }
        ObjectNode body = payload(draft);
        body.put("status", ActionDraftStatus.CANCELLED.name());
        ActionDraftFact cancelled = cas(draft, body, ActionDraftStatus.CANCELLED.name());
        emit(cancelled, "action.resolved");
        audit(cancelled, "ACTION_DRAFT_CANCELLED");
        return cancelled;
    }

    /** Stable HTTP projection; payload remains opaque except for server-owned fields. */
    public Map<String, Object> view(ActionDraftFact draft) {
        ObjectNode body = payload(draft);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("draftId", draft.draftId());
        view.put("status", draft.status());
        view.put("revision", draft.revision());
        view.put("expiresAt", body.path("expiresAt").asText(null));
        view.put("source", body.path("source").asText(null));
        view.put("conversationId", body.path("conversationId").asText(null));
        view.put("turnId", body.path("turnId").asText(null));
        view.put("actions", mapper.convertValue(array(body, "actions"), Object.class));
        view.put("selectedActionIds", mapper.convertValue(array(body, "selectedActionIds"), Object.class));
        view.put("result", body.has("result") ? mapper.convertValue(body.get("result"), Object.class) : null);
        return view;
    }

    /** Read durable post-dispatch evidence; an empty list means no readback was recorded. */
    public Map<String, Object> deviceStates(PrincipalInfo principal, String draftId) {
        ActionDraftFact draft = current(principal, draftId);
        List<Map<String, Object>> stateList = new ArrayList<>();
        Map<String, Object> deviceStates = new LinkedHashMap<>();
        Map<String, String> deviceByAction = actionDevices(draft);
        if (readbackStore != null) {
            for (ActionReadbackFact fact : readbackStore.findByDraft(draft.draftId())) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("actionId", fact.actionId());
                state.put("outcome", fact.outcome());
                state.put("observedAt", fact.observedAt());
                Object observedState = parseObject(fact.observedStateJson());
                state.put("observedState", observedState);
                stateList.add(state);
                String deviceId = deviceByAction.get(fact.actionId());
                if (deviceId != null && !deviceId.isBlank()) deviceStates.put(deviceId, observedState);
            }
        }
        // `states` is the original durable fact list. `deviceStates` is the
        // additive projection consumed by the mini-program's device cards.
        return Map.of("draftId", draft.draftId(), "states", stateList, "deviceStates", deviceStates);
    }

    private Map<String, String> actionDevices(ActionDraftFact draft) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonNode action : array(payload(draft), "actions")) {
            String actionId = action.path("id").asText("");
            String deviceId = action.path("deviceId").asText("");
            if (!actionId.isBlank() && !deviceId.isBlank()) result.put(actionId, deviceId);
        }
        return result;
    }

    /** Summary is derived from durable receipt/readback facts, never fabricated by the Java service. */
    public Map<String, Object> summary(PrincipalInfo principal, String draftId) {
        ActionDraftFact draft = current(principal, draftId);
        List<Map<String, Object>> receipts = new ArrayList<>();
        if (receiptStore != null) {
            for (ActionReceiptFact fact : receiptStore.findByDraft(draft.draftId())) {
                Map<String, Object> receipt = new LinkedHashMap<>();
                receipt.put("actionId", fact.actionId());
                receipt.put("outcome", fact.outcome().name());
                receipt.put("payload", parseObject(fact.payloadJson()));
                receipts.add(receipt);
            }
        }
        return Map.of("draftId", draft.draftId(), "status", draft.status(), "receipts", receipts,
                "readbacks", deviceStates(principal, draft.draftId()).get("states"));
    }

    private ActionDraftFact find(PrincipalInfo principal, String draftId) {
        ActionDraftFact draft = store.find(draftId).orElseThrow(() -> new BusinessException("ACTION_DRAFT_NOT_FOUND", "草案不存在", 404));
        if (!principal.tenantCode().equals(tenantOf(draft))
                || !ownerKey(principal).equals(payload(draft).path("ownerKey").asText(""))) {
            throw new BusinessException("ACTION_DRAFT_NOT_FOUND", "草案不存在", 404);
        }
        return draft;
    }

    private ActionDraftFact pending(PrincipalInfo principal, String id, long revision) {
        ActionDraftFact draft = find(principal, id);
        if (!draft.status().equals(ActionDraftStatus.PENDING_CONFIRMATION.name())) {
            if (expired(draft)) { expire(draft); throw new ActionDraftExpiredException(); }
            throw new ActionDraftBusyException("Action draft is not pending");
        }
        if (expired(draft)) { expire(draft); throw new ActionDraftExpiredException(); }
        if (draft.revision() != revision) throw new ActionDraftRevisionConflictException();
        return draft;
    }

    private ActionDraftFact replace(ActionDraftFact draft, ObjectNode body, String status) {
        body.put("status", status);
        ActionDraftFact replacement = new ActionDraftFact(
                draft.draftId(), draft.revision() + 1, status, json(body), draft.idempotencyKey());
        try {
            return store.update(draft.draftId(), draft.revision(), replacement);
        } catch (ActionDraftUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException conflict) {
            throw new ActionDraftBusyException("草案已被其他请求更新");
        }
    }

    private ActionDraftFact cas(ActionDraftFact draft, ObjectNode body, String status) {
        ActionDraftFact replacement = new ActionDraftFact(draft.draftId(), draft.revision() + 1, status, json(body), draft.idempotencyKey());
        try {
            return store.update(draft.draftId(), draft.revision(), replacement);
        } catch (ActionDraftUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException conflict) {
            throw new ActionDraftBusyException("草案已被其他请求更新");
        }
    }

    private void rollback(ActionDraftFact executing) {
        ObjectNode body = payload(executing);
        body.put("status", ActionDraftStatus.PENDING_CONFIRMATION.name());
        try {
            store.update(executing.draftId(), executing.revision(),
                    new ActionDraftFact(executing.draftId(), executing.revision() + 1, ActionDraftStatus.PENDING_CONFIRMATION.name(), json(body), executing.idempotencyKey()));
        } catch (ActionDraftUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException conflict) {
            throw new ActionDraftBusyException("草案状态无法恢复");
        }
    }

    private void expire(ActionDraftFact draft) {
        ObjectNode body = payload(draft);
        body.put("status", ActionDraftStatus.EXPIRED.name());
        try {
            store.update(draft.draftId(), draft.revision(),
                    new ActionDraftFact(draft.draftId(), draft.revision() + 1, ActionDraftStatus.EXPIRED.name(), json(body), draft.idempotencyKey()));
        } catch (ActionDraftUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException ignored) {
            // Another request may have won the expiry CAS; the caller still gets the typed expiry result.
        }
    }

    private ActionDraftFact interrupted(ActionDraftFact executing, ObjectNode body, RuntimeException error) {
        body.put("status", ActionDraftStatus.INTERRUPTED.name());
        body.put("dispatchStarted", true);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", ActionDraftStatus.INTERRUPTED.name());
        result.put("outcome", ActionReceiptOutcome.UNKNOWN.name());
        result.put("message", "执行中断，已下发指令结果未知，请先查询设备状态");
        ArrayNode receipts = mapper.createArrayNode();
        Set<String> selectedIds = actionIds(array(body, "selectedActionIds"));
        for (JsonNode action : array(body, "actions")) {
            if (!selectedIds.contains(action.path("id").asText())) continue;
            ObjectNode receipt = mapper.createObjectNode();
            receipt.put("actionId", action.path("id").asText());
            receipt.put("outcome", ActionReceiptOutcome.UNKNOWN.name());
            receipts.add(receipt);
        }
        result.set("receipts", receipts);
        ActionDraftFact done = cas(executing, body, ActionDraftStatus.INTERRUPTED.name());
        persistExecutionFacts(done, result);
        audit(done, "ACTION_DRAFT_INTERRUPTED");
        return done;
    }

    private void persistExecutionFacts(ActionDraftFact draft, ObjectNode result) {
        JsonNode receipts = result.get("receipts");
        if (receiptStore != null && receipts != null && receipts.isArray()) {
            for (JsonNode receipt : receipts) {
                String actionId = receipt.path("actionId").asText("");
                String outcome = receipt.path("outcome").asText("");
                if (actionId.isBlank() || outcome.isBlank()) continue;
                try {
                    receiptStore.append(new ActionReceiptFact(draft.draftId(), draft.revision(), actionId,
                            ActionReceiptOutcome.valueOf(outcome.toUpperCase(java.util.Locale.ROOT)), json(receipt)));
                } catch (IllegalArgumentException ignored) {
                    // An adapter cannot create an invalid durable outcome fact.
                }
            }
        }
        JsonNode readbacks = result.get("readbacks");
        if (readbackStore != null && readbacks != null && readbacks.isArray()) {
            for (JsonNode readback : readbacks) {
                String actionId = readback.path("actionId").asText("");
                if (actionId.isBlank()) continue;
                JsonNode observed = readback.get("observedState");
                readbackStore.append(new ActionReadbackFact(draft.draftId(), draft.revision(), actionId,
                        readback.path("outcome").asText("unconfirmed"),
                        json(observed == null ? mapper.createObjectNode() : observed),
                        readback.path("observedAt").asText(null)));
            }
        }
    }

    private AuthorizedDeviceScope verifyScope(PrincipalInfo principal, ObjectNode body) {
        AuthorizedDeviceScope scope = loadScope(principal, parseSource(body.path("source").asText(null)));
        verifyActions(body, scope);
        return scope;
    }

    private void verifyActions(ObjectNode body, AuthorizedDeviceScope scope) {
        for (JsonNode action : array(body, "actions")) {
            String id = action.path("deviceId").asText(action.path("id").asText(null));
            String kind = action.path("kind").asText(null);
            AuthorizedDevice device = scope.device(id).orElse(null);
            if (device == null || !device.kind().equals(kind) || device.observationOnly()) throw new ActionDraftScopeChangedException();
            validateCommand(kind, action.path("command"));
        }
    }

    private AuthorizedDeviceScope loadScope(PrincipalInfo principal, IotSource source) {
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), source);
        try {
            AuthorizedDeviceScope scope = iot.loadAuthorizedDeviceScope(identity).block(scopeTimeout);
            if (scope == null || !identity.equals(scope.identity()) || source != scope.source()) {
                throw new UpstreamUnavailableException("授权设备范围");
            }
            return scope;
        } catch (GatewayAuthorizationException error) {
            throw error;
        } catch (ActionDraftUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof GatewayAuthorizationException auth) throw auth;
            throw new ActionDraftUnavailableException("当前用户设备范围暂不可用", error);
        }
    }

    private void validateCommand(String kind, JsonNode command) {
        if (command == null || !command.isObject()) throw new BusinessException("INVALID_ACTION_DRAFT", "command is required", 422);
        switch (kind) {
            case "lighting" -> {
                boolean power = command.path("power").isTextual()
                        && Set.of("on", "off").contains(command.path("power").textValue());
                boolean switchValue = command.path("closeOrOpen").isIntegralNumber()
                        && (command.path("closeOrOpen").asInt() == 0 || command.path("closeOrOpen").asInt() == 1);
                if (!power && !switchValue) throw new BusinessException("UNSUPPORTED_ACTION_DRAFT", "照明指令参数无效", 422);
            }
            case "access" -> {
                if (!command.path("controlType").isIntegralNumber() || command.path("controlType").asInt() != 2) {
                    throw new BusinessException("UNSUPPORTED_ACTION_DRAFT", "门禁指令参数无效", 422);
                }
            }
            case "ac" -> {
                boolean valid = true;
                if (command.has("power")) valid &= command.path("power").isTextual() && Set.of("on", "off").contains(command.path("power").textValue());
                if (command.has("temperature")) valid &= command.path("temperature").isIntegralNumber()
                        && command.path("temperature").asInt() >= 16 && command.path("temperature").asInt() <= 30;
                if (command.has("mode")) valid &= command.path("mode").isTextual()
                        && Set.of("auto", "cool", "heat", "dry", "fan").contains(command.path("mode").textValue());
                if (command.has("fanSpeed")) valid &= command.path("fanSpeed").isTextual()
                        && Set.of("low", "high", "auto").contains(command.path("fanSpeed").textValue());
                if (!command.has("power") && !command.has("temperature") && !command.has("mode") && !command.has("fanSpeed")) valid = false;
                if (!valid) throw new BusinessException("UNSUPPORTED_ACTION_DRAFT", "空调指令参数无效", 422);
            }
            default -> throw new BusinessException("UNSUPPORTED_ACTION_DRAFT", "设备类型不可控", 422);
        }
    }

    private ArrayNode normalizeActions(ObjectNode payload, AuthorizedDeviceScope scope) {
        JsonNode raw = payload.get("actions");
        if (raw == null || !raw.isArray()) throw new BusinessException("INVALID_ACTION_DRAFT", "actions is required");
        ArrayNode normalized = mapper.createArrayNode();
        Set<String> actionIds = new HashSet<>();
        int index = 0;
        for (JsonNode item : raw) {
            if (!item.isObject()) throw new BusinessException("INVALID_ACTION_DRAFT", "action is invalid");
            ObjectNode action = (ObjectNode) item.deepCopy();
            String deviceId = action.path("deviceId").asText(action.path("id").asText(null));
            String kind = action.path("kind").asText(null);
            AuthorizedDevice device = scope.device(deviceId).orElse(null);
            if (device == null || !device.kind().equals(kind) || device.observationOnly()) throw new ActionDraftScopeChangedException();
            String actionId = action.path("id").asText(null);
            if (actionId == null || actionId.isBlank()) actionId = deviceId + ":" + index;
            if (!actionIds.add(actionId)) throw new BusinessException("INVALID_ACTION_DRAFT", "action id must be unique");
            action.put("id", actionId);
            action.put("deviceId", device.id());
            action.put("kind", device.kind());
            action.put("name", device.name());
            action.put("location", device.installAddress());
            if (!action.has("command") || !action.get("command").isObject()) throw new BusinessException("INVALID_ACTION_DRAFT", "command is required");
            validateCommand(kind, action.path("command"));
            normalized.add(action);
            index++;
        }
        return normalized;
    }

    private ArrayNode selectedIds(ObjectNode payload, ArrayNode actions) {
        JsonNode value = payload.get("selectedActionIds");
        if (value == null) {
            ArrayNode all = mapper.createArrayNode();
            actions.forEach(a -> all.add(a.path("id").asText()));
            return all;
        }
        if (!value.isArray() || value.size() == 0) throw new BusinessException("INVALID_SELECTION", "Invalid action selection");
        Set<String> ids = actionIds(actions);
        ArrayNode selected = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        value.forEach(v -> { if (!v.isTextual() || !ids.contains(v.textValue()) || !seen.add(v.textValue())) throw new BusinessException("INVALID_SELECTION", "Invalid action selection"); selected.add(v.textValue()); });
        return selected;
    }

    private ArrayNode retainSelected(ArrayNode selected, ArrayNode actions) {
        Set<String> ids = actionIds(actions);
        ArrayNode result = mapper.createArrayNode();
        selected.forEach(id -> { if (ids.contains(id.asText())) result.add(id.asText()); });
        return result;
    }

    private ObjectNode findAction(ArrayNode actions, String id) {
        for (JsonNode action : actions) if (id.equals(action.path("id").asText())) return (ObjectNode) action;
        return null;
    }

    private Set<String> actionIds(JsonNode actions) {
        Set<String> ids = new HashSet<>();
        actions.forEach(a -> ids.add(a.isTextual() ? a.textValue() : a.path("id").asText()));
        return ids;
    }

    private ObjectNode payload(ActionDraftFact draft) { return object(draft.payloadJson()); }

    private ObjectNode object(String json) {
        try {
            JsonNode node = mapper.readTree(json == null ? "{}" : json);
            if (node == null || !node.isObject()) throw new BusinessException("INVALID_ACTION_DRAFT", "草案内容必须是对象");
            return (ObjectNode) node;
        } catch (JsonProcessingException error) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "草案内容无效");
        }
    }

    private String json(JsonNode node) {
        try { return mapper.writeValueAsString(node); }
        catch (JsonProcessingException error) { throw new IllegalStateException("Unable to serialize action draft", error); }
    }

    private ArrayNode array(ObjectNode body, String name) { return array(body, name, mapper.createArrayNode()); }
    private ArrayNode array(ObjectNode body, String name, ArrayNode fallback) {
        JsonNode node = body.get(name);
        return node != null && node.isArray() ? (ArrayNode) node : fallback;
    }

    private boolean expired(ActionDraftFact draft) {
        JsonNode expires = payload(draft).get("expiresAt");
        return expires != null && expires.isTextual() && Instant.parse(expires.textValue()).isBefore(clock.instant());
    }

    private boolean terminal(ActionDraftFact draft) {
        try { return ActionDraftStatus.valueOf(draft.status()).terminal(); }
        catch (IllegalArgumentException error) { return false; }
    }

    private String tenantOf(ActionDraftFact draft) { return payload(draft).path("tenantCode").asText(""); }

    private static String ownerKey(PrincipalInfo principal) {
        return principal.ownerKey();
    }

    private IotSource parseSource(String value) {
        try { return IotSource.parse(value); }
        catch (RuntimeException error) { throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效"); }
    }

    private Object parseObject(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException error) {
            throw new ActionDraftUnavailableException("草案执行事实格式无效", error);
        }
    }
    private void emit(ActionDraftFact draft, String type) {
        if (conversationEvents == null) return;
        ObjectNode event = mapper.createObjectNode();
        event.put("type", type);
        event.put("draftId", draft.draftId());
        event.put("status", draft.status());
        event.put("revision", draft.revision());
        ObjectNode body = payload(draft);
        if (body.has("conversationId")) event.put("conversationId", body.path("conversationId").asText());
        String conversationId = body.path("conversationId").asText("");
        String turnId = body.path("turnId").asText("");
        synchronized (conversationEvents) {
            long sequence = conversationEvents.read(conversationId).stream().filter(e -> turnId.equals(e.turnId())).mapToLong(ConversationEventFact::sequence).max().orElse(0) + 1;
            conversationEvents.append(new ConversationEventFact(conversationId, turnId, sequence, type, json(event), draft.draftId() + ":" + type + ":" + draft.revision()));
        }
    }

    private void audit(ActionDraftFact draft, String type) {
        if (audit == null) return;
        audit.append(new AuditEventFact(UUID.randomUUID().toString(), tenantOf(draft), type, draft.payloadJson()));
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
