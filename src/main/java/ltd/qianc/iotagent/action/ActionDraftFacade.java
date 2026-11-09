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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ltd.qianc.iotagent.action.internal.ActionResultProjector;
import ltd.qianc.iotagent.audit.AuditEventFact;
import ltd.qianc.iotagent.audit.AuditEventStore;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.auth.ScopedKey;
import ltd.qianc.iotagent.conversation.ConversationEventFact;
import ltd.qianc.iotagent.conversation.ConversationEventStore;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AcCommandCapabilities;
import ltd.qianc.iotagent.iot.DeviceCommands;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.DeviceCandidateSetStore;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.GatewayAuthorizationException;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.OfficeRegion;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;
import ltd.qianc.iotagent.iot.UpstreamUnavailableException;
import ltd.qianc.iotagent.readmodel.ReadSnapshots;
import reactor.core.publisher.Mono;

/**
 * The only service allowed to move an Action Draft into execution. The draft
 * fact is the idempotency and CAS authority; the command gateway is an explicit
 * adapter seam. Production wiring remains fail-closed; fixture wiring is local-only
 * and enabled only by the explicit control mode.
 */
public final class ActionDraftFacade implements ltd.qianc.iotagent.conversation.ConversationDrafts {
    private static final Duration ORDINARY_STATE_TIMEOUT = Duration.ofSeconds(120);
    private static final java.util.regex.Pattern DEVICE_HANDLE = java.util.regex.Pattern.compile("^d(\\d+)_\\d+$");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ActionDraftFacade.class);
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> RESULT_TYPE =
            new com.fasterxml.jackson.core.type.TypeReference<>() { };
    private final ObjectMapper mapper;
    private final ActionDraftStore store;
    private final IotAdapter iot;
    private final DeviceCandidateSetStore candidates;
    private final ActionCommandGateway gateway;
    private final ConversationEventStore conversationEvents;
    private final AuditEventStore audit;
    private final ActionReceiptStore receiptStore;
    private final ActionReadbackStore readbackStore;
    private final ReadSnapshots snapshots;
    private final ImmediateActionHistory history;
    private final Clock clock;
    private final Duration scopeTimeout;
    private final Duration draftTtl;
    private final ActionResultProjector results;

    public ActionDraftFacade(ObjectMapper mapper, ActionDraftStore store, IotAdapter iot,
                             ActionCommandGateway gateway, ConversationEventStore conversationEvents,
                             AuditEventStore audit, ActionReceiptStore receiptStore,
                             ActionReadbackStore readbackStore, ReadSnapshots snapshots,
                             Clock clock, Duration scopeTimeout, Duration draftTtl, ImmediateActionHistory history) {
        this(mapper, store, iot, null, gateway, conversationEvents, audit, receiptStore, readbackStore,
                snapshots, clock, scopeTimeout, draftTtl, history);
    }

    public ActionDraftFacade(ObjectMapper mapper, ActionDraftStore store, IotAdapter iot,
                             DeviceCandidateSetStore candidates, ActionCommandGateway gateway,
                             ConversationEventStore conversationEvents, AuditEventStore audit,
                             ActionReceiptStore receiptStore, ActionReadbackStore readbackStore,
                             ReadSnapshots snapshots,
                             Clock clock, Duration scopeTimeout, Duration draftTtl, ImmediateActionHistory history) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.store = Objects.requireNonNull(store, "store");
        this.iot = Objects.requireNonNull(iot, "iot");
        this.candidates = candidates;
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.conversationEvents = conversationEvents;
        this.audit = audit;
        this.receiptStore = receiptStore;
        this.readbackStore = readbackStore;
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.history = history;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scopeTimeout = requirePositive(scopeTimeout, "scopeTimeout");
        this.draftTtl = requirePositive(draftTtl, "draftTtl");
        this.results = new ActionResultProjector(mapper);
    }

    /** Materialize a proposal only after the current server-derived scope is checked. */
    public ActionDraftFact propose(PrincipalInfo principal, String source, String conversationId,
                                   String turnId, String payloadJson, String idempotencyKey) {
        return propose(principal, source, conversationId, turnId, payloadJson, idempotencyKey, null);
    }

    /**
     * Materializes from server-owned candidate sets. {@code focusRegionId} must come
     * from the live Office run context, never from model/client text.
     */
    public ActionDraftFact propose(PrincipalInfo principal, String source, String conversationId,
                                   String turnId, String payloadJson, String idempotencyKey,
                                   String focusRegionId) {
        Objects.requireNonNull(principal, "principal");
        IotSourceEnum iotSource = parseSource(source);
        if (conversationId == null || conversationId.isBlank() || turnId == null || turnId.isBlank()) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "conversationId and turnId are required");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY", "Idempotency key is required");
        }
        String scopedKey = ScopedKey.of(principal.tenantCode(), ownerKey(principal),
                iotSource.wireValue(), idempotencyKey);
        Optional<ActionDraftFact> prior = store.findByIdempotencyKey(scopedKey);
        if (prior.isPresent() && tenantOf(prior.get()).equals(principal.tenantCode())
                && ownerKey(principal).equals(payload(prior.get()).path("ownerKey").asText(""))
                && iotSource.wireValue().equals(payload(prior.get()).path("source").asText(""))) return prior.get();
        for (ActionDraftFact existing : store.findByConversation(conversationId)) {
            if (tenantOf(existing).equals(principal.tenantCode())
                    && iotSource.wireValue().equals(payload(existing).path("source").asText(""))
                    && (existing.status().equals(ActionDraftStatusEnum.EXECUTING.name())
                    || existing.status().equals(ActionDraftStatusEnum.PENDING_CONFIRMATION.name()) && !expired(existing))) {
                throw new ActionDraftBusyException("当前会话已有待确认草案");
            }
        }
        ObjectNode payload = object(payloadJson);
        AuthorizedDeviceScope catalogScope = loadScope(principal, iotSource, false);
        AuthorizedDeviceScope scope = catalogScope;
        if (iotSource == IotSourceEnum.OFFICE && focusRegionId != null && !focusRegionId.isBlank()) {
            scope = focusScope(principal, iotSource, scope, focusRegionId);
            payload.put("focusRegionId", focusRegionId);
        }
        Page page = materialize(payload, scope, catalogScope, principal, conversationId, iotSource);
        Instant now = clock.instant();
        payload.put("source", iotSource.wireValue());
        payload.put("tenantCode", principal.tenantCode());
        payload.put("ownerKey", ownerKey(principal));
        payload.put("principalId", principal.principalId());
        payload.put("identityRealm", principal.identityRealm());
        payload.put("conversationId", conversationId);
        payload.put("turnId", turnId);
        payload.put("expiresAt", now.plus(draftTtl).toString());
        payload.set("actions", page.actions());
        payload.set("originalActions", page.actions().deepCopy());
        payload.set("selectedActionIds", page.selected());
        payload.set("originalSelectedActionIds", page.selected().deepCopy());
        payload.set("inventory", page.inventory());
        payload.set("originalInventory", page.inventory().deepCopy());
        payload.set("counts", page.counts());
        payload.set("originalCounts", page.counts().deepCopy());
        payload.put("summary", page.summary());
        payload.put("originalSummary", page.summary());
        if (page.question() == null) {
            payload.putNull("question");
            payload.putNull("originalQuestion");
        } else {
            payload.put("question", page.question());
            payload.put("originalQuestion", page.question());
        }
        stateQuality(payload, page.observations());
        String status = page.counts().path("satisfied").asInt() == page.actions().size()
                ? ActionDraftStatusEnum.NO_CHANGE.name() : ActionDraftStatusEnum.PENDING_CONFIRMATION.name();
        payload.put("status", status);
        payload.put("dispatchStarted", false);
        ActionDraftFact draft = new ActionDraftFact(UUID.randomUUID().toString(), 1,
                status, json(payload), scopedKey);
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

    private AuthorizedDeviceScope focusScope(PrincipalInfo principal, IotSourceEnum source,
                                             AuthorizedDeviceScope scope, String focusRegionId) {
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), source, ownerKey(principal));
        List<OfficeRegion> regions;
        try {
            regions = iot.loadOfficeRegions(identity).block(scopeTimeout);
        } catch (GatewayAuthorizationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ActionDraftUnavailableException("当前关注区域暂不可用", error);
        }
        if (regions == null || regions.stream().noneMatch(region -> region.id().equals(focusRegionId))) {
            throw new BusinessException("FOCUS_REGION_UNAVAILABLE", "当前关注区域不在用户授权范围内", 403);
        }
        return scope.retain(device -> device.officeIds().contains(focusRegionId));
    }

    public ActionDraftFact current(PrincipalInfo principal, String draftId) {
        ActionDraftFact draft = find(principal, draftId);
        return currentChecked(draft);
    }

    public ActionDraftFact current(PrincipalInfo principal, String draftId, String source) {
        ActionDraftFact draft = find(principal, draftId, parseSource(source));
        return currentChecked(draft);
    }

    private ActionDraftFact currentChecked(ActionDraftFact draft) {
        if (draft.status().equals(ActionDraftStatusEnum.PENDING_CONFIRMATION.name()) && expired(draft)) {
            return expire(draft);
        }
        return draft;
    }

    /** Current, owner-bound draft projections for a verified conversation replay. */
    public Map<String, Map<String, Object>> conversationDrafts(PrincipalInfo principal, String conversationId, String source) {
        IotSourceEnum expected = parseSource(source);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (ActionDraftFact draft : store.findByConversation(conversationId)) {
            ObjectNode body = payload(draft);
            if (principal.tenantCode().equals(tenantOf(draft))
                    && ownerKey(principal).equals(body.path("ownerKey").asText(""))
                    && expected.wireValue().equals(body.path("source").asText(""))
                    && conversationId.equals(body.path("conversationId").asText(""))) {
                if (draft.status().equals(ActionDraftStatusEnum.PENDING_CONFIRMATION.name()) && expired(draft)) {
                    draft = expire(draft);
                }
                result.put(draft.draftId(), view(draft));
            }
        }
        return result;
    }

    /** A new expression supersedes pending/expired cards, never an executing or confirmed action. */
    public List<String> retireForConversation(PrincipalInfo principal, String conversationId, String source) {
        String expected = parseSource(source).wireValue();
        List<String> retired = new ArrayList<>();
        for (ActionDraftFact draft : store.findByConversation(conversationId)) {
            ObjectNode body = payload(draft);
            if (!principal.tenantCode().equals(tenantOf(draft)) || !ownerKey(principal).equals(body.path("ownerKey").asText())
                    || !expected.equals(body.path("source").asText()) || !conversationId.equals(body.path("conversationId").asText())) continue;
            if (!ActionDraftStatusEnum.PENDING_CONFIRMATION.name().equals(draft.status())
                    && !ActionDraftStatusEnum.EXPIRED.name().equals(draft.status())) continue;
            body.put("status", ActionDraftStatusEnum.CANCELLED.name());
            ActionDraftFact cancelled = cas(draft, body, ActionDraftStatusEnum.CANCELLED.name());
            emit(cancelled, "action.resolved");
            audit(cancelled, "ACTION_DRAFT_CANCELLED");
            retired.add(cancelled.draftId());
        }
        return retired;
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
        refreshInventory(body);
        return replace(draft, body, draft.status());
    }

    public ActionDraftFact parameters(PrincipalInfo principal, String draftId, long revision,
                                      List<Map<String, Object>> changes) {
        ActionDraftFact draft = pending(principal, draftId, revision);
        if (changes == null || changes.isEmpty()) throw new BusinessException("INVALID_PARAMETERS", "参数修改不能为空");
        ObjectNode body = payload(draft);
        ArrayNode actions = array(body, "actions");
        Set<String> changed = new HashSet<>();
        Map<String, ObjectNode> commands = new LinkedHashMap<>();
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
            commands.put(id, editCommand(action, patch));
        }
        // All edits validated before any action is mutated: an invalid 15/31 cannot half-apply.
        for (Map.Entry<String, ObjectNode> entry : commands.entrySet()) {
            findAction(actions, entry.getKey()).set("command", entry.getValue());
        }
        refreshInventory(body);
        return replace(draft, body, draft.status());
    }

    private ObjectNode editCommand(ObjectNode action, JsonNode patch) {
        String kind = action.path("kind").asText("");
        if ("lighting".equals(kind)) {
            for (Iterator<String> names = patch.fieldNames(); names.hasNext(); ) {
                if (!Set.of("closeOrOpen", "power").contains(names.next())) {
                    throw new BusinessException("INVALID_PARAMETERS", "照明目标参数无效", 422);
                }
            }
            int closeOrOpen;
            try {
                closeOrOpen = patch.has("closeOrOpen")
                        ? DeviceCommands.lightingCloseOrOpen(patch.get("closeOrOpen"))
                        : DeviceCommands.closeOrOpenFromPower(patch.path("power"));
            } catch (IllegalArgumentException invalid) {
                throw new BusinessException("INVALID_PARAMETERS", "照明目标无效", 422);
            }
            return mapper.createObjectNode().put("closeOrOpen", closeOrOpen);
        }
        if (!"ac".equals(kind)) {
            throw new BusinessException("INVALID_PARAMETERS", "该设备类型不支持参数调整", 422);
        }
        ObjectNode command = mapper.createObjectNode();
        JsonNode existing = action.path("command");
        for (Iterator<String> names = patch.fieldNames(); names.hasNext(); ) {
            if (!Set.of("power", "temperature", "mode", "fanSpeed").contains(names.next())) {
                throw new BusinessException("INVALID_PARAMETERS", "设备参数或目标设备无效", 422);
            }
        }
        if ("off".equals(patch.path("power").asText(null))) {
            // 显式 off 只能单独出现：先整体校验 patch，off 混排的非法字段不再被静默丢弃。
            if (patch.size() != 1) {
                throw new BusinessException("INVALID_PARAMETERS", "设备参数或目标设备无效", 422);
            }
            command.put("power", "off");
            validateCommand("ac", command);
            return command;
        }
        if (existing.isObject()) existing.fields().forEachRemaining(entry -> command.set(entry.getKey(), entry.getValue()));
        for (Iterator<String> names = patch.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!Set.of("power", "temperature", "mode", "fanSpeed").contains(name)) {
                throw new BusinessException("INVALID_PARAMETERS", "设备参数或目标设备无效", 422);
            }
            command.set(name, patch.get(name));
        }
        validateCommand("ac", command);
        return command;
    }

    /**
     * Recomputes selection/satisfaction and the three-way counts after an edit.
     * Excluded and untouched rows stay untouched.
     */
    private void refreshInventory(ObjectNode body) {
        ArrayNode actions = array(body, "actions");
        ArrayNode inventory = array(body, "inventory");
        ArrayNode selected = array(body, "selectedActionIds");
        Set<String> selectedIds = actionIds(selected);
        Map<String, JsonNode> actionsByDevice = new LinkedHashMap<>();
        for (JsonNode action : actions) actionsByDevice.put(action.path("deviceId").asText(), action);
        Set<String> nextSelected = new HashSet<>();
        int operate = 0, excluded = 0, satisfied = 0, standby = 0, untouched = 0;
        for (JsonNode raw : inventory) {
            ObjectNode row = (ObjectNode) raw;
            JsonNode action = actionsByDevice.get(row.path("deviceId").asText());
            if (action == null) {
                if ("excluded".equals(row.path("disposition").asText())) excluded++;
                else untouched++;
                continue;
            }
            ObjectNode command = (ObjectNode) action.path("command");
            boolean isSatisfied = commandSatisfied(command, mapper.convertValue(row.get("state"), Object.class));
            boolean isSelected = selectedIds.contains(action.path("id").asText()) && !isSatisfied;
            row.set("command", command.deepCopy());
            row.put("satisfied", isSatisfied);
            if (isSatisfied) {
                row.put("disposition", "none");
                satisfied++;
            } else if (isSelected) {
                row.put("disposition", "operate");
                nextSelected.add(action.path("id").asText());
                operate++;
            } else {
                row.put("disposition", "standby");
                standby++;
            }
        }
        ArrayNode next = mapper.createArrayNode();
        for (JsonNode action : actions) {
            if (nextSelected.contains(action.path("id").asText())) next.add(action.path("id").asText());
        }
        body.set("selectedActionIds", next);
        ObjectNode counts = mapper.createObjectNode();
        counts.put("total", inventory.size());
        counts.put("operate", operate);
        counts.put("excluded", excluded);
        counts.put("satisfied", satisfied);
        counts.put("standby", standby);
        counts.put("untouched", untouched);
        counts.put("considered", inventory.size() - untouched);
        body.set("counts", counts);
    }

    public ActionDraftFact restore(PrincipalInfo principal, String draftId, long revision) {
        ActionDraftFact draft = pending(principal, draftId, revision);
        ObjectNode body = payload(draft);
        body.set("actions", array(body, "originalActions").deepCopy());
        body.set("selectedActionIds", array(body, "originalSelectedActionIds", array(body, "selectedActionIds")).deepCopy());
        body.set("inventory", array(body, "originalInventory", array(body, "inventory")).deepCopy());
        body.set("counts", objectNode(body.get("originalCounts")));
        if (body.has("originalSummary")) body.put("summary", body.path("originalSummary").asText(""));
        if (body.has("originalQuestion")) {
            if (body.get("originalQuestion").isNull()) body.putNull("question");
            else body.put("question", body.path("originalQuestion").asText(""));
        } else {
            body.putNull("question");
        }
        return replace(draft, body, draft.status());
    }

    /**
     * Expiry is death: revalidation reads fresh states into a NEW draft and consumes
     * the old one. The old draft id can never become executable again.
     */
    public ActionDraftFact revalidate(PrincipalInfo principal, String draftId, long revision) {
        ActionDraftFact draft = find(principal, draftId);
        if (!draft.status().equals(ActionDraftStatusEnum.EXPIRED.name())
                && !(draft.status().equals(ActionDraftStatusEnum.PENDING_CONFIRMATION.name()) && expired(draft))) {
            throw new ActionDraftExpiredException();
        }
        if (draft.revision() != revision) throw new ActionDraftRevisionConflictException();
        ObjectNode body = payload(draft);
        if (!body.path("revalidatedInto").asText("").isBlank()) {
            throw new ActionDraftExpiredException();
        }
        String conversationId = body.path("conversationId").asText("");
        IotSourceEnum source = parseSource(body.path("source").asText(null));
        for (ActionDraftFact existing : store.findByConversation(conversationId)) {
            if (existing.draftId().equals(draft.draftId())) continue;
            if (existing.status().equals(ActionDraftStatusEnum.EXECUTING.name())
                    || existing.status().equals(ActionDraftStatusEnum.PENDING_CONFIRMATION.name()) && !expired(existing)) {
                throw new ActionDraftBusyException("请先处理当前待确认草案");
            }
        }
        AuthorizedDeviceScope scope = loadScope(principal, source, false);
        String focusRegionId = body.path("focusRegionId").asText("");
        if (source == IotSourceEnum.OFFICE && !focusRegionId.isBlank()) {
            scope = focusScope(principal, source, scope, focusRegionId);
        }
        ArrayNode previousActions = array(body, "actions");
        Map<String, Object> previousSelected = new LinkedHashMap<>();
        actionIds(array(body, "selectedActionIds")).forEach(id -> previousSelected.put(id, Boolean.TRUE));
        List<String> deviceIds = new ArrayList<>();
        for (JsonNode action : previousActions) deviceIds.add(action.path("deviceId").asText());
        for (JsonNode row : array(body, "inventory")) {
            String id = row.path("deviceId").asText("");
            if (!id.isBlank() && !deviceIds.contains(id)) deviceIds.add(id);
        }
        DeviceStateReadResult fresh = deviceIds.isEmpty()
                ? new DeviceStateReadResult(Map.of(), clock.instant().toString())
                : readStates(principal, source, deviceIds);
        Map<String, Object> states = fresh.states();
        ArrayNode actions = mapper.createArrayNode();
        ArrayNode selected = mapper.createArrayNode();
        Map<String, JsonNode> previousByDevice = new LinkedHashMap<>();
        for (JsonNode action : previousActions) previousByDevice.put(action.path("deviceId").asText(), action);
        for (JsonNode action : previousActions) {
            String deviceId = action.path("deviceId").asText();
            AuthorizedDevice device = scope.device(deviceId).orElseThrow(ActionDraftScopeChangedException::new);
            if (!device.kind().equals(action.path("kind").asText())) throw new ActionDraftScopeChangedException();
            JsonNode command = action.path("command");
            validateCommand(device.kind(), command);
            Object state = states.get(deviceId);
            if (usableForPreparation(fresh, deviceId) && commandSatisfied(command, state)) continue;
            ObjectNode next = action(device, command, state, false);
            actions.add(next);
            if (previousSelected.containsKey(action.path("id").asText())) selected.add(next.path("id").asText());
        }
        Set<String> selectedIds = new HashSet<>();
        for (JsonNode selectedId : selected) selectedIds.add(selectedId.asText());
        ArrayNode inventory = mapper.createArrayNode();
        int operate = selected.size(), excluded = 0, satisfied = 0, standby = 0, untouched = 0;
        Set<String> actionDevices = new HashSet<>();
        actions.forEach(action -> actionDevices.add(action.path("deviceId").asText()));
        for (JsonNode previousRow : array(body, "inventory")) {
            String deviceId = previousRow.path("deviceId").asText();
            AuthorizedDevice device = scope.device(deviceId).orElse(null);
            if (device == null) continue;
            Object state = states.get(deviceId);
            JsonNode previous = previousByDevice.get(deviceId);
            if (previous != null && !actionDevices.contains(deviceId)) {
                inventory.add(inventoryRow(device, state, previous.path("command"), "none", true));
                satisfied++;
            } else if (previous != null) {
                JsonNode nextAction = null;
                for (JsonNode candidate : actions) if (candidate.path("deviceId").asText().equals(deviceId)) nextAction = candidate;
                boolean isSelected = nextAction != null && selectedIds.contains(nextAction.path("id").asText());
                inventory.add(inventoryRow(device, state, previous.path("command"),
                        isSelected ? "operate" : "standby", false));
                if (!isSelected) standby++;
            } else if ("excluded".equals(previousRow.path("disposition").asText())) {
                inventory.add(inventoryRow(device, state, null, "excluded", false));
                excluded++;
            } else {
                inventory.add(inventoryRow(device, state, null, "none", false));
                untouched++;
            }
        }
        ObjectNode counts = mapper.createObjectNode();
        counts.put("total", inventory.size());
        counts.put("operate", operate);
        counts.put("excluded", excluded);
        counts.put("satisfied", satisfied);
        counts.put("standby", standby);
        counts.put("untouched", untouched);
        counts.put("considered", inventory.size() - untouched);
        String summary = actions.isEmpty() ? "设备已达到目标，无需操作"
                : "重新核验完成 · " + actions.size() + " 台设备待处理";
        String newId = UUID.randomUUID().toString();
        ObjectNode replacement = body.deepCopy();
        replacement.put("source", source.wireValue());
        replacement.put("tenantCode", principal.tenantCode());
        replacement.put("ownerKey", ownerKey(principal));
        replacement.put("principalId", principal.principalId());
        replacement.put("identityRealm", principal.identityRealm());
        replacement.put("conversationId", conversationId);
        replacement.put("turnId", body.path("turnId").asText(""));
        replacement.set("actions", actions);
        replacement.set("originalActions", actions.deepCopy());
        replacement.set("selectedActionIds", selected);
        replacement.set("originalSelectedActionIds", selected.deepCopy());
        replacement.set("inventory", inventory);
        replacement.set("originalInventory", inventory.deepCopy());
        replacement.set("counts", counts);
        replacement.set("originalCounts", counts.deepCopy());
        replacement.put("summary", summary);
        replacement.put("originalSummary", summary);
        replacement.putNull("question");
        replacement.put("status", actions.isEmpty()
                ? ActionDraftStatusEnum.NO_CHANGE.name() : ActionDraftStatusEnum.PENDING_CONFIRMATION.name());
        replacement.put("expiresAt", clock.instant().plus(draftTtl).toString());
        stateQuality(replacement, fresh);
        replacement.put("revalidatedFrom", draft.draftId());
        replacement.put("dispatchStarted", false);
        replacement.remove("result");
        String key = draft.idempotencyKey() + ":revalidate:" + draft.revision();
        ObjectNode consumed = payload(draft);
        consumed.put("status", ActionDraftStatusEnum.EXPIRED.name());
        consumed.put("revalidatedInto", newId);
        replace(draft, consumed, ActionDraftStatusEnum.EXPIRED.name());
        ActionDraftFact created = store.create(new ActionDraftFact(newId, 1,
                replacement.path("status").asText(), json(replacement), key));
        emit(created, "action.proposed");
        audit(created, "ACTION_DRAFT_REVALIDATED");
        return created;
    }

    /** Confirm is CAS-gated before the fresh scope call, so concurrent confirms cannot dispatch twice. */
    public ActionDraftFact confirm(PrincipalInfo principal, String draftId, Long expectedRevision) {
        return confirmFrom(principal, find(principal, draftId), expectedRevision);
    }

    public ActionDraftFact confirm(PrincipalInfo principal, String draftId, String source, Long expectedRevision) {
        return confirmFrom(principal, find(principal, draftId, parseSource(source)), expectedRevision);
    }

    private ActionDraftFact confirmFrom(PrincipalInfo principal, ActionDraftFact initial, Long expectedRevision) {
        if (terminal(initial)) return initial;
        if (!initial.status().equals(ActionDraftStatusEnum.PENDING_CONFIRMATION.name())) {
            throw new ActionDraftBusyException("Action draft is already executing");
        }
        if (expectedRevision != null && initial.revision() != expectedRevision) throw new ActionDraftRevisionConflictException();
        if (expired(initial)) {
            expire(initial);
            throw new ActionDraftExpiredException();
        }
        ObjectNode body = payload(initial);
        ActionDraftFact executing = cas(initial, body, ActionDraftStatusEnum.EXECUTING.name());
        IotSourceEnum source = parseSource(body.path("source").asText(null));
        AuthorizedDeviceScope scope;
        try {
            scope = verifyScope(principal, body);
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
            body.put("confirmedAt", clock.instant().toString());
            if (history != null && source == IotSourceEnum.OFFICE) {
                try {
                    String name = iot.readCreatorDisplayName(new RequestIdentity(principal.authorization(),
                            principal.tenantCode(), source, ownerKey(principal))).block(scopeTimeout);
                    body.put("creatorName", name == null || name.isBlank() ? "成员" : name.trim());
                } catch (RuntimeException unavailable) {
                    body.put("creatorName", "成员");
                }
            }
            RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), source, ownerKey(principal));
            DeviceStateReadResult fresh = readFreshStates(identity, selectedActions);
            ArrayNode toDispatch = mapper.createArrayNode();
            ArrayNode satisfiedReceipts = mapper.createArrayNode();
            ArrayNode satisfiedReadbacks = mapper.createArrayNode();
            for (JsonNode action : selectedActions) {
                Object state = fresh.states().get(action.path("deviceId").asText(""));
                if (!ActionResultProjector.noPhysicalConfirmation(action.path("kind").asText(""))
                        && commandSatisfied(action.path("command"), state)) {
                    ObjectNode receipt = mapper.createObjectNode();
                    receipt.put("actionId", action.path("id").asText());
                    receipt.put("outcome", ActionReceiptOutcomeEnum.ALREADY_SATISFIED.name());
                    receipt.put("reason", "already_satisfied");
                    satisfiedReceipts.add(receipt);
                    ObjectNode readback = mapper.createObjectNode();
                    readback.put("actionId", action.path("id").asText());
                    readback.put("outcome", "satisfied");
                    readback.set("observedState", mapper.valueToTree(state));
                    readback.put("observedAt", fresh.observedAtByDevice()
                            .getOrDefault(action.path("deviceId").asText(""), fresh.observedAt()));
                    satisfiedReadbacks.add(readback);
                } else {
                    toDispatch.add(action);
                }
            }
            ActionDispatchResult dispatched = toDispatch.isEmpty()
                    ? new ActionDispatchResult(ActionDraftStatusEnum.NO_CHANGE, resultJson("fixture", "ALREADY_SATISFIED",
                    mapper.createArrayNode(), mapper.createArrayNode()), false, ActionReceiptOutcomeEnum.ALREADY_SATISFIED)
                    : gateway.dispatch(identity, scope, json(toDispatch), fresh.states());
            ActionDispatchResult result = mergeDispatch(dispatched, satisfiedReceipts, satisfiedReadbacks, !toDispatch.isEmpty());
            body.put("status", result.status().name());
            ObjectNode resultBody = object(result.resultJson());
            // New receipts persist the same normalized projection every read path serves,
            // so a complete status/message is durable instead of being regenerated later.
            resultBody = results.normalized(resultBody, executing.draftId(), result.status().name(),
                    results.kindsByAction(body), results.devicesByAction(body));
            body.set("result", resultBody);
            body.put("dispatchStarted", result.dispatchStarted());
            body.put("endedAt", clock.instant().toString());
            ActionDraftFact done = cas(executing, body, result.status().name());
            persistExecutionFacts(done, resultBody);
            recordHistory(done, scope);
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
            return interrupted(executing, body, error, scope);
        } catch (ActionDraftUnavailableException error) {
            rollback(executing);
            throw error;
        } catch (RuntimeException error) {
            return interrupted(executing, body, error, scope);
        }
    }

    public ActionDraftFact cancel(PrincipalInfo principal, String draftId) {
        ActionDraftFact draft = find(principal, draftId);
        if (terminal(draft)) return draft;
        if (!draft.status().equals(ActionDraftStatusEnum.PENDING_CONFIRMATION.name())) {
            throw new ActionDraftBusyException("Action draft is already executing");
        }
        ObjectNode body = payload(draft);
        body.put("status", ActionDraftStatusEnum.CANCELLED.name());
        ActionDraftFact cancelled = cas(draft, body, ActionDraftStatusEnum.CANCELLED.name());
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
        view.put("summary", body.path("summary").asText(""));
        if (body.hasNonNull("question")) view.put("question", body.path("question").asText());
        if (body.has("inventory")) view.put("inventory", mapper.convertValue(body.get("inventory"), Object.class));
        view.put("source", body.path("source").asText(null));
        view.put("conversationId", body.path("conversationId").asText(null));
        view.put("turnId", body.path("turnId").asText(null));
        view.put("focusRegionId", body.path("focusRegionId").asText(null));
        view.put("summary", body.path("summary").asText(""));
        view.put("question", body.has("question") && !body.get("question").isNull()
                ? body.path("question").asText(null) : null);
        view.put("actions", mapper.convertValue(array(body, "actions"), Object.class));
        view.put("selectedActionIds", mapper.convertValue(array(body, "selectedActionIds"), Object.class));
        view.put("inventory", mapper.convertValue(array(body, "inventory"), Object.class));
        view.put("counts", mapper.convertValue(objectNode(body.get("counts")), Object.class));
        view.put("observedAt", body.path("observedAt").asText(null));
        for (String field : List.of("stateCache", "stateStatus", "missingStateDeviceIds", "stateObservedAtByDevice")) {
            if (body.has(field)) view.put(field, mapper.convertValue(body.get(field), Object.class));
        }
        if (!body.path("revalidatedInto").asText("").isBlank()) {
            view.put("revalidatedInto", body.path("revalidatedInto").asText());
        }
        // One truthful projection serves draft detail, conversation restore and the
        // confirm answer; a draft that never executed reports no execution result.
        ObjectNode projected = results.project(draft, body);
        view.put("result", projected == null ? null : mapper.convertValue(projected, Object.class));
        return view;
    }

    /**
     * Flat execution result for the confirm answer. A draft without an executed
     * result yields its truthful draft status, never a fabricated execution.
     */
    public Map<String, Object> executionResult(ActionDraftFact draft) {
        ObjectNode projected = results.project(draft, payload(draft));
        if (projected == null) return results.statusBody(draft);
        return mapper.convertValue(projected, RESULT_TYPE);
    }

    /** Ordinary reads expose held device facts, not evidence from a prior dispatch. */
    public Map<String, Object> deviceStates(PrincipalInfo principal, String draftId) {
        ActionDraftFact draft = find(principal, draftId);
        IotSourceEnum source = parseSource(payload(draft).path("source").asText(null));
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), source, principal.ownerKey());
        List<String> ids = actionDevices(draft).values().stream().distinct().sorted().toList();
        DeviceStateReadResult result = iot.readDeviceStates(identity, ids).block(ORDINARY_STATE_TIMEOUT);
        if (result == null) throw new UpstreamUnavailableException("设备状态");
        Map<String, Object> body = mapper.convertValue(result, RESULT_TYPE);
        body.put("source", "local");
        return body;
    }

    private List<Map<String, Object>> durableReadbacks(ActionDraftFact draft) {
        List<Map<String, Object>> facts = new ArrayList<>();
        if (readbackStore == null) return facts;
        for (ActionReadbackFact fact : readbackStore.findByDraft(draft.draftId())) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("actionId", fact.actionId());
            state.put("outcome", fact.outcome());
            state.put("observedAt", fact.observedAt());
            state.put("observedState", parseObject(fact.observedStateJson()));
            facts.add(state);
        }
        return facts;
    }

    public Map<String, Object> deviceStates(PrincipalInfo principal, String draftId, String source) {
        find(principal, draftId, parseSource(source));
        return deviceStates(principal, draftId);
    }

    private Map<String, String> actionDevices(ActionDraftFact draft) {
        return results.devicesByAction(payload(draft));
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
                "readbacks", durableReadbacks(draft));
    }

    public Map<String, Object> summary(PrincipalInfo principal, String draftId, String source) {
        current(principal, draftId, source);
        return summary(principal, draftId);
    }

    private ActionDraftFact find(PrincipalInfo principal, String draftId) {
        return find(principal, draftId, null);
    }

    private ActionDraftFact find(PrincipalInfo principal, String draftId, IotSourceEnum source) {
        ActionDraftFact draft = store.find(draftId).orElseThrow(() -> new BusinessException("ACTION_DRAFT_NOT_FOUND", "草案不存在", 404));
        if (!principal.tenantCode().equals(tenantOf(draft))
                || !ownerKey(principal).equals(payload(draft).path("ownerKey").asText(""))
                || source != null && !source.wireValue().equals(payload(draft).path("source").asText(""))) {
            throw new BusinessException("ACTION_DRAFT_NOT_FOUND", "草案不存在", 404);
        }
        return draft;
    }

    private ActionDraftFact pending(PrincipalInfo principal, String id, long revision) {
        ActionDraftFact draft = find(principal, id);
        if (!draft.status().equals(ActionDraftStatusEnum.PENDING_CONFIRMATION.name())) {
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
        body.put("status", ActionDraftStatusEnum.PENDING_CONFIRMATION.name());
        try {
            store.update(executing.draftId(), executing.revision(),
                    new ActionDraftFact(executing.draftId(), executing.revision() + 1, ActionDraftStatusEnum.PENDING_CONFIRMATION.name(), json(body), executing.idempotencyKey()));
        } catch (ActionDraftUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException conflict) {
            throw new ActionDraftBusyException("草案状态无法恢复");
        }
    }

    private ActionDraftFact expire(ActionDraftFact draft) {
        ObjectNode body = payload(draft);
        body.put("status", ActionDraftStatusEnum.EXPIRED.name());
        try {
            return store.update(draft.draftId(), draft.revision(),
                    new ActionDraftFact(draft.draftId(), draft.revision() + 1, ActionDraftStatusEnum.EXPIRED.name(), json(body), draft.idempotencyKey()));
        } catch (ActionDraftUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException conflict) {
            ActionDraftFact current = store.find(draft.draftId()).orElseThrow(() ->
                    new ActionDraftUnavailableException("草案过期状态无法读取", conflict));
            if (current.revision() <= draft.revision()) throw conflict;
            return current;
        }
    }

    private ActionDraftFact interrupted(ActionDraftFact executing, ObjectNode body, RuntimeException error, AuthorizedDeviceScope scope) {
        body.put("status", ActionDraftStatusEnum.INTERRUPTED.name());
        body.put("dispatchStarted", true);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", ActionDraftStatusEnum.INTERRUPTED.name());
        result.put("outcome", ActionReceiptOutcomeEnum.UNKNOWN.name());
        // The long reason is evidence; the projected message is canonical.
        result.put("error", "执行中断，已下发指令结果未知，请先查询设备状态"
                + (error.getMessage() == null || error.getMessage().isBlank() ? "" : ": " + error.getMessage()));
        ArrayNode receipts = mapper.createArrayNode();
        Set<String> selectedIds = actionIds(array(body, "selectedActionIds"));
        for (JsonNode action : array(body, "actions")) {
            if (!selectedIds.contains(action.path("id").asText())) continue;
            ObjectNode receipt = mapper.createObjectNode();
            receipt.put("actionId", action.path("id").asText());
            receipt.put("outcome", ActionReceiptOutcomeEnum.UNKNOWN.name());
            receipts.add(receipt);
        }
        result.set("receipts", receipts);
        result = results.normalized(result, executing.draftId(), ActionDraftStatusEnum.INTERRUPTED.name(),
                results.kindsByAction(body), results.devicesByAction(body));
        body.set("result", result);
        body.put("endedAt", clock.instant().toString());
        ActionDraftFact done = cas(executing, body, ActionDraftStatusEnum.INTERRUPTED.name());
        persistExecutionFacts(done, result);
        recordHistory(done, scope);
        audit(done, "ACTION_DRAFT_INTERRUPTED");
        return done;
    }

    private DeviceStateReadResult readFreshStates(RequestIdentity identity, ArrayNode actions) {
        // Access control is open-once with no comparable pre-state: no state polling
        // happens just to open a door. Fresh authorization still gates the dispatch.
        List<String> ids = actions.valueStream()
                .filter(action -> !ActionResultProjector.noPhysicalConfirmation(action.path("kind").asText("")))
                .map(action -> action.path("deviceId").asText(""))
                .filter(id -> !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return new DeviceStateReadResult(Map.of(), clock.instant().toString());
        try {
            DeviceStateReadResult states = iot.readLiveDeviceStates(identity, ids).block(scopeTimeout);
            if (states == null) throw new UpstreamUnavailableException("设备状态");
            return states;
        } catch (GatewayAuthorizationException error) {
            throw error;
        } catch (ActionDraftUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ActionDraftUnavailableException("确认前设备状态暂不可用", error);
        }
    }

    private boolean commandSatisfied(JsonNode command, Object state) {
        if (state == null || command == null || !command.isObject() || command.isEmpty()) return false;
        if (command.has("controlType")) return false;
        Iterator<String> fields = command.fieldNames();
        if (state instanceof Boolean lit) {
            if (!command.has("closeOrOpen") || command.size() > 1) return false;
            return lit == (command.path("closeOrOpen").asInt() == 1);
        }
        JsonNode observed = mapper.valueToTree(state);
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode expected = command.get(field);
            JsonNode actual = observed.get(field);
            if ("closeOrOpen".equals(field) && actual == null && observed.has("power")) {
                actual = mapper.getNodeFactory().numberNode("on".equals(observed.path("power").asText()) ? 1 : 0);
            }
            if (actual == null || actual.isNull()) return false;
            if (expected.isNumber() && actual.isNumber()) {
                if (Double.compare(expected.asDouble(), actual.asDouble()) != 0) return false;
            } else if (!expected.asText().equals(actual.asText())) {
                return false;
            }
        }
        return true;
    }

    private ActionDispatchResult mergeDispatch(ActionDispatchResult dispatched, ArrayNode satisfiedReceipts,
                                               ArrayNode satisfiedReadbacks, boolean dispatchedAny) {
        ObjectNode result = object(dispatched.resultJson());
        ArrayNode receipts = mapper.createArrayNode();
        satisfiedReceipts.forEach(receipts::add);
        JsonNode gatewayReceipts = result.get("receipts");
        if (gatewayReceipts != null && gatewayReceipts.isArray()) gatewayReceipts.forEach(receipts::add);
        ArrayNode readbacks = mapper.createArrayNode();
        satisfiedReadbacks.forEach(readbacks::add);
        JsonNode gatewayReadbacks = result.get("readbacks");
        if (gatewayReadbacks != null && gatewayReadbacks.isArray()) gatewayReadbacks.forEach(readbacks::add);
        result.set("receipts", receipts);
        result.set("readbacks", readbacks);
        if (!result.has("observedStates")) {
            ObjectNode observedStates = mapper.createObjectNode();
            for (JsonNode readback : readbacks) {
                if (readback.has("observedState")) observedStates.set(readback.path("actionId").asText(), readback.get("observedState"));
            }
            result.set("observedStates", observedStates);
        }
        ActionDraftStatusEnum status = dispatched.status();
        if (!dispatchedAny) status = ActionDraftStatusEnum.NO_CHANGE;
        else if (!satisfiedReceipts.isEmpty() && dispatched.status() == ActionDraftStatusEnum.REJECTED) {
            status = ActionDraftStatusEnum.PARTIALLY_ACCEPTED;
        }
        ActionReceiptOutcomeEnum outcome = dispatchedAny ? dispatched.receiptOutcome() : ActionReceiptOutcomeEnum.ALREADY_SATISFIED;
        return new ActionDispatchResult(status, result.toString(), dispatched.dispatchStarted(), outcome);
    }

    private String resultJson(String gateway, String outcome, ArrayNode receipts, ArrayNode readbacks) {
        ObjectNode result = mapper.createObjectNode();
        result.put("gateway", gateway);
        result.put("outcome", outcome);
        result.set("receipts", receipts);
        result.set("readbacks", readbacks);
        return result.toString();
    }

    private void recordHistory(ActionDraftFact completed, AuthorizedDeviceScope scope) {
        if (history == null || !"office".equals(payload(completed).path("source").asText())) return;
        try {
            history.record(completed, scope);
        } catch (RuntimeException failure) {
            // Runtime records this failure without turning an already-dispatched action back into a retryable draft.
            LOG.error("Immediate task history unavailable for draft {}", completed.draftId(), failure);
            audit(completed, "IMMEDIATE_TASK_RECORD_FAILED");
        }
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
                            ActionReceiptOutcomeEnum.valueOf(outcome.toUpperCase(java.util.Locale.ROOT)), json(receipt)));
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
        IotSourceEnum source = parseSource(body.path("source").asText(null));
        AuthorizedDeviceScope scope = loadScope(principal, source, true);
        String focusRegionId = body.path("focusRegionId").asText("");
        if (source == IotSourceEnum.OFFICE && !focusRegionId.isBlank()) {
            scope = scope.retain(device -> device.officeIds().contains(focusRegionId));
        }
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

    private AuthorizedDeviceScope loadScope(PrincipalInfo principal, IotSourceEnum source, boolean live) {
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), source, ownerKey(principal));
        try {
            AuthorizedDeviceScope scope = (live ? iot.revalidateAuthorizedDeviceScope(identity)
                    : iot.loadAuthorizedDeviceScope(identity)).block(scopeTimeout);
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

    /**
     * 校验并把指令规范成执行值；整数性、无损 int 与范围规则统一在
     * {@link DeviceCommands}，这里只保留草案对外错误码（UNSUPPORTED_ACTION_DRAFT/422）。
     */
    private ObjectNode validateCommand(String kind, JsonNode command) {
        try {
            return DeviceCommands.canonical(kind, command);
        } catch (IllegalArgumentException invalid) {
            throw switch (kind) {
                case "lighting" -> new BusinessException("UNSUPPORTED_ACTION_DRAFT", "照明指令参数无效", 422);
                case "access" -> new BusinessException("UNSUPPORTED_ACTION_DRAFT", "门禁指令参数无效", 422);
                case "ac" -> new BusinessException("UNSUPPORTED_ACTION_DRAFT", "空调指令参数无效", 422);
                default -> new BusinessException("UNSUPPORTED_ACTION_DRAFT", "设备类型不可控", 422);
            };
        }
    }

    private record Page(ArrayNode actions, ArrayNode selected, ArrayNode inventory, ObjectNode counts,
                        String summary, String question, DeviceStateReadResult observations) { }

    private record CandidateView(String candidateSetId, String kind, List<AuthorizedDevice> members) { }

    private record ExplicitAction(AuthorizedDevice device, ObjectNode command, Boolean selected) { }

    /**
     * Expands the server-owned candidate sets, subtracts explicit exclusions, reads
     * real states once, and classifies every considered device as operate, excluded
     * or already-satisfied. The model's list of ids is never treated as the final set.
     */
    private Page materialize(ObjectNode payload, AuthorizedDeviceScope scope,
                             AuthorizedDeviceScope catalogScope, PrincipalInfo principal,
                             String conversationId, IotSourceEnum source) {
        Map<String, String> handleIndex = new LinkedHashMap<>();
        List<CandidateView> sets = payload.path("lighting").isObject() && payload.path("candidateSetIds").isEmpty()
                ? new ArrayList<>() : new ArrayList<>(resolveCandidateSets(payload, "candidateSetIds",
                        scope, catalogScope, principal, conversationId, source, handleIndex));
        Map<String, String> exclusionRefs = resolveExcludedReferences(payload, scope, catalogScope,
                principal, conversationId, source);
        if (payload.path("lighting").isObject()) {
            expandLighting(payload, sets, scope, catalogScope, principal, conversationId, source,
                    handleIndex, exclusionRefs);
        }
        if (sets.isEmpty()) {
            throw new BusinessException("CANDIDATE_SET_REQUIRED", "操作草案必须引用已核验的候选集", 422);
        }
        Map<String, AuthorizedDevice> universe = new LinkedHashMap<>();
        for (CandidateView set : sets) {
            for (AuthorizedDevice member : set.members()) universe.putIfAbsent(member.id(), member);
        }
        JsonNode rawActions = payload.get("actions");
        if (rawActions == null || !rawActions.isArray() || rawActions.isEmpty()) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "actions is required");
        }
        if (universe.isEmpty()) throw new BusinessException("INVALID_ACTION_DRAFT", "候选范围没有设备");
        List<ExplicitAction> explicit = explicitActions(rawActions, scope, universe, handleIndex);
        Set<String> excluded = excludedIds(payload, universe, exclusionRefs);
        if (payload.has("excludeCandidateSetIds")) {
            for (CandidateView exclusion : resolveCandidateSets(payload, "excludeCandidateSetIds", scope,
                    catalogScope, principal, conversationId, source, handleIndex)) {
                if (exclusion.members().isEmpty()) {
                    throw new BusinessException("INVALID_ACTION_DRAFT", "排除子集为空，尚未查明真实排除范围");
                }
                for (AuthorizedDevice member : exclusion.members()) {
                    if (!universe.containsKey(member.id())) {
                        throw new BusinessException("INVALID_ACTION_DRAFT", "排除项必须完全属于本次包含范围");
                    }
                    excluded.add(member.id());
                }
            }
        }
        Map<String, JsonNode> explicitCommands = new LinkedHashMap<>();
        Map<String, Boolean> explicitSelection = new LinkedHashMap<>();
        Map<String, List<JsonNode>> commandsByKind = new LinkedHashMap<>();
        for (ExplicitAction action : explicit) {
            if (excluded.contains(action.device().id())) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "排除设备与拟操作设备重叠，草案无效");
            }
            explicitCommands.put(action.device().id(), action.command());
            if (action.selected() != null) explicitSelection.put(action.device().id(), action.selected());
            commandsByKind.computeIfAbsent(action.device().kind(), key -> new ArrayList<>()).add(action.command());
        }
        Map<String, JsonNode> defaultCommands = new LinkedHashMap<>();
        for (Map.Entry<String, List<JsonNode>> entry : commandsByKind.entrySet()) {
            if (!payload.path("expandCandidates").asBoolean(false)) continue;
            JsonNode first = entry.getValue().get(0);
            if (entry.getValue().stream().allMatch(first::equals)) defaultCommands.put(entry.getKey(), first);
            else if (universe.values().stream().anyMatch(device -> device.kind().equals(entry.getKey())
                    && !explicitCommands.containsKey(device.id()) && !excluded.contains(device.id()))) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "同一类设备存在多个目标值，请逐台指定或统一目标");
            }
        }
        Set<String> recommended = recommendedIds(payload, universe, handleIndex);
        List<String> ids = List.copyOf(universe.keySet());
        DeviceStateReadResult read = readStates(principal, source, ids);
        Map<String, Object> states = read.states();
        ArrayNode actions = mapper.createArrayNode();
        ArrayNode selected = mapper.createArrayNode();
        ArrayNode inventory = mapper.createArrayNode();
        int operate = 0, excludedCount = 0, satisfiedCount = 0, standby = 0, untouched = 0;
        for (AuthorizedDevice device : universe.values()) {
            Object state = states.get(device.id());
            if (excluded.contains(device.id())) {
                inventory.add(inventoryRow(device, state, null, "excluded", false));
                excludedCount++;
                continue;
            }
            JsonNode command = explicitCommands.get(device.id());
            if (command == null) command = defaultCommands.get(device.kind());
            if (command == null) {
                inventory.add(inventoryRow(device, state, null, "none", false));
                untouched++;
                continue;
            }
            if (device.observationOnly()) throw new ActionDraftScopeChangedException();
            validateCommand(device.kind(), command);
            boolean satisfied = usableForPreparation(read, device.id()) && commandSatisfied(command, state);
            ObjectNode action = action(device, command, state, satisfied);
            actions.add(action);
            boolean isSelected = !satisfied;
            if (Boolean.FALSE.equals(explicitSelection.get(device.id()))) isSelected = false;
            if (recommended.contains(device.id())) isSelected = !satisfied;
            if (isSelected) {
                selected.add(action.path("id").asText());
                operate++;
                inventory.add(inventoryRow(device, state, command, "operate", satisfied));
            } else if (satisfied) {
                satisfiedCount++;
                inventory.add(inventoryRow(device, state, command, "none", true));
            } else {
                standby++;
                inventory.add(inventoryRow(device, state, command, "standby", false));
            }
        }
        if (actions.isEmpty()) throw new BusinessException("INVALID_ACTION_DRAFT", "操作范围为空，未生成本次目标");
        ObjectNode counts = mapper.createObjectNode();
        counts.put("total", universe.size());
        counts.put("operate", operate);
        counts.put("excluded", excludedCount);
        counts.put("satisfied", satisfiedCount);
        counts.put("standby", standby);
        counts.put("untouched", untouched);
        counts.put("considered", universe.size() - untouched);
        if (operate + excludedCount + satisfiedCount + standby + untouched != universe.size()) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "设备台数不一致");
        }
        String summary = payload.path("summary").isTextual() ? payload.path("summary").asText().trim() : "";
        if (summary.isBlank()) summary = "设备操作草案 · " + actions.size() + " 台设备";
        String question = payload.path("question").isTextual() ? payload.path("question").asText().trim() : null;
        if (question != null && question.isBlank()) question = null;
        return new Page(actions, selected, inventory, counts, summary, question, read);
    }

    private void stateQuality(ObjectNode payload, DeviceStateReadResult observations) {
        payload.put("observedAt", observations.observedAt());
        payload.set("stateCache", mapper.valueToTree(observations.cache()));
        payload.put("stateStatus", observations.status().name().toLowerCase(java.util.Locale.ROOT));
        payload.set("missingStateDeviceIds", mapper.valueToTree(observations.missingDeviceIds()));
        payload.set("stateObservedAtByDevice", mapper.valueToTree(observations.observedAtByDevice()));
    }

    private boolean usableForPreparation(DeviceStateReadResult observations, String deviceId) {
        if ("stale".equals(observations.cache().get("status"))) return false;
        String observedAt = observations.observedAtByDevice().getOrDefault(deviceId, observations.observedAt());
        if (observedAt == null) return false;
        try {
            Instant sample = Instant.parse(observedAt);
            Instant now = clock.instant();
            return !sample.isAfter(now) && !sample.isBefore(now.minusSeconds(60));
        } catch (java.time.format.DateTimeParseException unknownTime) {
            return false;
        }
    }

    /** Runtime resolveExcludedDevice: verify the source set without expanding the operation universe. */
    private Map<String, String> resolveExcludedReferences(ObjectNode payload, AuthorizedDeviceScope scope,
            AuthorizedDeviceScope catalogScope, PrincipalInfo principal, String conversationId, IotSourceEnum source) {
        Set<String> sources = new LinkedHashSet<>();
        collectExclusionSources(payload.path("excludedDeviceIds"), sources);
        collectExclusionSources(payload.path("lighting").path("excludedDeviceIds"), sources);
        if (sources.isEmpty()) return Map.of();
        ObjectNode request = mapper.createObjectNode();
        ArrayNode refs = request.putArray("excludeCandidateSetIds");
        for (String handle : sources) refs.add(handle);
        Map<String, String> resolved = new LinkedHashMap<>();
        // Exclusions require exact sets even when inclusion permits browse.
        // Keep this index separate: exclusion sources must not authorize action references.
        resolveCandidateSets(request, "excludeCandidateSetIds", scope, catalogScope,
                principal, conversationId, source, resolved);
        return resolved;
    }

    private static void collectExclusionSources(JsonNode refs, Set<String> sources) {
        if (!refs.isArray()) return;
        for (JsonNode ref : refs) {
            if (!ref.isTextual()) continue;
            java.util.regex.Matcher matcher = DEVICE_HANDLE.matcher(ref.asText());
            if (matcher.matches()) sources.add("s" + matcher.group(1));
        }
    }

    /** Original expandLightingScope: fixed union minus exact subsets, without a model-sized action limit. */
    private void expandLighting(ObjectNode payload, List<CandidateView> sets, AuthorizedDeviceScope scope,
            AuthorizedDeviceScope catalogScope, PrincipalInfo principal, String conversationId, IotSourceEnum source,
            Map<String, String> handleIndex, Map<String, String> exclusionRefs) {
        ObjectNode lighting = (ObjectNode) payload.path("lighting").deepCopy();
        boolean single = lighting.path("candidateSetId").isTextual() && !lighting.path("candidateSetId").asText().isBlank();
        boolean multiple = lighting.path("candidateSetIds").isArray() && !lighting.path("candidateSetIds").isEmpty();
        if (single == multiple) throw new BusinessException("INVALID_ACTION_DRAFT", "包含范围需提供单集合或多个集合，二选一");
        if (single) lighting.putArray("candidateSetIds").add(lighting.path("candidateSetId").asText());
        List<CandidateView> included = resolveCandidateSets(lighting, "candidateSetIds", scope, catalogScope,
                principal, conversationId, source, handleIndex);
        Map<String, AuthorizedDevice> members = new LinkedHashMap<>();
        for (CandidateView set : included) {
            if (!"lighting".equals(set.kind()) || set.members().isEmpty()) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "照明范围尚未完整识别");
            }
            for (AuthorizedDevice member : set.members()) members.putIfAbsent(member.id(), member);
            if (sets.stream().noneMatch(existing -> existing.candidateSetId().equals(set.candidateSetId()))) sets.add(set);
        }
        Set<String> excluded = excludedIds(lighting, members, exclusionRefs);
        for (CandidateView subset : resolveCandidateSets(lighting, "excludeCandidateSetIds", scope, catalogScope,
                principal, conversationId, source, handleIndex)) {
            if (!"lighting".equals(subset.kind()) || subset.members().isEmpty()) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "排除项尚未完整识别");
            }
            for (AuthorizedDevice member : subset.members()) {
                if (!members.containsKey(member.id())) {
                    throw new BusinessException("INVALID_ACTION_DRAFT", "排除项必须完全属于本次包含范围");
                }
                excluded.add(member.id());
            }
        }
        if (excluded.size() == members.size()) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "排除后没有待操作设备，请核对包含范围与排除子集");
        }
        int closeOrOpen;
        try {
            closeOrOpen = DeviceCommands.lightingCloseOrOpen(lighting.get("closeOrOpen"));
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("INVALID_ACTION_DRAFT", "照明操作必须明确开关目标");
        }
        ArrayNode actions = payload.path("actions").isArray()
                ? (ArrayNode) payload.path("actions").deepCopy() : mapper.createArrayNode();
        for (AuthorizedDevice member : members.values()) {
            if (!excluded.contains(member.id())) actions.addObject().put("deviceId", member.id())
                    .put("kind", "lighting").put("closeOrOpen", closeOrOpen).put("selected", true);
        }

        Set<String> allExcluded = new LinkedHashSet<>(excluded);
        if (payload.path("excludedDeviceIds").isArray()) {
            // Union by device identity, not by alias spelling shared with lighting exclusions.
            for (JsonNode id : payload.path("excludedDeviceIds")) {
                allExcluded.add(exclusionRefs.getOrDefault(id.asText(), id.asText()));
            }
        }
        payload.set("excludedDeviceIds", mapper.valueToTree(allExcluded));
        payload.set("actions", actions);
        payload.set("candidateSetIds", mapper.valueToTree(sets.stream().map(CandidateView::candidateSetId).toList()));
        payload.remove("lighting");
    }

    private List<CandidateView> resolveCandidateSets(ObjectNode payload, String field, AuthorizedDeviceScope scope,
                                                     AuthorizedDeviceScope catalogScope,
                                                     PrincipalInfo principal, String conversationId,
                                                     IotSourceEnum source, Map<String, String> handleIndex) {
        JsonNode raw = payload.get(field);
        if (raw == null || raw.isNull()) return List.of();
        if (!raw.isArray() || raw.isEmpty()) throw new BusinessException("INVALID_ACTION_DRAFT", field + " 无效");
        List<CandidateView> sets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode value : raw) {
            if (!value.isTextual() || value.asText().isBlank() || !seen.add(value.asText())) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "candidateSetIds 无效");
            }
            if (candidates == null) {
                throw new BusinessException("CANDIDATE_SET_STORE_UNAVAILABLE", "设备候选集事实存储未配置", 503);
            }
            DeviceCandidateRecord record = candidates.find(value.asText(), conversationId)
                    .orElseThrow(() -> new BusinessException("CANDIDATE_SET_NOT_FOUND", "设备候选集不存在或已过期", 404));
            if (!principal.tenantCode().equals(record.tenantCode())
                    || !ownerKey(principal).equals(record.ownerKey())
                    || source != record.source()
                    || !conversationId.equals(record.conversationId())) {
                throw new BusinessException("CANDIDATE_SET_NOT_FOUND", "设备候选集不存在或已过期", 404);
            }
            boolean exclusion = "excludeCandidateSetIds".equals(field);
            // This field also carries dN_k source sets: exclusions must never accept browse.
            if (!record.permitsScopeExpansion(exclusion)) {
                throw new BusinessException("CANDIDATE_SCOPE_UNRESOLVED",
                        "候选范围或排除子集尚未精确查明，请补查明确设备或澄清范围，不要重复相同地点检索",
                        candidateRecovery(record, "scope_unresolved", "get_device_details"), 422);
            }
            if (record.members().size() != record.totalMatches()) {
                throw new BusinessException("CANDIDATE_SET_INCOMPLETE", "候选范围成员不完整，尚未生成草案",
                        candidateRecovery(record, "members_incomplete", "find_device_candidates"), 422);
            }
            // Presentation may be bounded, but the persisted member list is complete:
            // server-side expansion is exactly what prevents the model from owning the
            // final id list.
            AuthorizedDeviceScope candidateScope = candidateScope(catalogScope, record);
            List<AuthorizedDevice> members = new ArrayList<>();
            for (AuthorizedDevice member : record.members()) {
                AuthorizedDevice current = scope.device(member.id())
                        .filter(device -> device.kind().equals(member.kind()))
                        .filter(device -> candidateScope.device(device.id()).filter(allowed -> allowed.kind().equals(device.kind())).isPresent())
                        .orElseThrow(ActionDraftScopeChangedException::new);
                members.add(current);
            }
            if (record.handle() != null && !record.handle().isBlank()) {
                for (int position = 0; position < record.members().size(); position++) {
                    // Runtime handles.ts resolveDeviceRef: d{N}_{k} positions over the persisted members.
                    handleIndex.putIfAbsent(record.deviceHandle(position), record.members().get(position).id());
                }
            }
            sets.add(new CandidateView(record.candidateSetId(), record.query().kind(), members));
        }
        return sets;
    }

    private static Map<String, Object> candidateRecovery(DeviceCandidateRecord record, String reason, String nextTool) {
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("reason", reason);
        if (record.handle() != null && !record.handle().isBlank()) recovery.put("candidateSet", record.handle());
        recovery.put("resolution", record.resolutionMode());
        recovery.put("nextTool", nextTool);
        recovery.put("retrySameScope", false);
        return recovery;
    }

    private static AuthorizedDeviceScope candidateScope(AuthorizedDeviceScope scope, DeviceCandidateRecord record) {
        String regionId = record.query().regionId();
        if (scope.source() != IotSourceEnum.OFFICE || regionId == null || regionId.isBlank()) return scope;
        return scope.retain(device -> device.officeIds().contains(regionId));
    }

    private List<ExplicitAction> explicitActions(JsonNode raw, AuthorizedDeviceScope scope,
                                                  Map<String, AuthorizedDevice> universe,
                                                  Map<String, String> handleIndex) {
        List<ExplicitAction> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : raw) {
            if (!item.isObject() || !item.path("deviceId").isTextual()) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "action is invalid");
            }
            String deviceId = handleIndex.getOrDefault(item.path("deviceId").asText(), item.path("deviceId").asText());
            AuthorizedDevice device = scope.device(deviceId).orElseThrow(ActionDraftScopeChangedException::new);
            if (!universe.containsKey(device.id())) {
                throw new BusinessException("INVALID_DEVICE_REFERENCE", "拟操作设备不属于引用的候选集", 403);
            }
            String kind = item.path("kind").isTextual() ? item.path("kind").asText() : device.kind();
            if (!device.kind().equals(kind)) throw new ActionDraftScopeChangedException();
            if (device.observationOnly()) throw new ActionDraftScopeChangedException();
            if (!seen.add(device.id())) throw new BusinessException("INVALID_ACTION_DRAFT", "设备目标重复");
            ObjectNode command = validateCommand(kind, normalizeCommand(item, kind));
            Boolean selected = item.path("selected").isBoolean() ? item.path("selected").asBoolean() : null;
            result.add(new ExplicitAction(device, command, selected));
        }
        return result;
    }

    private ObjectNode normalizeCommand(JsonNode item, String kind) {
        JsonNode command = item.path("command");
        ObjectNode result = mapper.createObjectNode();
        switch (kind) {
            case "lighting" -> {
                if (command.isObject() && command.has("closeOrOpen")) {
                    result.set("closeOrOpen", command.get("closeOrOpen"));
                } else if (item.path("closeOrOpen").isIntegralNumber()) {
                    result.set("closeOrOpen", item.get("closeOrOpen"));
                } else if (command.isObject() && command.has("power")) {
                    String power = command.path("power").asText();
                    if ("on".equals(power)) result.put("closeOrOpen", 1);
                    else if ("off".equals(power)) result.put("closeOrOpen", 0);
                } else if (item.path("power").isTextual()) {
                    String power = item.path("power").asText();
                    if ("on".equals(power)) result.put("closeOrOpen", 1);
                    else if ("off".equals(power)) result.put("closeOrOpen", 0);
                }
            }
            case "access" -> {
                if (command.isObject() && command.has("controlType")) result.set("controlType", command.get("controlType"));
                else if (item.path("controlType").isIntegralNumber()) result.set("controlType", item.get("controlType"));
            }
            case "ac" -> {
                if (command.isObject() && command.size() > 0) {
                    command.fields().forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue()));
                } else if (item.path("ac").isObject()) {
                    item.path("ac").fields().forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue()));
                } else {
                    for (String field : List.of("power", "temperature", "mode", "fanSpeed")) {
                        if (item.has(field)) result.set(field, item.get(field));
                    }
                }
            }
            default -> throw new BusinessException("UNSUPPORTED_ACTION_DRAFT", "设备类型不可控", 422);
        }
        return result;
    }

    private Set<String> excludedIds(ObjectNode payload, Map<String, AuthorizedDevice> universe,
                                    Map<String, String> exclusionRefs) {
        JsonNode raw = payload.get("excludedDeviceIds");
        if (raw == null || raw.isNull()) return new HashSet<>();
        if (!raw.isArray()) throw new BusinessException("INVALID_ACTION_DRAFT", "excludedDeviceIds 无效");
        Set<String> excluded = new HashSet<>();
        for (JsonNode value : raw) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "排除设备不属于已核验的候选范围");
            }
            String id = exclusionRefs.getOrDefault(value.asText(), value.asText());
            if (!excluded.add(id) || !universe.containsKey(id)) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "排除设备不属于已核验的候选范围");
            }
        }
        return excluded;
    }

    private Set<String> recommendedIds(ObjectNode payload, Map<String, AuthorizedDevice> universe,
                                       Map<String, String> handleIndex) {
        JsonNode raw = payload.get("recommendedDeviceIds");
        if (raw == null || raw.isNull()) return Set.of();
        if (!raw.isArray()) throw new BusinessException("INVALID_ACTION_DRAFT", "recommendedDeviceIds 无效");
        Set<String> recommended = new HashSet<>();
        for (JsonNode value : raw) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "默认选中设备不属于本次草案");
            }
            String id = handleIndex.getOrDefault(value.asText(), value.asText());
            if (!recommended.add(id) || !universe.containsKey(id)) {
                throw new BusinessException("INVALID_ACTION_DRAFT", "默认选中设备不属于本次草案");
            }
        }
        return recommended;
    }

    private DeviceStateReadResult readStates(PrincipalInfo principal, IotSourceEnum source, List<String> ids) {
        RequestIdentity identity = new RequestIdentity(principal.authorization(), principal.tenantCode(), source, ownerKey(principal));
        try {
            DeviceStateReadResult result = iot.readDeviceStates(identity, ids).block(scopeTimeout);
            if (result == null) throw new UpstreamUnavailableException("设备当前状态");
            return result;
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ActionDraftUnavailableException("读取设备当前状态失败", error);
        }
    }

    private ObjectNode action(AuthorizedDevice device, JsonNode command, Object state, boolean satisfied) {
        ObjectNode action = mapper.createObjectNode();
        action.put("id", UUID.randomUUID().toString());
        action.put("deviceId", device.id());
        action.put("kind", device.kind());
        action.put("deviceName", device.name());
        action.put("name", device.name());
        action.put("location", device.installAddress());
        action.set("beforeState", mapper.valueToTree(state));
        action.set("command", command.deepCopy());
        if ("ac".equals(device.kind())) {
            action.set("parameters", mapper.valueToTree(AcCommandCapabilities.parameters()));
        }
        action.put("satisfied", satisfied);
        return action;
    }

    private ObjectNode inventoryRow(AuthorizedDevice device, Object state, JsonNode command,
                                    String disposition, boolean satisfied) {
        ObjectNode row = mapper.createObjectNode();
        row.put("deviceId", device.id());
        row.put("kind", device.kind());
        row.put("name", device.name());
        row.put("location", device.installAddress());
        row.set("state", mapper.valueToTree(state));
        if (command != null) row.set("command", command.deepCopy());
        row.put("disposition", disposition);
        row.put("satisfied", satisfied);
        return row;
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

    private ObjectNode objectNode(JsonNode node) {
        return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : mapper.createObjectNode();
    }

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
        try { return ActionDraftStatusEnum.valueOf(draft.status()).terminal(); }
        catch (IllegalArgumentException error) { return false; }
    }

    private String tenantOf(ActionDraftFact draft) { return payload(draft).path("tenantCode").asText(""); }

    private static String ownerKey(PrincipalInfo principal) {
        return principal.ownerKey();
    }

    private IotSourceEnum parseSource(String value) {
        try { return IotSourceEnum.parse(value); }
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
        ObjectNode body = payload(draft);
        ObjectNode event = mapper.valueToTree(view(draft));
        event.put("type", type);
        event.put("draftId", draft.draftId());
        event.put("status", draft.status());
        event.put("revision", draft.revision());
        event.put("expiresAt", body.path("expiresAt").asText(null));
        if (body.has("conversationId")) event.put("conversationId", body.path("conversationId").asText());
        if (body.has("turnId")) event.put("turnId", body.path("turnId").asText());
        if (body.has("summary")) event.put("summary", body.path("summary").asText(""));
        if (body.has("question") && !body.get("question").isNull()) event.put("question", body.path("question").asText());
        event.set("selectedActionIds", array(body, "selectedActionIds").deepCopy());
        event.set("actions", array(body, "actions").deepCopy());
        event.set("inventory", array(body, "inventory").deepCopy());
        event.set("counts", objectNode(body.get("counts")));
        if (body.has("result") && body.get("result").isObject()) event.set("result", body.get("result").deepCopy());
        String conversationId = body.path("conversationId").asText("");
        String turnId = body.path("turnId").asText("");
        synchronized (conversationEvents) {
            long sequence = conversationEvents.read(conversationId).stream().filter(e -> turnId.equals(e.turnId())).mapToLong(ConversationEventFact::sequence).max().orElse(0) + 1;
            event.put("turnId", turnId);
            event.put("sequence", sequence);
            event.put("timestamp", clock.instant().toString());
            conversationEvents.append(new ConversationEventFact(conversationId, turnId, sequence, type, json(event), draft.draftId() + ":" + type + ":" + draft.revision()));
        }
    }

    private void audit(ActionDraftFact draft, String type) {
        if (audit == null) return;
        // Audit copy carries the event time; the stored draft payload is not mutated.
        ObjectNode body = payload(draft).deepCopy();
        body.put("at", clock.instant().toString());
        audit.append(new AuditEventFact(UUID.randomUUID().toString(), tenantOf(draft), type,
                json(body)));
    }

    /**
     * The durable idempotency column is bounded. A readable composite key over
     * tenant + owner + source + caller key exceeds it for real identities, so the
     * stored binding is a fixed-length SHA-256 of those exact components. The hash
     * is canonical, not a fallback: equality and isolation semantics are unchanged.
     */
    private static String scopedIdempotencyKey(PrincipalInfo principal, IotSourceEnum source, String idempotencyKey) {
        String material = principal.tenantCode() + "\u0000" + ownerKey(principal)
                + "\u0000" + source.wireValue() + "\u0000" + idempotencyKey;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(70).append("draft:");
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
