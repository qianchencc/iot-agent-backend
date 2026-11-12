package com.ehe.iotagent.agent.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ehe.iotagent.conversation.AgentEngineEvent;

/**
 * Per-run Java projection from generic engine events to the Runtime-compatible
 * public event contract. Business events and pending-confirmation state are
 * derived only from canonical tool results; internal tool.result events never
 * escape to the client.
 */
final class AgentBusinessEventProjection {

    private final Map<String, Object> focusRegion;
    private final Map<String, Object> reference;
    private final Clock clock;
    private final Map<String, Map<String, Object>> resultsByCall = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> devicesById = new LinkedHashMap<>();
    private boolean pendingConfirmation;
    private Map<String, Object> pendingFields = Map.of();
    private final AgentEnergyRunFacts energy = new AgentEnergyRunFacts();

    AgentBusinessEventProjection(Map<String, Object> focusRegion, Map<String, Object> reference, Clock clock) {
        this.focusRegion = focusRegion == null ? Map.of() : focusRegion;
        this.reference = reference == null ? Map.of() : reference;
        this.clock = clock;
    }

    List<AgentEngineEvent> project(AgentEngineEvent event) {
        if (event == null || event.type() == null) return List.of();
        return switch (event.type()) {
            case "run.started" -> List.of(withFields(event, "run.started", runStartedFields(event)));
            case "run.completed" -> List.of(pendingConfirmation
                    ? withFields(event, "run.awaiting_confirmation", completedFields(event))
                    : event);
            case "message.completed" -> {
                String receipt = energy.receipt(false, textOr(event.fields(), "content", "").isBlank());
                yield receipt == null ? List.of(event) : List.of(withFields(event, "message.completed",
                        Map.of("content", receipt, "phase", "final")));
            }
            case "run.failed" -> {
                String receipt = energy.receipt(true, true);
                yield receipt == null ? List.of(event) : List.of(
                        withFields(event, "message.completed", Map.of("content", receipt, "phase", "final")),
                        withFields(event, "run.completed", Map.of("interpretationStatus", "failed")));
            }
            case "heartbeat", "run.progress", "run.aborted",
                    "message.delta", "message.reclassified" -> List.of(event);
            case "tool.started" -> List.of(toolStarted(event));
            case "tool.progress" -> List.of(toolProgress(event));
            case "tool.completed" -> List.of(toolCompleted(event));
            case "tool.result" -> toolResult(event);
            default -> List.of();
        };
    }

    private Map<String, Object> runStartedFields(AgentEngineEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>(event.fields());
        fields.putIfAbsent("createdAt", Instant.now(clock).toString());
        if (!focusRegion.isEmpty()) fields.put("focusRegion", focusRegion);
        if (!reference.isEmpty()) fields.put("reference", reference);
        return fields;
    }

    private Map<String, Object> completedFields(AgentEngineEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>(event.fields());
        fields.putAll(pendingFields);
        return fields;
    }

    private AgentEngineEvent toolStarted(AgentEngineEvent event) {
        String tool = text(event.fields(), "tool");
        energy.started(text(event.fields(), "toolCallId"), tool, map(event.fields().get("arguments")));
        Map<String, Object> fields = new LinkedHashMap<>(event.fields());
        addLabel(tool, fields);
        return withFields(event, "tool.started", fields);
    }

    private AgentEngineEvent toolProgress(AgentEngineEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>(event.fields());
        addLabel(text(event.fields(), "tool"), fields);
        return withFields(event, "tool.progress", fields);
    }

    private AgentEngineEvent toolCompleted(AgentEngineEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>(event.fields());
        String tool = text(fields, "tool");
        addLabel(tool, fields);
        fields.putIfAbsent("status", "completed");
        // Runtime pi-runner.ts:1616-1637 parity — a call refused because the tool
        // phase closed (budget or no-tool finalization) is a skip with a stop
        // receipt, never a failed business step; real failures carry bounded text.
        String code = text(fields, "code");
        if ("AGENT_TOOL_PHASE_CLOSED".equals(code) || "AGENT_TOOL_BUDGET_EXHAUSTED".equals(code)) {
            fields.put("status", "skipped");
            fields.put("label", "停止继续处理");
            fields.put("summary", "已改用现有结果收尾");
        } else if ("failed".equals(fields.get("status"))) {
            fields.putIfAbsent("summary", "本步骤未完成");
            String error = text(fields, "error");
            if (error != null) {
                fields.putIfAbsent("details", Map.of("error",
                        error.length() > 200 ? error.substring(0, 200) : error));
            }
        }
        fields.remove("code");
        fields.remove("error");
        String callId = text(fields, "toolCallId");
        Map<String, Object> result = callId == null ? null : resultsByCall.remove(callId);
        if (result == null) result = Map.of();
        fields.putAll(energy.timeline(callId));
        if ("find_device_candidates".equals(tool) && !result.isEmpty() && "completed".equals(fields.get("status"))) {
            int count = count(result);
            fields.putIfAbsent("summary", "找到 " + count + " 个设备候选");
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (Object item : list(result.get("devices"))) {
                Map<String, Object> device = map(item);
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("deviceName", textOr(device, "name", ""));
                candidate.put("location", textOr(device, "location", ""));
                candidates.add(candidate);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("count", count);
            copy(result, details, "complete", "totalMatches");
            details.put("candidates", candidates);
            fields.putIfAbsent("details", details);
        }
        return withFields(event, "tool.completed", fields);
    }

    private List<AgentEngineEvent> toolResult(AgentEngineEvent event) {
        String tool = text(event.fields(), "tool");
        String callId = text(event.fields(), "toolCallId");
        if (tool == null) return List.of();
        Object raw = event.fields().get("result");
        if (!(raw instanceof Map<?, ?> rawMap)) return List.of();
        Map<String, Object> result = stringMap(rawMap);
        if (callId != null) resultsByCall.put(callId, result);
        energy.completed(callId, tool, result);
        boolean unavailableVideoState = "read_device_states".equals(tool)
                && "unavailable".equalsIgnoreCase(text(result, "status"))
                && !Boolean.FALSE.equals(result.get("ok"))
                && (list(result.get("blocks")).stream().map(AgentBusinessEventProjection::map)
                        .anyMatch(block -> "video".equals(text(block, "type")) && !list(block.get("devices")).isEmpty())
                    || map(result.get("states")).keySet().stream().anyMatch(id ->
                            "video".equals(text(devicesById.getOrDefault(id, Map.of()), "kind"))));
        if (isError(result) && !unavailableVideoState) return List.of();
        rememberDevices(tool, result);
        AgentToolCatalog.Definition definition = AgentToolCatalog.definition(tool);
        if (definition == null) return List.of();
        markPending(tool, result);
        return publicBusinessEvents(tool, result)
                .stream()
                .map(fields -> withFields(event, text(fields, "type"), withoutType(fields)))
                .toList();
    }

    private void markPending(String tool, Map<String, Object> result) {
        AgentToolCatalog.Definition definition = AgentToolCatalog.definition(tool);
        if (definition == null || definition.confirmation() == AgentToolCatalog.ConfirmationEnum.NONE) return;
        String status = text(result, "status");
        if (status != null && !"PENDING_CONFIRMATION".equalsIgnoreCase(status)) return;
        Map<String, Object> identity = new LinkedHashMap<>();
        switch (definition.confirmation()) {
            case ACTION -> {
                String draftId = text(result, "draftId");
                if (draftId == null) return;
                identity.put("draftId", draftId);
            }
            case TASK -> {
                String taskId = first(text(result, "taskId"), text(result, "proposalId"));
                if (taskId == null) return;
                identity.put("taskId", taskId);
            }
            case TASK_CHANGE -> {
                String changeId = first(text(result, "changeId"), text(result, "proposalId"));
                if (changeId == null) return;
                identity.put("changeId", changeId);
            }
            case TASK_CANCELLATION -> {
                String cancellationId = first(text(result, "cancellationId"), text(result, "proposalId"));
                if (cancellationId == null) return;
                identity.put("cancellationId", cancellationId);
            }
            default -> { }
        }
        if (!identity.isEmpty()) {
            pendingConfirmation = true;
            pendingFields = Collections.unmodifiableMap(identity);
        }
    }

    private List<Map<String, Object>> publicBusinessEvents(String tool, Map<String, Object> result) {
        return switch (tool) {
            case "propose_action_draft" -> List.of();
            case "read_action_draft" -> actionResolved(result);
            case "read_action_summary" -> actionResolved(result);
            case "propose_task" -> List.of(taskEvent("task.proposed", "taskId", result));
            case "propose_task_change" -> List.of(taskEvent("task.change.proposed", "changeId", result));
            case "propose_task_cancellation" -> List.of(taskEvent("task.cancellation.proposed", "cancellationId", result));
            case "query_alerts" -> List.of(observation("alerts", result, null));
            case "query_environment_history" -> List.of(observation("environment_history", result,
                    text(result, "observedAt")));
            case "query_energy_history" -> List.of(observation("energy_history", result,
                    text(result, "observedAt")));
            case "summarize_energy", "compare_energy" -> "meter-report".equals(result.get("type")) && AgentEnergyRunFacts.hasData(result)
                    ? List.of(observation("meter_report", result, text(result, "observedAt"))) : List.of();
            case "create_energy_report" -> reportArtifact(result);
            case "prepare_video_playback" -> videoPlayback(result);
            case "review_safety_event" -> safetyVideo(result);
            case "read_device_states" -> stateObservation(result);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> actionResolved(Map<String, Object> result) {
        String draftId = text(result, "draftId");
        String status = text(result, "status");
        if (draftId == null || status == null) return List.of();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "action.resolved");
        fields.put("draftId", draftId);
        fields.put("status", status);
        if (result.get("revision") instanceof Number revision) fields.put("revision", revision);
        return List.of(fields);
    }

    private Map<String, Object> taskEvent(String type, String identityKey, Map<String, Object> result) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", type);
        String proposalId = text(result, "proposalId");
        String identity = first(text(result, identityKey), proposalId);
        if (identity != null) fields.put(identityKey, identity);
        String expiresAt = text(result, "expiresAt");
        if (expiresAt != null) fields.put("expiresAt", expiresAt);
        Map<String, Object> task = taskView(type, result, identity);
        if (!task.isEmpty()) fields.put("task", task);
        return fields;
    }

    private Map<String, Object> taskView(String type, Map<String, Object> result, String identity) {
        Map<String, Object> task = new LinkedHashMap<>();
        if ("task.proposed".equals(type)) {
            task.putAll(map(result.get("payload")));
            if (identity != null) task.put("taskId", identity);
            task.put("created", false);
        } else {
            Map<String, Object> before = map(result.get("before"));
            Map<String, Object> after = map(result.get("after"));
            task.putAll(after.isEmpty() ? before : after);
            task.put("kind", "task.change.proposed".equals(type) ? "change" : "cancellation");
            if (identity != null) task.put(identityKeyFor(type), identity);
            Map<String, Object> target = map(result.get("target"));
            if (target.get("taskId") instanceof String taskId) task.put("taskId", taskId);
            if (target.get("planId") instanceof String planId) task.put("planId", planId);
            // Runtime app.ts task.change.proposed parity: the event task carries the
            // authoritative before/after/target snapshots. The flattened fields above
            // stay for existing consumers; snapshots are the source the confirmation
            // card renders, so they must never be dropped by this merge.
            if (!before.isEmpty()) task.put("before", before);
            if (!after.isEmpty()) task.put("after", after);
            if (!target.isEmpty()) task.put("target", target);
        }
        copy(result, task, "status", "version", "expiresAt");
        if (result.get("operation") instanceof String operation) task.put("operation", operation);
        return task;
    }

    private static String identityKeyFor(String type) {
        return "task.change.proposed".equals(type) ? "changeId" : "cancellationId";
    }

    private Map<String, Object> observation(String blockType, Map<String, Object> report, String observedAt) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", blockType);
        if ("alerts".equals(blockType)) {
            report.forEach((key, value) -> {
                if (!"type".equals(key)) block.put(key, value);
            });
        } else {
            block.put("report", report);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "observation.reported");
        fields.put("observedAt", observedAt == null || observedAt.isBlank() ? null : observedAt);
        fields.put("blocks", List.of(block));
        return fields;
    }

    private List<Map<String, Object>> reportArtifact(Map<String, Object> result) {
        Map<String, Object> report = new LinkedHashMap<>(map(result.get("report")));
        String reportId = text(result, "reportId");
        if (report.isEmpty()) {
            if (reportId == null) return List.of();
            report.put("id", reportId);
        } else if (reportId != null) {
            report.putIfAbsent("id", reportId);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "observation.reported");
        fields.put("observedAt", textOr(report, "generatedAt", Instant.now(clock).toString()));
        fields.put("blocks", List.of(Map.of("type", "report_artifact", "report", report)));
        return List.of(fields);
    }

    private List<Map<String, Object>> videoPlayback(Map<String, Object> result) {
        Map<String, Object> playback = map(result.get("videoPlayback"));
        if (playback.isEmpty()) return List.of();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "observation.reported");
        fields.put("observedAt", Instant.now(clock).toString());
        fields.put("blocks", List.of(Map.of("type", "video_playback", "report", playback)));
        return List.of(fields);
    }

    private List<Map<String, Object>> safetyVideo(Map<String, Object> result) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> single = map(result.get("videoPlayback"));
        if (!single.isEmpty()) blocks.add(Map.of("type", "video_playback", "report", single));
        for (Object item : list(result.get("videoPlaybacks"))) {
            Map<String, Object> playback = map(item);
            if (!playback.isEmpty()) blocks.add(Map.of("type", "video_playback", "report", playback));
        }
        if (blocks.isEmpty()) return List.of();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "observation.reported");
        fields.put("observedAt", Instant.now(clock).toString());
        fields.put("blocks", blocks);
        return List.of(fields);
    }

    private List<Map<String, Object>> stateObservation(Map<String, Object> result) {
        List<Map<String, Object>> suppliedBlocks = new ArrayList<>();
        for (Object item : list(result.get("blocks"))) {
            Map<String, Object> block = map(item);
            if (!block.isEmpty()) suppliedBlocks.add(block);
        }
        if (!suppliedBlocks.isEmpty()) {
            return List.of(stateEvent(result, suppliedBlocks));
        }
        Map<String, Object> states = map(result.get("states"));
        if (states.isEmpty()) return List.of();
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> sampleTimes = map(result.get("observedAtByDevice"));
        for (String kind : List.of("video", "environment", "energy", "safety")) {
            List<Map<String, Object>> devices = new ArrayList<>();
            for (Map.Entry<String, Object> entry : states.entrySet()) {
                Map<String, Object> device = devicesById.get(entry.getKey());
                if (device == null || !kind.equals(text(device, "kind"))) continue;
                Map<String, Object> row = observationDevice(kind, device, entry.getValue());
                row.put("observedAt", sampleTimes.getOrDefault(entry.getKey(), result.get("observedAt")));
                devices.add(row);
            }
            if (!devices.isEmpty()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", kind);
                block.put("devices", devices);
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) return List.of();
        return List.of(stateEvent(result, blocks));
    }

    private static Map<String, Object> stateEvent(Map<String, Object> result, List<Map<String, Object>> blocks) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "observation.reported");
        fields.put("observedAt", result.get("observedAt"));
        fields.put("blocks", blocks);
        for (String key : List.of("observedAtByDevice", "status", "missingDeviceIds", "cache")) {
            if (result.containsKey(key)) fields.put(key, result.get(key));
        }
        return fields;
    }

    private Map<String, Object> observationDevice(String kind, Map<String, Object> device, Object state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("deviceId", textOr(device, "id", ""));
        row.put("name", textOr(device, "name", ""));
        row.put("location", textOr(device, "location", ""));
        switch (kind) {
            case "video" -> row.put("online", state instanceof Boolean online ? online : null);
            case "environment", "energy" -> row.put("reading", state instanceof Map<?, ?> ? state : null);
            default -> {
                row.put("subtype", null);
                if (device.get("currentDevice") instanceof Map<?, ?> current) row.put("currentDevice", current);
            }
        }
        return row;
    }

    private void rememberDevices(String tool, Map<String, Object> result) {
        if (!"find_device_candidates".equals(tool) && !"get_device_details".equals(tool)) return;
        for (Object item : list(result.get("devices"))) {
            Map<String, Object> device = map(item);
            String id = text(device, "id");
            if (id != null) devicesById.put(id, device);
        }
    }

    private void addLabel(String tool, Map<String, Object> fields) {
        AgentToolCatalog.Definition definition = tool == null ? null : AgentToolCatalog.definition(tool);
        if (definition != null) fields.putIfAbsent("label", definition.label());
    }

    private static AgentEngineEvent withFields(AgentEngineEvent original, String type, Map<String, Object> fields) {
        return new AgentEngineEvent(original.runId(), original.conversationId(), original.turnId(), type,
                fields == null ? Map.of() : fields);
    }

    private static Map<String, Object> withoutType(Map<String, Object> fields) {
        Map<String, Object> copy = new LinkedHashMap<>(fields);
        copy.remove("type");
        return copy;
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) target.put(key, source.get(key));
        }
    }

    private static int count(Map<String, Object> result) {
        if (result.get("totalMatches") instanceof Number number) return number.intValue();
        if (result.get("count") instanceof Number number) return number.intValue();
        return list(result.get("devices")).size();
    }

    private static boolean isError(Map<String, Object> result) {
        if (Boolean.FALSE.equals(result.get("ok"))) return true;
        Object status = result.get("status");
        return status instanceof String value && Set.of("unavailable", "failed", "error").contains(value.toLowerCase(java.util.Locale.ROOT));
    }

    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> values)) return Set.of();
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (Object item : values) if (item instanceof String text) result.add(text);
        return result;
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String textOr(Map<String, Object> body, String key, String fallback) {
        String value = text(body, key);
        return value == null ? fallback : value;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        return stringMap(raw);
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
