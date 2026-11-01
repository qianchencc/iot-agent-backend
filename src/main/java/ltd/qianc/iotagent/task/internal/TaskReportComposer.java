package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskPhaseStore;

/**
 * Builds the declarative report checkpoint passed to the report module. It never
 * authors statistics, blocks or totals: the report module resolves server
 * evidence from the requested type, period and authorized scope. For AC
 * inspection tasks it adds the durable phase observations and the explicitly
 * assumption-based estimate as trusted evidence, never as measured savings.
 */
public final class TaskReportComposer {
    private final ObjectMapper mapper;

    public TaskReportComposer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns null when the task neither requested a report nor produced inspection evidence. */
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
            phase.set("definition", TaskPayloadSafety.withoutCredentials(mapper, read(result.definitionJson())));
            phase.set("result", TaskPayloadSafety.withoutCredentials(mapper, read(result.resultJson())));
            phases.add(phase);
        }
        checkpoint.set("phaseResults", phases);
        return checkpoint.toString();
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
