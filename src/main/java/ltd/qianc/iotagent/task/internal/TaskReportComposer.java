package ltd.qianc.iotagent.task.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import ltd.qianc.iotagent.task.TaskFact;
import ltd.qianc.iotagent.task.TaskPayloadSafety;
import ltd.qianc.iotagent.task.TaskPhaseStore;

/**
 * Builds the declarative report checkpoint passed to the report module. It never
 * authors statistics, blocks or totals: the report module resolves server
 * evidence from the requested type, period and authorized scope.
 */
public final class TaskReportComposer {
    private final ObjectMapper mapper;

    public TaskReportComposer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Returns null when the task did not request a report. */
    public String compose(TaskFact task, List<TaskPhaseStore.PhaseResult> results,
                          String outcome, Instant generatedAt) {
        JsonNode payload;
        try { payload = mapper.readTree(task.payloadJson()); }
        catch (RuntimeException error) { throw new IllegalArgumentException("任务事实内容无效", error); }
        JsonNode request = payload.path("reportRequest");
        if (!request.isObject()) return null;
        ObjectNode checkpoint = mapper.createObjectNode();
        checkpoint.set("reportRequest", TaskPayloadSafety.withoutCredentials(mapper, request));
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

    private JsonNode read(String value) {
        try { return mapper.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (RuntimeException error) { return mapper.createObjectNode(); }
    }
}
