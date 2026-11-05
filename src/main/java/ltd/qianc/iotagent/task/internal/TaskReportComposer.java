package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskPhaseStore;
import ltd.qianc.iotagent.task.TaskReportCheckpointComposer;

/**
 * Builds the declarative report checkpoint passed to the report module. It never
 * authors statistics, blocks or totals: the report module resolves server
 * evidence from the requested type, period and authorized scope. For AC
 * inspection tasks it adds the durable phase observations and the explicitly
 * assumption-based estimate as trusted evidence, never as measured savings.
 */
public final class TaskReportComposer implements TaskReportCheckpointComposer {
    private final ObjectMapper mapper;

    public TaskReportComposer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns null when the task neither requested a report nor produced inspection evidence. */
    @Override
    public String compose(TaskFact task, List<TaskPhaseStore.PhaseResult> results,
                          String outcome, Instant generatedAt) {
        JsonNode payload;
        try { payload = mapper.readTree(task.payloadJson()); }
        catch (Exception error) { throw new IllegalArgumentException("任务事实 JSON 无效", error); }
        JsonNode request = payload.path("reportRequest");
        ObjectNode inspection = inspectionEvidence(task.taskId(), results, outcome, generatedAt);
        if (!request.isObject() && inspection == null) return null;
        if (!request.isObject() && !"company".equals(payload.path("source").asText(""))) {
            // The report module's server-evidence source is company-only; office AC
            // inspection facts remain available in the task detail without a fake report.
            return null;
        }
        ObjectNode checkpoint = mapper.createObjectNode();
        if (request.isObject()) {
            checkpoint.set("reportRequest", TaskPayloadSafety.withoutCredentials(mapper, request));
        } else {
            checkpoint.set("reportRequest", inspectionRequest(payload, inspection, results));
        }
        if (inspection != null) checkpoint.set("inspectionEvidence", inspection);
        checkpoint.put("taskId", task.taskId());
        checkpoint.put("taskOutcome", outcome);
        checkpoint.put("generatedAt", generatedAt.toString());
        checkpoint.put("checkpointAt", generatedAt.toString());
        checkpoint.put("source", payload.path("source").asText("office"));
        checkpoint.put("tenantCode", payload.path("tenantCode").asText(""));
        checkpoint.put("ownerKey", payload.path("ownerKey").asText(""));
        String objective = payload.path("objective").asText("");
        if (objective.isBlank()) objective = payload.path("title").asText(payload.path("label").asText(""));
        checkpoint.put("objective", objective);
        ArrayNode phases = mapper.createArrayNode();
        for (TaskPhaseStore.PhaseResult result : results) {
            ObjectNode phase = mapper.createObjectNode();
            phase.put("phaseId", result.phaseId());
            phase.put("at", result.dueAt().toString());
            phase.put("status", result.status());
            phase.put("startedAt", result.startedAt() == null ? null : result.startedAt().toString());
            phase.put("endedAt", result.endedAt() == null ? null : result.endedAt().toString());
            if (result.failureReason() != null) phase.put("failureReason", result.failureReason());
            phase.set("definition", TaskPayloadSafety.withoutCredentials(mapper, read(result.definitionJson())));
            phase.set("result", TaskPayloadSafety.withoutCredentials(mapper, read(result.resultJson())));
            phases.add(phase);
        }
        checkpoint.set("phaseResults", phases);
        return checkpoint.toString();
    }

    /**
     * Runtime summarizeTaskReport: one device counted once across AC
     * checkpoints, honest failure tally, and the assumption-based energy
     * estimate only when a device was actually adjusted.
     */
    @Override
    public String summarize(TaskFact task, List<TaskPhaseStore.PhaseResult> results) {
        Map<String, List<String>> outcomesByDevice = new LinkedHashMap<>();
        Map<String, String> dispositionByDevice = new LinkedHashMap<>();
        int checkpoints = 0;
        for (TaskPhaseStore.PhaseResult result : results) {
            JsonNode observations = read(result.resultJson()).path("observations");
            if (!observations.isArray()) continue;
            for (JsonNode block : observations) {
                if (!"ac_inspection".equals(block.path("type").asText(""))) continue;
                checkpoints++;
                for (JsonNode device : block.path("devices")) {
                    String id = device.path("deviceId").asText("");
                    if (id.isBlank()) continue;
                    outcomesByDevice.computeIfAbsent(id, ignored -> new ArrayList<>())
                            .add(device.path("outcome").asText(""));
                    String disposition = device.path("disposition").asText("");
                    String current = dispositionByDevice.get(id);
                    if ("adjusted".equals(disposition)
                            || current == null
                            || ("not_dispatched".equals(disposition) && !"adjusted".equals(current))
                            || ("unknown".equals(disposition) && "adjustable".equals(current))) {
                        dispositionByDevice.put(id, disposition);
                    }
                }
            }
        }
        if (checkpoints == 0) return null;
        int adjusted = 0, satisfied = 0, skipped = 0, failed = 0, unknown = 0, unverified = 0;
        Set<String> failureOutcomes = Set.of("failed", "rejected", "missing", "unreadable", "read_failed");
        for (Map.Entry<String, List<String>> entry : outcomesByDevice.entrySet()) {
            String disposition = dispositionByDevice.get(entry.getKey());
            if ("adjusted".equals(disposition)) adjusted++;
            else if ("not_needed".equals(disposition)) satisfied++;
            else if ("not_dispatched".equals(disposition)) skipped++;
            else if ("unknown".equals(disposition)) unknown++;
            else unverified++;
            if (entry.getValue().stream().anyMatch(failureOutcomes::contains) && !"adjusted".equals(disposition)) failed++;
        }
        ObjectNode summary = mapper.createObjectNode();
        summary.put("checkpoints", checkpoints);
        ObjectNode devices = summary.putObject("devices");
        devices.put("total", outcomesByDevice.size()).put("adjusted", adjusted).put("satisfied", satisfied)
                .put("skipped", skipped).put("failed", failed).put("unknown", unknown).put("unverified", unverified);
        summary.set("energyImpact", energyImpact(results, adjusted));
        return summary.toString();
    }

    private JsonNode energyImpact(List<TaskPhaseStore.PhaseResult> results, int adjustedDevices) {
        for (TaskPhaseStore.PhaseResult result : results) {
            JsonNode estimate = read(result.resultJson()).path("energyEstimate");
            if (estimate.isObject() && estimate.has("value")) {
                ObjectNode impact = mapper.createObjectNode();
                impact.put("status", "estimated").put("basis", "assumption").put("estimated", true)
                        .put("value", estimate.path("value").asDouble()).put("unit", "度");
                impact.putObject("cost").put("value", estimate.path("costValue").asDouble(0)).put("unit", "元");
                impact.put("from", estimate.path("from").asText(null)).put("to", estimate.path("to").asText(null))
                        .put("deviceCount", estimate.path("deviceCount").asInt(adjustedDevices));
                impact.set("baseline", estimate.path("baseline").deepCopy());
                impact.set("assumptions", estimate.path("assumptions").deepCopy());
                impact.set("coverage", estimate.path("coverage").deepCopy());
                impact.put("reason", estimate.path("reason").asText("按调前设定温度与经验参数估算，非电表实测"));
                return impact;
            }
        }
        ObjectNode unavailable = mapper.createObjectNode();
        unavailable.put("status", "unavailable");
        unavailable.put("reason", adjustedDevices > 0
                ? "已调温，但缺少可用的调前设定温度或调温时长不足，暂不展示估算数字"
                : "本次没有实际调温，且没有可比较的电表事实");
        return unavailable;
    }

    private ObjectNode inspectionEvidence(String taskId, List<TaskPhaseStore.PhaseResult> results,
                                          String outcome, Instant generatedAt) {
        ArrayNode checkpoints = mapper.createArrayNode();
        LinkedHashSet<String> deviceIds = new LinkedHashSet<>();
        int adjusted = 0;
        int unconfirmed = 0;
        for (TaskPhaseStore.PhaseResult result : results) {
            JsonNode executed = read(result.resultJson());
            JsonNode definition = read(result.definitionJson());
            JsonNode observations = executed.path("observations");
            boolean added = false;
            if (observations.isArray()) {
                for (JsonNode block : observations) {
                    if (!"ac_inspection".equals(block.path("type").asText(""))) continue;
                    added = true;
                    ObjectNode checkpoint = mapper.createObjectNode();
                    checkpoint.put("phaseId", result.phaseId());
                    checkpoint.put("at", result.dueAt().toString());
                    checkpoint.put("status", result.status());
                    checkpoint.put("outcome", executed.path("outcome").asText(result.status()));
                    checkpoint.put("mode", block.path("mode").asText("inspect"));
                    checkpoint.put("target", block.path("criteria").path("setpoint").path("value")
                            .asInt(executed.path("target").asInt(26)));
                    checkpoint.set("criteria", TaskPayloadSafety.withoutCredentials(mapper, block.path("criteria")));
                    checkpoint.set("devices", TaskPayloadSafety.withoutCredentials(mapper, block.path("devices")));
                    JsonNode estimate = executed.path("energyEstimate");
                    if (estimate.isObject()) {
                        ObjectNode safeEstimate = (ObjectNode) TaskPayloadSafety.withoutCredentials(mapper, estimate);
                        safeEstimate.put("label", "估算，非电表实测节能");
                        checkpoint.set("energyEstimate", safeEstimate);
                    }
                    for (JsonNode device : block.path("devices")) {
                        String id = device.path("deviceId").asText("");
                        if (!id.isBlank()) deviceIds.add(id);
                        String disposition = device.path("disposition").asText("");
                        if ("adjusted".equals(disposition)) adjusted++;
                        String deviceOutcome = device.path("outcome").asText("");
                        if ("unconfirmed".equals(deviceOutcome) || "unknown".equals(deviceOutcome)) unconfirmed++;
                    }
                    checkpoints.add(checkpoint);
                }
            }
            if (!added && definition.path("read").path("ac").isObject()) {
                ObjectNode checkpoint = mapper.createObjectNode();
                JsonNode ac = definition.path("read").path("ac");
                checkpoint.put("phaseId", result.phaseId());
                checkpoint.put("at", result.dueAt().toString());
                checkpoint.put("status", result.status());
                checkpoint.put("outcome", executed.path("outcome").asText(result.status()));
                checkpoint.put("mode", ac.path("mode").asText("inspect"));
                checkpoint.put("target", ac.path("target").asInt(26));
                checkpoint.set("criteria", TaskPayloadSafety.withoutCredentials(mapper, ac.path("condition")));
                ArrayNode devices = mapper.createArrayNode();
                for (JsonNode device : definition.path("read").path("devices")) {
                    ObjectNode value = mapper.createObjectNode();
                    String id = device.path("id").asText("");
                    value.put("deviceId", id);
                    value.put("name", device.path("name").asText(id));
                    value.put("location", device.path("location").asText(""));
                    value.put("disposition", "unknown");
                    value.put("outcome", result.status());
                    value.put("reason", executed.path("reason").asText("检查点未取得有效状态"));
                    value.put("observedAt", result.dueAt().toString());
                    devices.add(value);
                    if (!id.isBlank()) deviceIds.add(id);
                }
                checkpoint.set("devices", devices);
                checkpoints.add(checkpoint);
                unconfirmed += devices.size();
            }
        }
        if (checkpoints.isEmpty()) return null;
        ObjectNode evidence = mapper.createObjectNode();
        evidence.put("taskId", taskId);
        evidence.put("generatedAt", generatedAt.toString());
        evidence.put("taskOutcome", outcome);
        evidence.set("checkpoints", checkpoints);
        ObjectNode coverage = evidence.putObject("coverage");
        coverage.put("checkpoints", checkpoints.size());
        ArrayNode devices = coverage.putArray("devices");
        deviceIds.forEach(devices::add);
        coverage.put("adjusted", adjusted);
        coverage.put("unconfirmed", unconfirmed);
        return evidence;
    }

    private ObjectNode inspectionRequest(JsonNode payload, ObjectNode evidence, List<TaskPhaseStore.PhaseResult> results) {
        ObjectNode request = mapper.createObjectNode();
        request.put("kind", "ac_inspection");
        String title = payload.path("title").asText(payload.path("label").asText(""));
        request.put("title", title.isBlank() ? "空调巡检报告" : title);
        String objective = payload.path("objective").asText("");
        if (!objective.isBlank()) request.put("objective", objective);
        String from = null;
        String to = null;
        JsonNode interval = payload.path("interval");
        if (interval.isObject() && interval.path("startsAt").isTextual() && interval.path("endsAt").isTextual()) {
            from = interval.path("startsAt").asText();
            to = interval.path("endsAt").asText();
        }
        JsonNode checkpoints = evidence.path("checkpoints");
        if (checkpoints.isArray() && !checkpoints.isEmpty()) {
            if (from == null) from = checkpoints.get(0).path("at").asText();
            if (to == null) to = checkpoints.get(checkpoints.size() - 1).path("at").asText();
        }
        if (from == null || to == null || from.isBlank() || to.isBlank()) {
            throw new IllegalStateException("空调巡检缺少可验证的检查点时间，无法生成报告");
        }
        try {
            Instant start = Instant.parse(from);
            Instant end = Instant.parse(to);
            if (!end.isAfter(start)) {
                // A single checkpoint has one real business time; use the phase's
                // actual end only when it is later, never Instant.now().
                Instant fallback = null;
                for (TaskPhaseStore.PhaseResult result : results) {
                    if (result.endedAt() != null && result.endedAt().isAfter(start)
                            && (fallback == null || result.endedAt().isAfter(fallback))) {
                        fallback = result.endedAt();
                    }
                }
                if (fallback == null) {
                    throw new IllegalStateException("空调巡检只有单一检查点时间，无法确定报告期间");
                }
                end = fallback;
            }
            from = start.toString();
            to = end.toString();
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("空调巡检检查点时间无效，无法生成报告", invalid);
        }
        ObjectNode period = request.putObject("period");
        period.put("kind", "absolute").put("from", from).put("to", to);
        return request;
    }

    private JsonNode read(String value) {
        if (value == null || value.isBlank()) return mapper.createObjectNode();
        try { return mapper.readTree(value); }
        catch (Exception error) { throw new IllegalStateException("任务检查点 JSON 无效", error); }
    }
}
