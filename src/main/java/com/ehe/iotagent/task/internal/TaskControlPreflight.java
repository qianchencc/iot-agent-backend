package com.ehe.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.AuthorizedDevice;
import com.ehe.iotagent.iot.AuthorizedDeviceScope;
import com.ehe.iotagent.iot.DeviceCandidateBinding;
import com.ehe.iotagent.iot.DeviceCandidateQuery;
import com.ehe.iotagent.iot.DeviceCandidateRecord;
import com.ehe.iotagent.iot.DeviceCapabilityInspection;
import com.ehe.iotagent.iot.DeviceCapabilityRequest;
import com.ehe.iotagent.iot.DeviceStateReadResult;
import com.ehe.iotagent.iot.IotAdapter;
import com.ehe.iotagent.iot.RequestIdentity;
import reactor.core.publisher.Mono;

/** One capability/state inspection for canonical control phases, regardless of the proposal expression or source. */
final class TaskControlPreflight {
    private final ObjectMapper mapper;
    private final IotAdapter iot;
    private final Duration timeout;

    TaskControlPreflight(ObjectMapper mapper, IotAdapter iot, Duration timeout) {
        this.mapper = mapper;
        this.iot = iot;
        this.timeout = timeout;
    }

    void apply(ObjectNode request, AuthorizedDeviceScope scope) {
        Map<String, Set<String>> idsByKind = new LinkedHashMap<>();
        for (JsonNode phase : request.path("phases")) {
            for (JsonNode action : phase.path("actions")) {
                idsByKind.computeIfAbsent(action.path("kind").asText(), ignored -> new LinkedHashSet<>())
                        .add(action.path("deviceId").asText());
            }
        }
        if (idsByKind.isEmpty()) return;
        RequestIdentity identity = scope.identity();
        String conversation = request.path("conversationId").asText("");
        if (conversation.isBlank()) throw invalid("任务缺少服务器绑定的会话");
        List<DeviceCapabilityRequest.DeviceCapabilitySelection> selections = new ArrayList<>(idsByKind.size());
        for (var group : idsByKind.entrySet()) {
            for (String id : group.getValue()) {
                AuthorizedDevice device = scope.device(id).orElseThrow(() -> invalid("目标已不在当前授权范围"));
                if (device.observationOnly() || device.controlRef() == null || device.controlRef().isBlank()) {
                    throw invalid("控制目标缺少已核验的真实控制绑定");
                }
            }
            List<String> ids = List.copyOf(group.getValue());
            var found = iot.findDeviceCandidates(identity,
                    new DeviceCandidateQuery(group.getKey(), ids, null, List.of(), List.of(), null, null),
                    new DeviceCandidateBinding(conversation)).block(timeout);
            if (found == null) throw unavailable();
            DeviceCandidateRecord fixed = iot.resolveDeviceCandidateSet(identity, found.candidateSetId(), conversation).block(timeout);
            if (fixed == null || !conversation.equals(fixed.conversationId())
                    || !identity.tenantCode().equals(fixed.tenantCode()) || !identity.ownerKey().equals(fixed.ownerKey())
                    || identity.source() != fixed.source()) throw invalid("能力核验候选集不属于当前身份、来源与会话");
            Set<String> actual = new LinkedHashSet<>();
            for (AuthorizedDevice member : fixed.members()) {
                AuthorizedDevice current = scope.device(member.id()).orElseThrow(() -> invalid("控制目标候选成员发生变化"));
                if (!current.bindingFingerprint().equals(member.bindingFingerprint())) throw invalid("设备绑定已变化，请重新准备");
                actual.add(member.id());
            }
            if (!"exact".equals(fixed.resolutionMode()) || fixed.members().size() != ids.size()
                    || fixed.totalMatches() != ids.size() || !actual.equals(group.getValue())) {
                throw invalid("控制目标候选成员发生变化，请重新准备");
            }
            selections.add(new DeviceCapabilityRequest.DeviceCapabilitySelection(fixed.candidateSetId(), ids));
        }
        DeviceCapabilityRequest fixed = new DeviceCapabilityRequest(selections);
        DeviceCapabilityInspection inspection;
        try {
            inspection = iot.inspectDeviceCapabilities(identity, fixed, conversation).block(timeout);
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        if (inspection == null) throw unavailable();
        Map<String, DeviceCapabilityInspection.DeviceCapability> capabilities = new LinkedHashMap<>();
        for (var capability : inspection.devices()) capabilities.put(capability.deviceId(), capability);
        for (JsonNode phase : request.path("phases")) {
            for (JsonNode action : phase.path("actions")) verifyCapability(action, capabilities);
        }
        // These are observations at preparation time, never grounds to remove a future target.
        DeviceStateReadResult states = iot.readDeviceStates(identity, fixed, conversation).timeout(timeout)
                .onErrorResume(failure -> failure instanceof com.ehe.iotagent.iot.UpstreamUnavailableException
                                || failure instanceof java.util.concurrent.TimeoutException,
                        failure -> Mono.empty()).block();
        for (JsonNode value : request.path("phases")) {
            if (!(value instanceof ObjectNode phase) || phase.path("actions").isEmpty()) continue;
            int satisfied = 0, unreadable = 0;
            for (JsonNode action : phase.path("actions")) {
                Object state = states == null ? null : states.states().get(action.path("deviceId").asText());
                if (!readable(action, state)) unreadable++;
                else if (satisfied(action, state)) satisfied++;
            }
            ObjectNode preflight = mapper.createObjectNode();
            if (phase.get("scopeFacts") instanceof ObjectNode facts) preflight.setAll(facts);
            if (!preflight.has("includedScopes")) {
                Set<String> names = new LinkedHashSet<>();
                for (JsonNode action : phase.path("actions")) {
                    String id = action.path("deviceId").asText();
                    AuthorizedDevice device = scope.device(id).orElseThrow(() -> invalid("目标已不在当前授权范围"));
                    names.add(device.name() == null || device.name().isBlank() ? id : device.name());
                }
                preflight.set("includedScopes", mapper.valueToTree(names));
            }
            if (!preflight.has("excludedScopes")) preflight.putArray("excludedScopes");
            if (!preflight.has("excludedCount")) preflight.put("excludedCount", 0);
            preflight.put("observedAt", states == null || states.observedAt() == null ? "" : states.observedAt());
            preflight.put("targetCount", phase.path("actions").size());
            preflight.put("alreadySatisfiedCount", satisfied);
            preflight.put("unreadableCount", unreadable);
            preflight.put("needsDispatchCount", phase.path("actions").size() - satisfied - unreadable);
            phase.set("preflight", preflight);
        }
    }

    private static void verifyCapability(JsonNode action, Map<String, DeviceCapabilityInspection.DeviceCapability> capabilities) {
        String kind = action.path("kind").asText();
        var capability = capabilities.get(action.path("deviceId").asText());
        if (capability == null || !kind.equalsIgnoreCase(capability.kind())) throw invalid("控制目标包含未核验的控制能力");
        if ("lighting".equals(kind)) {
            int power = action.path("command").path("closeOrOpen").asInt(-1);
            if (capability.supportedCommands().stream().noneMatch(command -> command.get("closeOrOpen") instanceof Number value
                    && value.intValue() == power)) throw invalid("控制目标不支持下达该照明指令");
        } else if ("ac".equals(kind)) {
            if (capability.parameters() == null) throw invalid("控制目标不支持下达空调指令");
        } else throw invalid("设备类型不可控");
    }

    private static boolean readable(JsonNode action, Object state) {
        if ("lighting".equals(action.path("kind").asText())) {
            if (state instanceof Boolean) return true;
            Object value = state instanceof Map<?, ?> map ? map.get("closeOrOpen") : state;
            return value instanceof Number number && Double.isFinite(number.doubleValue());
        }
        if (!(state instanceof Map<?, ?> map)) return false;
        for (Iterator<String> fields = action.path("command").fieldNames(); fields.hasNext();) {
            String field = fields.next();
            Object value = map.get(field);
            switch (field) {
                case "power" -> { if (!"on".equals(value) && !"off".equals(value)) return false; }
                case "temperature" -> { if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) return false; }
                case "mode", "fanSpeed" -> { if (!(value instanceof String text) || text.isBlank()) return false; }
                default -> { return false; }
            }
        }
        return true;
    }

    private static boolean satisfied(JsonNode action, Object state) {
        JsonNode command = action.path("command");
        if ("lighting".equals(action.path("kind").asText())) {
            boolean wantOn = command.path("closeOrOpen").asInt() == 1;
            if (state instanceof Boolean value) return value == wantOn;
            Object value = state instanceof Map<?, ?> map ? map.get("closeOrOpen") : state;
            return value instanceof Number number && number.intValue() == (wantOn ? 1 : 0);
        }
        if (!(state instanceof Map<?, ?> map)) return false;
        for (Iterator<Map.Entry<String, JsonNode>> fields = command.fields(); fields.hasNext();) {
            var field = fields.next();
            Object value = map.get(field.getKey());
            JsonNode expected = field.getValue();
            if (expected.isNumber() && value instanceof Number number) {
                if (Double.compare(number.doubleValue(), expected.asDouble()) != 0) return false;
            } else if (value == null || !value.toString().equals(expected.asText())) return false;
        }
        return true;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("INVALID_TASK_SCOPE", message, 400);
    }

    private static BusinessException unavailable() {
        return new BusinessException("TASK_CAPABILITY_UNAVAILABLE", "控制目标能力暂不可核验，请稍后重试", 503);
    }
}
