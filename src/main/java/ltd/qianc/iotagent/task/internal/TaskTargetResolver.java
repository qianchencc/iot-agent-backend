package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ltd.qianc.iotagent.auth.PrincipalInfo;
import ltd.qianc.iotagent.error.BusinessException;
import ltd.qianc.iotagent.iot.AuthorizedDevice;
import ltd.qianc.iotagent.iot.AuthorizedDeviceScope;
import ltd.qianc.iotagent.iot.DeviceCandidateBinding;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSourceEnum;
import ltd.qianc.iotagent.iot.RequestIdentity;

/** Converts declarative goals into fixed authorized members, using the existing discovery seam. */
final class TaskTargetResolver {
    private final ObjectMapper mapper;
    private final IotAdapter iot;
    private final Duration timeout;
    private final RequestIdentity identity;
    private final String conversation;
    private final String focusRegionId;
    private AuthorizedDeviceScope scope;

    private TaskTargetResolver(ObjectMapper mapper, IotAdapter iot, Duration timeout, PrincipalInfo principal,
            String source, ObjectNode request) {
        this.mapper = mapper;
        this.iot = iot;
        this.timeout = timeout;
        this.identity = new RequestIdentity(principal.authorization(), principal.tenantCode(),
                IotSourceEnum.parse(source), principal.ownerKey());
        this.conversation = request.path("conversationId").asText("");
        this.focusRegionId = request.path("focusRegionId").asText("");
    }

    static void materialize(ObjectMapper mapper, IotAdapter iot, Duration timeout, PrincipalInfo principal,
            String source, ObjectNode request) {
        // fixedDeviceIds is a server-authored fact derived from the verified read
        // members; a model-provided copy would forge the report's fixed scope.
        if (request.path("reportRequest").has("fixedDeviceIds")) {
            throw invalid("报告固定成员由服务器按已确认只读范围写入，不能直接提供");
        }
        if (!request.path("phases").isArray()) return;
        var resolver = new TaskTargetResolver(mapper, iot, timeout, principal, source, request);
        long targetPhases = java.util.stream.StreamSupport.stream(request.path("phases").spliterator(), false)
                .filter(phase -> !phase.path("targets").isEmpty()).count();
        if (targetPhases > 0 && (targetPhases != request.path("phases").size()
                || request.has("candidateSetIds") || request.has("interval"))) {
            throw invalid("分阶段目标不能混用候选集任务、只读检查点或区间");
        }
        // Runtime proposeTaskTool: model device handles resolve against the referenced
        // candidate sets before any member validation; non-handles pass through.
        resolver.resolvePhaseDeviceHandles(request);
        for (JsonNode value : request.path("phases")) {
            if (!(value instanceof ObjectNode phase)) continue;
            // scopeFacts/preflight are server-authored phase facts; a model-supplied
            // copy would forge the card's scope and pre-scan counts.
            phase.remove("scopeFacts");
            phase.remove("preflight");
            if (phase.has("targets")) {
                if (phase.path("targets").isArray() && phase.path("targets").isEmpty()) phase.remove("targets");
                else resolver.targets(phase);
            }
            if (phase.has("lighting")) resolver.lighting(phase, request);
            resolver.readAll(phase, request);
        }
        resolver.validateCandidateReferences(request);
    }

    /** d{N}_{k} → real device id over the union of referenced candidate sets (sN or UUID refs). */
    private void resolvePhaseDeviceHandles(ObjectNode request) {
        if (!request.path("candidateSetIds").isArray() || request.path("candidateSetIds").isEmpty()) return;
        Map<String, String> handleIndex = new LinkedHashMap<>();
        ArrayNode setIds = (ArrayNode) request.get("candidateSetIds");
        for (int index = 0; index < setIds.size(); index++) {
            if (!setIds.get(index).isTextual() || setIds.get(index).asText().isBlank()) continue;
            // Runtime proposeTaskTool maps every set ref through resolveDeviceCandidateSet first,
            // so sN handles become real ids before phase member validation.
            DeviceCandidateRecord record = candidate(setIds.get(index).asText());
            setIds.set(index, mapper.getNodeFactory().textNode(record.candidateSetId()));
            indexHandles(record, handleIndex);
        }
        if (handleIndex.isEmpty()) return;
        for (JsonNode value : request.path("phases")) {
            if (!(value instanceof ObjectNode phase)) continue;
            for (JsonNode action : phase.withArray("actions")) {
                if (action instanceof ObjectNode node && node.path("deviceId").isTextual()) {
                    node.put("deviceId", handleIndex.getOrDefault(node.path("deviceId").asText(),
                            node.path("deviceId").asText()));
                }
            }
            ObjectNode read = (ObjectNode) phase.get("read");
            if (read != null && read.path("deviceIds").isArray()) {
                ArrayNode ids = (ArrayNode) read.get("deviceIds");
                for (int index = 0; index < ids.size(); index++) {
                    if (ids.get(index).isTextual()) ids.set(index, mapper.getNodeFactory().textNode(
                            handleIndex.getOrDefault(ids.get(index).asText(), ids.get(index).asText())));
                }
            }
            ObjectNode lighting = (ObjectNode) phase.get("lighting");
            if (lighting == null) continue;
            if (lighting.path("candidateSetId").isTextual() && !lighting.path("candidateSetId").asText().isBlank()) {
                lighting.put("candidateSetId", candidate(lighting.path("candidateSetId").asText()).candidateSetId());
            }
            if (lighting.path("excludeCandidateSetIds").isArray()) {
                ArrayNode exclusions = (ArrayNode) lighting.get("excludeCandidateSetIds");
                for (int index = 0; index < exclusions.size(); index++) {
                    if (exclusions.get(index).isTextual() && !exclusions.get(index).asText().isBlank()) {
                        exclusions.set(index, mapper.getNodeFactory().textNode(
                                candidate(exclusions.get(index).asText()).candidateSetId()));
                    }
                }
            }
            if (lighting.path("excludedDeviceIds").isArray()) {
                ArrayNode ids = (ArrayNode) lighting.get("excludedDeviceIds");
                for (int index = 0; index < ids.size(); index++) {
                    if (ids.get(index).isTextual()) ids.set(index, mapper.getNodeFactory().textNode(
                            handleIndex.getOrDefault(ids.get(index).asText(), ids.get(index).asText())));
                }
            }
        }
    }

    private void indexHandles(DeviceCandidateRecord record, Map<String, String> handleIndex) {
        if (record.handle() == null || record.handle().isBlank()) return;
        for (int position = 0; position < record.members().size(); position++) {
            handleIndex.putIfAbsent(record.deviceHandle(position), record.members().get(position).id());
        }
    }

    /** Runtime pi-runner.ts expandLightingScope: complete candidate range minus explicit subsets. */
    private void lighting(ObjectNode phase, ObjectNode request) {
        if (!phase.path("actions").isEmpty() || phase.has("read")) {
            throw invalid("照明范围检查点不能混用逐设备操作或只读目标");
        }
        JsonNode lighting = phase.path("lighting");
        String includedId = lighting.path("candidateSetId").asText();
        if (!texts(request.path("candidateSetIds")).contains(includedId)) {
            throw invalid("照明范围必须来自任务引用的候选集");
        }
        DeviceCandidateRecord included = candidate(includedId);
        if (included.members().isEmpty() || included.members().size() != included.totalMatches()) {
            throw scopeFailure("TASK_SCOPE_MEMBERS_INCOMPLETE", "照明候选成员未完整持有，请重新核实完整范围",
                    included, "members_incomplete", "find_device_candidates");
        }
        if (!"lighting".equals(included.query().kind())) {
            throw scopeFailure("TASK_SCOPE_KIND_MISMATCH", "照明检查点引用了其他类型候选，请查询照明设备",
                    included, "kind_mismatch", "find_device_candidates");
        }
        if (!included.permitsScopeExpansion(false)) {
            throw scopeFailure("TASK_SCOPE_UNRESOLVED",
                    "照明候选仅为模糊或未确定范围，不能直接编排整个集合；请补查明确设备或澄清范围，不要重复相同地点检索",
                    included, "scope_unresolved", "get_device_details");
        }
        Map<String, AuthorizedDevice> members = new LinkedHashMap<>();
        for (AuthorizedDevice device : included.members()) {
            verifyMember(device);
            members.put(device.id(), device);
        }
        Set<String> excluded = new LinkedHashSet<>();
        Set<String> excludedLabels = new LinkedHashSet<>();
        if (!lighting.path("excludedDeviceIds").isEmpty()) excluded.addAll(texts(lighting.path("excludedDeviceIds")));
        if (!lighting.path("excludeCandidateSetIds").isEmpty()) {
            for (String id : texts(lighting.path("excludeCandidateSetIds"))) {
                DeviceCandidateRecord subset = candidate(id);
                if (subset.members().isEmpty() || subset.members().size() != subset.totalMatches()
                        || !"lighting".equals(subset.query().kind()) || !subset.permitsScopeExpansion(true)) {
                    throw scopeFailure("TASK_EXCLUSION_UNRESOLVED", "照明排除范围必须是完整且精确查明的子集",
                            subset, "exclusion_unresolved", "get_device_details");
                }
                excludedLabels.add(scopeLabel(subset, "已核验排除范围"));
                for (AuthorizedDevice device : subset.members()) {
                    verifyMember(device);
                    excluded.add(device.id());
                }
            }
        }
        if (!members.keySet().containsAll(excluded)) throw invalid("排除范围不完全属于照明包含范围");
        if (!lighting.path("excludedDeviceIds").isEmpty()) {
            for (String id : texts(lighting.path("excludedDeviceIds"))) {
                String name = members.get(id).name();
                excludedLabels.add(name == null || name.isBlank() ? id : name);
            }
        }
        int command;
        try {
            command = ltd.qianc.iotagent.iot.DeviceCommands.lightingCloseOrOpen(lighting.path("closeOrOpen"));
        } catch (IllegalArgumentException invalidCommand) {
            throw invalid("照明开关指令无效");
        }
        ArrayNode actions = mapper.createArrayNode();
        for (AuthorizedDevice device : members.values()) {
            if (excluded.contains(device.id())) continue;
            ObjectNode action = actions.addObject().put("deviceId", device.id()).put("kind", "lighting");
            action.putObject("command").put("closeOrOpen", command);
        }
        if (actions.isEmpty()) throw invalid("排除后没有可安排的目标设备");
        phase.set("actions", actions);
        ObjectNode facts = phase.putObject("scopeFacts");
        facts.putArray("includedScopes").add(scopeLabel(included, "已核验照明范围"));
        facts.set("excludedScopes", mapper.valueToTree(excludedLabels));
        facts.put("targetCount", actions.size()).put("excludedCount", excluded.size());
        phase.remove("lighting");
    }

    private void targets(ObjectNode phase) {
        if (identity.source() != IotSourceEnum.COMPANY) throw invalid("公司范围目标不能用于办公室来源");
        if (!phase.path("targets").isArray() || phase.path("targets").isEmpty()) throw invalid("任务缺少目标范围");
        if (!phase.path("actions").isEmpty() || phase.has("read") || phase.has("lighting")) {
            throw invalid("范围目标不能混用逐设备动作、只读检查或照明候选范围");
        }
        Map<String, ObjectNode> actions = new LinkedHashMap<>();
        List<String> includedLabels = new ArrayList<>(), excludedLabels = new ArrayList<>();
        Set<String> excludedIds = new LinkedHashSet<>();
        for (JsonNode target : phase.path("targets")) {
            String kind = target.path("kind").asText();
            if (!Set.of("lighting", "ac").contains(kind)) throw invalid("任务范围仅支持照明与空调");
            boolean all = target.path("allAuthorized").isBoolean() && target.path("allAuthorized").asBoolean();
            boolean locations = target.path("scopeTexts").isArray() && !target.path("scopeTexts").isEmpty();
            boolean selection = target.path("selection").isObject();
            if ((all ? 1 : 0) + (locations ? 1 : 0) + (selection ? 1 : 0) != 1) {
                throw invalid("每个目标需且只能声明全部授权、明确地点或精确选择");
            }
            Set<String> fields = selection ? Set.of("kind", "command", "selection")
                    : Set.of("kind", "command", "allAuthorized", "scopeTexts", "excludeTexts");
            target.fieldNames().forEachRemaining(field -> {
                if (!fields.contains(field)) throw invalid("目标包含不支持的字段：" + field);
            });
            if (!target.path("command").isObject() || target.path("command").isEmpty()) throw invalid("任务缺少目标动作");
            Map<String, AuthorizedDevice> members = new LinkedHashMap<>();
            if (all) {
                for (AuthorizedDevice device : scope().devices()) if (kind.equals(device.kind())) members.put(device.id(), device);
                includedLabels.add("全部授权" + ("lighting".equals(kind) ? "照明" : "空调"));
            } else if (locations) {
                for (String location : texts(target.path("scopeTexts"))) {
                    for (AuthorizedDevice device : discover(kind, location)) members.put(device.id(), device);
                    includedLabels.add(location);
                }
            } else {
                JsonNode chosen = target.path("selection");
                DeviceCandidateRecord record = candidate(chosen.path("candidateSetId").asText());
                for (String id : texts(chosen.path("deviceIds"))) {
                    // Runtime company-control-task.ts: selection refs resolve inside their own set only.
                    String resolved = record.resolveDeviceRef(id.trim());
                    AuthorizedDevice device = record.members().stream().filter(d -> resolved.equals(d.id())).findFirst()
                            .orElseThrow(() -> invalid("所选设备不属于声明的候选集"));
                    if (!kind.equals(device.kind())) throw invalid("目标类型与候选设备不一致");
                    verifyMember(device);
                    members.put(resolved, device);
                }
                includedLabels.add("精确选择");
            }
            if (members.isEmpty()) throw invalid("当前授权范围没有匹配的目标设备");
            Set<String> targetExcluded = new LinkedHashSet<>();
            if (target.has("excludeTexts") && !target.path("excludeTexts").isEmpty()) {
                if (selection) throw invalid("精确选择不能混用地点排除");
                for (String location : texts(target.path("excludeTexts"))) {
                    for (AuthorizedDevice device : discover(kind, location)) {
                        if (!members.containsKey(device.id())) throw invalid("排除范围不完全属于包含目标");
                        excludedIds.add(device.id());
                        targetExcluded.add(device.id());
                    }
                    excludedLabels.add(location);
                }
            }
            for (AuthorizedDevice device : members.values()) {
                if (targetExcluded.contains(device.id())) continue;
                ObjectNode action = mapper.createObjectNode().put("deviceId", device.id()).put("kind", kind);
                action.set("command", target.path("command").deepCopy());
                ObjectNode prior = actions.putIfAbsent(device.id(), action);
                if (prior != null && !prior.equals(action)) throw invalid("同一阶段的设备动作冲突");
            }
        }
        if (actions.isEmpty()) throw invalid("排除后没有可安排的目标设备");
        ArrayNode fixed = mapper.createArrayNode();
        actions.values().forEach(fixed::add);
        phase.set("actions", fixed);
        ObjectNode scopeFacts = mapper.createObjectNode();
        scopeFacts.set("includedScopes", mapper.valueToTree(includedLabels));
        scopeFacts.set("excludedScopes", mapper.valueToTree(excludedLabels));
        excludedIds.removeAll(actions.keySet());
        scopeFacts.put("targetCount", fixed.size()).put("excludedCount", excludedIds.size());
        phase.set("scopeFacts", scopeFacts);
        // Confirmation and recurring materialization must retain fixed members, never re-expand live scope.
        phase.remove("targets");
    }


    private void readAll(ObjectNode phase, ObjectNode request) {
        if (!(phase.get("read") instanceof ObjectNode read)) return;
        if (read.path("agent").isObject() && identity.source() != IotSourceEnum.COMPANY) {
            throw invalid("用电检查点仅用于公司来源");
        }
        boolean explicitAll = read.path("all").isBoolean() && read.path("all").asBoolean();
        if (!explicitAll) {
            // A read checkpoint must name its fixed members. A declarative
            // reportRequest never widens a missing observation scope to "all",
            // and must not silently drop the read assignment either
            // (Runtime pi-runner rejects any read without deviceIds).
            boolean named = (read.path("deviceIds").isArray() && !read.path("deviceIds").isEmpty())
                    || (read.path("devices").isArray() && !read.path("devices").isEmpty());
            if (!named) throw invalid("只读检查需要明确设备或read.all=true，省略范围不表示全部");
            pinReportMembers(read, request, false);
            return;
        }
        if (!read.path("deviceIds").isEmpty() || !read.path("devices").isEmpty()) throw invalid("全部范围与精确设备不能混用");
        String kind = read.path("ac").isObject() ? "ac"
                : read.has("alertWindow") || read.has("alertDays") ? "safety"
                : read.path("agent").isObject() || request.path("reportRequest").path("kind").asText("").equals("energy") ? "energy" : "";
        if (kind.isBlank()) throw invalid("全范围只读缺少明确的观察类型");
        if (identity.source() == IotSourceEnum.OFFICE && focusRegionId.isBlank()) {
            throw invalid("办公室全范围任务需要服务器绑定的关注区域");
        }
        ArrayNode devices = mapper.createArrayNode();
        for (AuthorizedDevice device : scope().devices()) {
            if (identity.source() == IotSourceEnum.OFFICE && !device.officeIds().contains(focusRegionId)) continue;
            if (kind.equals(device.kind())) devices.addObject().put("id", device.id()).put("kind", kind);
        }
        if (devices.isEmpty()) throw invalid("当前授权范围没有可观察设备");
        read.set("devices", devices);
        read.remove("all");
        phase.putObject("scopeFacts").put("targetCount", devices.size()).put("allAuthorized", true);
        pinExpandedReportMembers(kind, devices, request);
    }

    /** Pins a declarative report to exactly the checkpoint's verified read members. */
    private void pinReportMembers(ObjectNode read, ObjectNode request, boolean wholeFamily) {
        if (!(request.get("reportRequest") instanceof ObjectNode reportRequest)) return;
        String kind = reportRequest.path("kind").asText("");
        if ("energy".equals(kind)) {
            pinEnergyReportScope(reportRequest, memberIds(read, "energy"), wholeFamily);
        } else if ("safety".equals(kind)) {
            List<String> members = memberIds(read, "safety");
            ArrayNode fixed = mapper.createArrayNode();
            members.forEach(fixed::add);
            reportRequest.set("fixedDeviceIds", fixed);
        }
    }

    private void pinExpandedReportMembers(String kind, ArrayNode devices, ObjectNode request) {
        if (!(request.get("reportRequest") instanceof ObjectNode reportRequest)) return;
        if ("energy".equals(kind) && "energy".equals(reportRequest.path("kind").asText(""))) {
            List<String> members = new ArrayList<>();
            devices.forEach(device -> members.add(device.path("id").asText()));
            pinEnergyReportScope(reportRequest, members, true);
        } else if ("safety".equals(kind) && "safety".equals(reportRequest.path("kind").asText(""))) {
            ArrayNode fixed = mapper.createArrayNode();
            devices.forEach(device -> fixed.add(device.path("id").asText()));
            reportRequest.set("fixedDeviceIds", fixed);
        }
    }

    private List<String> memberIds(ObjectNode read, String kind) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode id : read.path("deviceIds")) ids.add(id.asText());
        for (JsonNode device : read.path("devices")) ids.add(device.path("id").asText());
        List<String> verified = new ArrayList<>();
        for (String id : ids) {
            AuthorizedDevice device = scope().device(id)
                    .orElseThrow(() -> invalid("报告成员不在当前授权范围：" + id));
            if (!kind.equals(device.kind())) throw invalid("报告范围与只读成员类型不一致");
            verified.add(id);
        }
        if (verified.isEmpty()) throw invalid("报告缺少只读成员");
        return verified;
    }

    /**
     * The report may only cover the checkpoint's confirmed meters. An explicit
     * scope conflicting with them is rejected instead of widened or narrowed;
     * level=all stays acceptable only when the members are the whole family
     * (read.all), and is still rewritten to the fixed selection so later
     * additions never drift into the report.
     */
    private void pinEnergyReportScope(ObjectNode reportRequest, List<String> members, boolean wholeFamily) {
        if (members == null || members.isEmpty()) throw invalid("报告缺少已确认的电表成员");
        JsonNode scope = reportRequest.get("scope");
        if (scope != null && scope.isObject()) {
            var declared = ltd.qianc.iotagent.report.ReportMeterScope.resolve(
                    scope(), mapper.convertValue(scope, Object.class));
            boolean sameSet = declared.meterIds().size() == members.size()
                    && new LinkedHashSet<>(declared.meterIds()).containsAll(members);
            if (!sameSet || (!wholeFamily && "all".equals(declared.level()))) {
                throw invalid("报告范围与已确认的只读成员不一致，请让两者使用同一电表集合");
            }
        }
        ObjectNode fixedScope = mapper.createObjectNode().put("level", "selection");
        ArrayNode ids = mapper.createArrayNode();
        members.forEach(ids::add);
        fixedScope.set("meterIds", ids);
        reportRequest.set("scope", fixedScope);
    }

    private void validateCandidateReferences(ObjectNode request) {
        if (!request.path("candidateSetIds").isArray() || request.path("candidateSetIds").isEmpty()) return;
        boolean needsCandidates = false;
        for (JsonNode phase : request.path("phases")) {
            if (!phase.has("scopeFacts")) needsCandidates = true;
        }
        if (!needsCandidates) return;
        Map<String, AuthorizedDevice> allowed = new LinkedHashMap<>();
        for (String id : texts(request.path("candidateSetIds"))) {
            DeviceCandidateRecord record = candidate(id);
            if (record.members().size() != record.totalMatches()) {
                throw invalid("候选成员不完整，尚未生成提案");
            }
            for (AuthorizedDevice member : record.members()) {
                verifyMember(member);
                allowed.put(member.id(), member);
            }
        }
        for (JsonNode phase : request.path("phases")) {
            if (phase.has("scopeFacts")) continue;
            for (JsonNode action : phase.path("actions")) {
                if (!allowed.containsKey(action.path("deviceId").asText())) throw invalid("任务设备不属于引用的候选集");
            }
            for (JsonNode id : phase.path("read").path("deviceIds")) {
                if (!allowed.containsKey(id.asText())) throw invalid("只读设备不属于引用的候选集");
            }
            for (JsonNode device : phase.path("read").path("devices")) {
                if (!allowed.containsKey(device.path("id").asText())) throw invalid("只读设备不属于引用的候选集");
            }
        }
    }

    private List<AuthorizedDevice> discover(String kind, String location) {
        requireConversation();
        var query = new DeviceCandidateQuery(kind, List.of(), location, List.of(), List.of(), null, null);
        var found = iot.findDeviceCandidates(identity, query, new DeviceCandidateBinding(conversation)).block(timeout);
        if (found == null || found.totalMatches() == 0 || !"exact".equals(found.resolution().mode())) {
            throw invalid("范围尚未查明，请补充明确地点：" + location);
        }
        DeviceCandidateRecord record = candidate(found.candidateSetId());
        if (record.members().size() != record.totalMatches()) throw invalid("范围成员不完整，尚未生成提案");
        for (AuthorizedDevice device : record.members()) {
            if (!kind.equals(device.kind())) throw invalid("范围类型不一致");
            verifyMember(device);
        }
        return record.members();
    }

    private DeviceCandidateRecord candidate(String id) {
        requireConversation();
        if (id.isBlank()) throw invalid("缺少候选集来源");
        DeviceCandidateRecord record = iot.resolveDeviceCandidateSet(identity, id, conversation).block(timeout);
        if (record == null || !conversation.equals(record.conversationId()) || !identity.tenantCode().equals(record.tenantCode())
                || !identity.ownerKey().equals(record.ownerKey()) || identity.source() != record.source()) {
            throw invalid("候选集不属于当前身份、来源与会话");
        }
        return record;
    }

    private void verifyMember(AuthorizedDevice member) {
        AuthorizedDevice current = scope().device(member.id()).orElseThrow(() -> invalid("目标已不在当前授权范围"));
        if (!current.bindingFingerprint().equals(member.bindingFingerprint())) throw invalid("设备绑定已变化，请重新准备");
    }

    private AuthorizedDeviceScope scope() {
        if (iot == null) throw invalid("当前设备授权范围暂不可用");
        if (scope == null) {
            scope = iot.loadAuthorizedDeviceScope(identity).block(timeout);
            if (scope == null || scope.source() != identity.source() || !identity.equals(scope.identity())) throw invalid("当前设备授权范围暂不可用");
        }
        return scope;
    }

    private void requireConversation() {
        scope();
        if (conversation.isBlank()) throw invalid("任务缺少服务器绑定的会话");
    }

    private static String scopeLabel(DeviceCandidateRecord record, String fallback) {
        String label = record.query().scopeText();
        return label == null || label.isBlank() ? fallback : label.trim();
    }

    private static List<String> texts(JsonNode value) {
        if (!value.isArray() || value.isEmpty()) throw invalid("范围或选择必须明确且非空");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) throw invalid("范围或设备引用格式无效");
            if (!result.contains(item.asText().trim())) result.add(item.asText().trim());
        }
        return result;
    }

    private static BusinessException scopeFailure(String code, String message, DeviceCandidateRecord record,
                                                   String reason, String nextTool) {
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("reason", reason);
        if (record.handle() != null && !record.handle().isBlank()) recovery.put("candidateSet", record.handle());
        recovery.put("resolution", record.resolutionMode());
        recovery.put("nextTool", nextTool);
        recovery.put("retrySameScope", false);
        return new BusinessException(code, message, recovery, 422);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_TASK_SCOPE", message, 400);
    }
}
