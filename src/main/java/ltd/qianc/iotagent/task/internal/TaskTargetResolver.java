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
import ltd.qianc.iotagent.iot.DeviceCapabilityInspection;
import ltd.qianc.iotagent.iot.DeviceCapabilityRequest;
import ltd.qianc.iotagent.iot.DeviceStateReadResult;
import ltd.qianc.iotagent.iot.DeviceCandidateBinding;
import ltd.qianc.iotagent.iot.DeviceCandidateQuery;
import ltd.qianc.iotagent.iot.DeviceCandidateRecord;
import ltd.qianc.iotagent.iot.IotAdapter;
import ltd.qianc.iotagent.iot.IotSource;
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
                IotSource.parse(source), principal.ownerKey());
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
        for (JsonNode value : request.path("phases")) {
            if (!(value instanceof ObjectNode phase)) continue;
            phase.remove("scopeFacts");
            if (phase.has("targets")) resolver.targets(phase);
            resolver.readAll(phase, request);
        }
        resolver.validateCandidateReferences(request);
    }

    private void targets(ObjectNode phase) {
        if (identity.source() != IotSource.COMPANY) throw invalid("公司范围目标不能用于办公室来源");
        if (!phase.path("targets").isArray() || phase.path("targets").isEmpty()) throw invalid("任务缺少目标范围");
        if (!phase.path("actions").isEmpty() || phase.has("read")) throw invalid("范围目标不能混用逐设备动作或只读检查");
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
                    AuthorizedDevice device = record.members().stream().filter(d -> id.equals(d.id())).findFirst()
                            .orElseThrow(() -> invalid("所选设备不属于声明的候选集"));
                    if (!kind.equals(device.kind())) throw invalid("目标类型与候选设备不一致");
                    verifyMember(device);
                    members.put(id, device);
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
            if (target.has("excludeCandidateSetIds") && !target.path("excludeCandidateSetIds").isEmpty()) {
                // An exclusion may reference an already-discovered exact candidate
                // set (Runtime excludeCandidateSetIds): the server re-verifies its
                // binding, completeness and resolution, requires every member to
                // belong to this target's included members, and unions the result
                // with location exclusions — a reference never widens the scope.
                if (selection) throw invalid("精确选择不能混用地点排除");
                for (String id : texts(target.path("excludeCandidateSetIds"))) {
                    DeviceCandidateRecord record = candidate(id);
                    if (!"exact".equals(record.resolutionMode()) || record.members().isEmpty()
                            || record.members().size() != record.totalMatches()) {
                        throw invalid("排除候选集必须是已核验的完整精确范围：" + id);
                    }
                    String label = record.query().scopeText();
                    if ((label == null || label.isBlank()) && !record.query().scopeTerms().isEmpty()) {
                        label = String.join("、", record.query().scopeTerms());
                    }
                    for (AuthorizedDevice device : record.members()) {
                        if (!kind.equals(device.kind())) throw invalid("排除范围类型与目标不一致");
                        if (!members.containsKey(device.id())) throw invalid("排除范围不完全属于包含目标");
                        excludedIds.add(device.id());
                        targetExcluded.add(device.id());
                    }
                    excludedLabels.add(label == null || label.isBlank() ? "已核验排除范围" : label);
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
        preflight(phase, fixed);
        // Confirmation and recurring materialization must retain fixed members, never re-expand live scope.
        phase.remove("targets");
    }

    /**
     * Runtime control-task preflight (company-control-task.ts): every expanded
     * member is capability- and binding-checked before the proposal exists — a
     * truly incapable or read-only target rejects the proposal. Current states
     * are only RECORDED (satisfied / unreadable / needs dispatch); already
     * satisfied or unreadable members stay in the future task, never dropped,
     * because the state at execution time is what counts.
     */
    private void preflight(ObjectNode phase, ArrayNode actions) {
        if (iot == null) throw invalid("当前设备授权范围暂不可用");
        for (JsonNode action : actions) {
            String id = action.path("deviceId").asText();
            AuthorizedDevice device = scope().device(id)
                    .orElseThrow(() -> invalid("目标已不在当前授权范围：" + id));
            if (device.observationOnly()) throw invalid("控制目标包含只读设备：" + id);
            if (device.controlRef() == null || device.controlRef().isBlank()) {
                throw invalid("控制目标缺少真实控制绑定：" + id);
            }
        }
        // Match Runtime's fixed candidate reads: revalidate only the participating
        // families, not the entire seven-family catalog again for every phase.
        requireConversation();
        Map<String, List<String>> idsByKind = new LinkedHashMap<>();
        for (JsonNode action : actions) {
            idsByKind.computeIfAbsent(action.path("kind").asText(), ignored -> new ArrayList<>())
                    .add(action.path("deviceId").asText());
        }
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections = new ArrayList<>();
        for (var group : idsByKind.entrySet()) {
            var found = iot.findDeviceCandidates(identity,
                    new DeviceCandidateQuery(group.getKey(), group.getValue(), null, List.of(), List.of(), null, null),
                    new DeviceCandidateBinding(conversation)).block(timeout);
            if (found == null) throw invalid("控制目标候选范围暂不可核验");
            DeviceCandidateRecord record = candidate(found.candidateSetId());
            if (record.members().size() != group.getValue().size()
                    || !record.members().stream().map(AuthorizedDevice::id).collect(
                            java.util.stream.Collectors.toSet()).equals(new LinkedHashSet<>(group.getValue()))) {
                throw invalid("控制目标候选成员发生变化，请重新准备");
            }
            selections.add(new DeviceCapabilityRequest.DeviceCapabilitySelection(
                    record.candidateSetId(), group.getValue()));
        }
        DeviceCapabilityRequest fixed = new DeviceCapabilityRequest(selections);
        DeviceCapabilityInspection inspection;
        try {
            inspection = iot.inspectDeviceCapabilities(identity, fixed, conversation).block(timeout);
        } catch (RuntimeException failure) {
            throw invalid("控制目标能力暂不可核验，请稍后重试");
        }
        if (inspection == null) throw invalid("控制目标能力暂不可核验，请稍后重试");
        Map<String, DeviceCapabilityInspection.DeviceCapability> capabilities = new LinkedHashMap<>();
        for (DeviceCapabilityInspection.DeviceCapability capability : inspection.devices()) {
            capabilities.put(capability.deviceId(), capability);
        }
        for (JsonNode action : actions) {
            String id = action.path("deviceId").asText();
            String kind = action.path("kind").asText();
            DeviceCapabilityInspection.DeviceCapability capability = capabilities.get(id);
            if (capability == null || !kind.equalsIgnoreCase(capability.kind())) {
                throw invalid("控制目标包含未核验的控制能力：" + id);
            }
            if ("lighting".equals(kind)) {
                int closeOrOpen = action.path("command").path("closeOrOpen").asInt(-1);
                boolean supported = capability.supportedCommands().stream().anyMatch(command ->
                        command.get("closeOrOpen") instanceof Number number && number.intValue() == closeOrOpen);
                if (!supported) throw invalid("控制目标不支持下达该照明指令：" + id);
            } else if ("ac".equals(kind)) {
                if (capability.parameters() == null) throw invalid("控制目标不支持下达空调指令：" + id);
            } else {
                throw invalid("设备类型不可控：" + id);
            }
        }
        // A future action is authorized by its verified binding/capability, not today's
        // availability. Retain unknown members; authentication and scope errors still fail closed.
        DeviceStateReadResult states = iot.readDeviceStates(identity, fixed, conversation)
                .timeout(timeout)
                .onErrorResume(failure -> failure instanceof ltd.qianc.iotagent.iot.UpstreamUnavailableException
                                || failure instanceof java.util.concurrent.TimeoutException,
                        failure -> reactor.core.publisher.Mono.empty())
                .block();
        int satisfied = 0, unreadable = 0;
        for (JsonNode action : actions) {
            Object state = states == null ? null : states.states().get(action.path("deviceId").asText());
            if (!readable(action, state)) unreadable++;
            else if (satisfied(action, state)) satisfied++;
        }
        ObjectNode preflight = phase.putObject("preflight");
        preflight.setAll((ObjectNode) phase.get("scopeFacts"));
        preflight.put("observedAt", states == null || states.observedAt() == null ? "" : states.observedAt());
        preflight.put("targetCount", actions.size());
        preflight.put("alreadySatisfiedCount", satisfied);
        preflight.put("unreadableCount", unreadable);
        preflight.put("needsDispatchCount", actions.size() - satisfied - unreadable);
    }

    private static boolean readable(JsonNode action, Object state) {
        JsonNode command = action.path("command");
        if ("lighting".equals(action.path("kind").asText())) {
            return state instanceof Boolean || state instanceof Number
                    || state instanceof Map<?, ?> map && map.containsKey("closeOrOpen");
        }
        if (!(state instanceof Map<?, ?> map)) return false;
        for (String field : iterable(command.fieldNames())) {
            Object value = map.get(field);
            switch (field) {
                case "power" -> {
                    if (!"on".equals(value) && !"off".equals(value)) return false;
                }
                case "temperature" -> {
                    if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) return false;
                }
                case "mode", "fanSpeed" -> {
                    if (!(value instanceof String text) || text.isBlank()) return false;
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean satisfied(JsonNode action, Object state) {
        JsonNode command = action.path("command");
        if ("lighting".equals(action.path("kind").asText())) {
            boolean wantOn = command.path("closeOrOpen").asInt(0) == 1;
            if (state instanceof Boolean bool) return bool == wantOn;
            if (state instanceof Number number) return number.intValue() == (wantOn ? 1 : 0);
            if (state instanceof Map<?, ?> map && map.get("closeOrOpen") instanceof Number number) {
                return number.intValue() == (wantOn ? 1 : 0);
            }
            return false;
        }
        if (!(state instanceof Map<?, ?> map)) return false;
        for (String field : iterable(command.fieldNames())) {
            Object value = map.get(field);
            JsonNode expected = command.get(field);
            boolean matches = expected != null && expected.isNumber() && value instanceof Number number
                    ? Double.compare(number.doubleValue(), expected.asDouble()) == 0
                    : value != null && value.toString().equals(expected.asText(""));
            if (!matches) return false;
        }
        return true;
    }

    private static Iterable<String> iterable(java.util.Iterator<String> values) {
        List<String> result = new ArrayList<>();
        values.forEachRemaining(result::add);
        return result;
    }

    private void readAll(ObjectNode phase, ObjectNode request) {
        if (!(phase.get("read") instanceof ObjectNode read)) return;
        if (read.path("agent").isObject() && identity.source() != IotSource.COMPANY) {
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
        ArrayNode devices = mapper.createArrayNode();
        for (AuthorizedDevice device : scope().devices()) {
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
            if (identity.source() == IotSource.OFFICE) {
                if (focusRegionId.isBlank()) throw invalid("办公室全范围任务需要服务器绑定的关注区域");
                scope = scope.retain(device -> device.officeIds().contains(focusRegionId));
            }
        }
        return scope;
    }

    private void requireConversation() {
        scope();
        if (conversation.isBlank()) throw invalid("任务缺少服务器绑定的会话");
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

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_TASK_SCOPE", message, 400);
    }
}
