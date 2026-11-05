package ltd.qianc.iotagent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import ltd.qianc.iotagent.task.TaskReadScope;

/**
 * Ports the Runtime public task projections (tasks/store.ts detail, taskListSummary,
 * plans.ts virtualTask/describe and report-summary.ts). It is a pure projection over
 * durable facts: every device, observation block or identity outside the caller's
 * {@link TaskReadScope} is trimmed exactly like the original catalog filters.
 */
public final class TaskDetailProjector {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** Read seam for durable phase rows of one task, ordered by (dueAt, id). */
    public interface TaskPhaseReader {
        List<PhaseRow> phases(String taskId);
    }

    public record PhaseRow(String phaseId, Instant at, String status, JsonNode definition, JsonNode result,
                           Instant startedAt, Instant endedAt, String failureReason) { }

    /** Everything the projections need about one durable task fact. */
    public record TaskSource(String taskId, long version, String status, JsonNode payload,
                             Instant createdAt, Instant updatedAt) { }

    /** Durable occurrence states of one plan keyed by local occurrence date. */
    public record OccurrenceState(LocalDate date, String taskId, String status, JsonNode overridePayload) { }

    /** Plan facts plus its persisted next pointer; mirrors the Runtime task_plans row. */
    public record PlanSource(String planId, long version, String status, JsonNode payload,
                             Instant nextAt, LocalDate nextDate, Instant createdAt, Instant updatedAt,
                             Map<LocalDate, OccurrenceState> occurrences) { }

    private static final Set<String> EXCEPTIONAL_STATUSES = Set.of("partial", "failed", "interrupted", "missed");
    private static final Set<String> TERMINAL_PHASE = Set.of(
            "COMPLETED", "PARTIAL", "FAILED", "UNCONFIRMED", "INTERRUPTED", "CANCELLED", "MISSED", "SATISFIED");

    private final ObjectMapper mapper;
    private final TaskPhaseReader phases;

    public TaskDetailProjector(ObjectMapper mapper, TaskPhaseReader phases) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.phases = Objects.requireNonNull(phases, "phases");
    }

    public List<PhaseRow> phases(String taskId) {
        return phases.phases(taskId);
    }

    // ---------------------------------------------------------------- detail

    /**
     * Full Runtime task detail or null when nothing of the task is visible to the
     * viewer. {@code planMetadata} carries repeat/nextAt/occurrence for materialized
     * recurring tasks exactly like the Runtime planMetadata() helper.
     */
    public Map<String, Object> detail(TaskSource task, TaskReadScope scope, String viewerKey,
                                       Map<String, Object> planMetadata) {
        List<PhaseRow> rows = phases(task.taskId());
        boolean creator = viewerKey.equals(text(task.payload(), "ownerKey"));
        List<DeviceRow> devices = new ArrayList<>();
        for (PhaseRow row : rows) devices.addAll(deviceRows(row));
        List<DeviceRow> visible = devices.stream()
                .filter(device -> scope.device(device.id()).isPresent()).toList();
        JsonNode agentReadPresent = firstAgentRead(rows);
        boolean minimalOwnerView = visible.isEmpty() && text(task.payload(), "sourcePlanId") != null
                && creator && agentReadPresent == null;
        if (visible.isEmpty() && !minimalOwnerView) return null;

        Map<String, Object> view = new LinkedHashMap<>(planMetadata);
        view.put("id", task.taskId());
        putIfValue(view, "agentSource", text(task.payload(), "source"));
        view.put("label", firstText(task.payload(), "label", "title", "objective"));
        view.put("status", lowerStatus(task.status()));
        view.put("version", task.version());
        view.put("interval", interval(task.payload()));
        view.put("executionActive", rows.stream().anyMatch(row -> "EXECUTING".equalsIgnoreCase(row.status())));
        view.put("report", report(task, rows, visible, scope, creator));
        view.put("regionIds", regionIds(task.payload(), scope));
        view.put("createdAt", createdAt(task));
        view.put("updatedAt", updatedAt(task));
        view.put("creator", Map.of("displayName", textOr(task.payload(), "creatorName", "")));
        view.put("source", sourceView(task.payload(), creator));
        view.put("canCancel", canCancel(task, rows, devices, scope));
        view.put("phases", phaseViews(rows, scope, creator, visible.isEmpty()));
        return view;
    }

    /** Runtime taskListSummary: checkpoint skeletons, no device rows, no report body. */
    public Map<String, Object> listSummary(Map<String, Object> detail) {
        Map<String, Object> summary = new LinkedHashMap<>(detail);
        List<Map<String, Object>> skeletons = new ArrayList<>();
        Object phases = detail.get("phases");
        if (phases instanceof List<?> rows) {
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> phase)) continue;
                Map<String, Object> skeleton = new LinkedHashMap<>();
                skeleton.put("id", phase.get("id"));
                skeleton.put("at", phase.get("at"));
                skeleton.put("status", phase.get("status"));
                skeleton.put("startedAt", phase.get("startedAt"));
                skeleton.put("endedAt", phase.get("endedAt"));
                skeleton.put("failureReason", phase.get("failureReason"));
                skeleton.put("devices", List.of());
                skeletons.add(skeleton);
            }
        }
        summary.put("phases", skeletons);
        Object report = detail.get("report");
        boolean hasReport = report instanceof Map<?, ?>;
        summary.put("report", null);
        summary.put("hasReport", hasReport);
        summary.put("reportAt", hasReport ? ((Map<?, ?>) report).get("generatedAt") : null);
        summary.remove("devices");
        return summary;
    }

    // -------------------------------------------------------------- phases

    private List<Map<String, Object>> phaseViews(List<PhaseRow> rows, TaskReadScope scope,
                                                 boolean creator, boolean noVisibleDevices) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (PhaseRow row : rows) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", row.phaseId());
            view.put("at", row.at().toString());
            view.put("status", lowerStatus(row.status()));
            view.put("startedAt", row.startedAt() == null ? null : row.startedAt().toString());
            view.put("endedAt", row.endedAt() == null ? null : row.endedAt().toString());
            view.put("failureReason", row.failureReason());
            JsonNode read = row.definition().path("read");
            List<DeviceRow> phaseDevices = deviceRows(row);
            boolean allEnergy = !phaseDevices.isEmpty() && phaseDevices.stream()
                    .allMatch(device -> "energy".equalsIgnoreCase(device.kind()));
            boolean allAc = !phaseDevices.isEmpty() && phaseDevices.stream()
                    .allMatch(device -> "ac".equalsIgnoreCase(device.kind()));
            if (read.path("agent").isObject() && creator && allEnergy) {
                view.put("read", Map.of("agent", publicValue(read.path("agent"))));
            } else if (read.path("ac").isObject() && creator && allAc) {
                Map<String, Object> readView = new LinkedHashMap<>();
                readView.put("ac", publicValue(read.path("ac")));
                view.put("read", readView);
                if (row.result().path("observations").isArray()) {
                    view.put("observations", visibleObservations(
                            (ArrayNode) row.result().path("observations"), row, scope, creator));
                }
            }
            if (row.result().path("timings").isObject()) view.put("timings", publicValue(row.result().path("timings")));
            if (row.result().has("summary") && creator && allEnergy && !noVisibleDevices) {
                // Opaque summaries/facts cannot be safely split after any phase device loses authorization.
                if (phaseDevices.stream().allMatch(device -> scope.device(device.id()).isPresent())) {
                    Map<String, Object> agentResult = new LinkedHashMap<>();
                    agentResult.put("summary", value(row.result().path("summary")));
                    if (row.result().has("status")) agentResult.put("status", value(row.result().path("status")));
                    if (row.result().path("facts").isArray()) agentResult.put("facts", publicValue(row.result().path("facts")));
                    view.put("agentResult", agentResult);
                }
                if (row.result().path("observations").isArray()) {
                    view.put("observations", visibleObservations(
                            (ArrayNode) row.result().path("observations"), row, scope, creator));
                }
            }
            List<Map<String, Object>> devices = new ArrayList<>();
            for (DeviceRow device : phaseDevices) {
                if (scope.device(device.id()).isEmpty()) continue;
                Map<String, Object> deviceView = new LinkedHashMap<>();
                deviceView.put("id", device.id());
                deviceView.put("kind", device.kind());
                deviceView.put("name", device.name());
                deviceView.put("location", device.location());
                deviceView.put("target", device.target() == null ? Map.of() : publicValue(device.target()));
                deviceView.put("outcome", device.outcome());
                deviceView.put("details", device.details());
                devices.add(deviceView);
            }
            view.put("devices", devices);
            views.add(view);
        }
        return views;
    }

    /** Durable per-phase device rows combining definition targets with executed outcomes. */
    public List<DeviceRow> deviceRows(PhaseRow row) {
        List<DeviceRow> devices = new ArrayList<>();
        JsonNode actions = row.definition().path("actions");
        if (actions.isArray()) {
            for (JsonNode action : actions) {
                String id = action.path("deviceId").asText("");
                if (id.isBlank()) continue;
                devices.add(new DeviceRow(id, action.path("kind").asText(""),
                        action.path("deviceName").asText(action.path("name").asText(id)),
                        action.path("location").asText(""), action.path("command"),
                        outcomeOf(row, id), detailsOf(row, id)));
            }
        }
        JsonNode read = row.definition().path("read").path("devices");
        if (read.isArray()) {
            JsonNode ac = row.definition().path("read").path("ac");
            boolean adjusting = ac.isObject() && "adjust_to_26".equals(ac.path("mode").asText());
            for (JsonNode device : read) {
                String id = device.path("id").asText("");
                if (id.isBlank()) continue;
                ObjectNode target = mapper.createObjectNode();
                if (adjusting) target.put("temperature", acTarget(ac));
                else {
                    target.put("observe", true);
                    if ("safety".equalsIgnoreCase(device.path("kind").asText())) {
                        JsonNode alertWindow = row.definition().path("read").path("alertWindow");
                        JsonNode alertDays = row.definition().path("read").path("alertDays");
                        if (alertWindow.isTextual()) target.set("alertWindow", alertWindow.deepCopy());
                        if (alertDays.isIntegralNumber()) target.set("alertDays", alertDays.deepCopy());
                    }
                }
                devices.add(new DeviceRow(id, device.path("kind").asText(""),
                        device.path("name").asText(device.path("deviceName").asText(id)),
                        device.path("location").asText(""), target,
                        outcomeOf(row, id), detailsOf(row, id)));
            }
        }
        return devices;
    }

    /** One device row as persisted projection input. */
    public record DeviceRow(String id, String kind, String name, String location,
                            JsonNode target, String outcome, Map<String, Object> details) { }

    private String outcomeOf(PhaseRow row, String deviceId) {
        JsonNode executed = row.result().path("devices").path(deviceId);
        String outcome = executed.path("outcome").asText("");
        return outcome.isBlank() ? "pending" : outcome;
    }

    private Map<String, Object> detailsOf(PhaseRow row, String deviceId) {
        JsonNode executed = row.result().path("devices").path(deviceId);
        if (!executed.isObject()) return Map.of();
        Map<String, Object> details = new LinkedHashMap<>();
        executed.fields().forEachRemaining(entry -> {
            if (!"controlBinding".equals(entry.getKey())) {
                details.put(entry.getKey(), publicValue(entry.getValue()));
            }
        });
        return details;
    }

    private JsonNode firstAgentRead(List<PhaseRow> rows) {
        for (PhaseRow row : rows) {
            JsonNode agent = row.definition().path("read").path("agent");
            if (agent.isObject()) return agent;
        }
        return null;
    }

    // --------------------------------------------------------------- report

    private Map<String, Object> report(TaskSource task, List<PhaseRow> rows, List<DeviceRow> visible,
                                       TaskReadScope scope, boolean creator) {
        if (immediateDraft(task.payload())) return null;
        // A settled checkpoint is not a settled interval task. The scanner owns
        // the final task transition once the window closes.
        if (!TERMINAL_PHASE.contains(task.status().toUpperCase(Locale.ROOT))) return null;
        boolean allTerminal = rows.stream()
                .allMatch(row -> TERMINAL_PHASE.contains(row.status().toUpperCase(Locale.ROOT)));
        if (!allTerminal) return null;
        Map<String, Object> report = new LinkedHashMap<>();
        Instant generatedAt = rows.stream().map(PhaseRow::endedAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(task.updatedAt());
        if (task.updatedAt() != null && (generatedAt == null || task.updatedAt().isAfter(generatedAt))) {
            generatedAt = task.updatedAt();
        }
        if (generatedAt == null) generatedAt = Instant.now();
        report.put("generatedAt", generatedAt.toString());
        report.put("outcome", lowerStatus(task.status()));
        JsonNode interval = interval(task.payload());
        if (interval != null) report.put("interval", publicValue(interval));
        List<Map<String, Object>> phaseReports = new ArrayList<>();
        for (PhaseRow row : rows) {
            Map<String, Object> phaseReport = new LinkedHashMap<>();
            phaseReport.put("id", row.phaseId());
            phaseReport.put("at", row.at().toString());
            phaseReport.put("status", lowerStatus(row.status()));
            phaseReport.put("failureReason", row.failureReason());
            if (row.result().path("timings").isObject()) {
                phaseReport.put("timings", publicValue(row.result().path("timings")));
            }
            if (row.result().path("observations").isArray()) {
                phaseReport.put("observations", visibleObservations(
                        (ArrayNode) row.result().path("observations"), row, scope, creator));
            }
            List<Map<String, Object>> devices = new ArrayList<>();
            for (DeviceRow device : deviceRows(row)) {
                if (scope.device(device.id()).isEmpty()) continue;
                Map<String, Object> deviceView = new LinkedHashMap<>();
                deviceView.put("id", device.id());
                deviceView.put("kind", device.kind());
                deviceView.put("name", device.name());
                deviceView.put("location", device.location());
                deviceView.put("target", device.target() == null ? Map.of() : publicValue(device.target()));
                deviceView.put("outcome", device.outcome());
                deviceView.put("details", device.details());
                devices.add(deviceView);
            }
            phaseReport.put("devices", devices);
            phaseReports.add(phaseReport);
        }
        report.put("phases", phaseReports);
        report.put("summary", summarize(report));
        return report;
    }

    /** Runtime detail report observation visibility per block type. */
    private List<Object> visibleObservations(ArrayNode blocks, PhaseRow row, TaskReadScope scope, boolean creator) {
        List<Object> visible = new ArrayList<>();
        List<DeviceRow> devices = deviceRows(row);
        boolean allEnergy = !devices.isEmpty() && devices.stream()
                .allMatch(device -> "energy".equalsIgnoreCase(device.kind()));
        for (JsonNode block : blocks) {
            String type = block.path("type").asText("");
            switch (type) {
                case "alerts" -> {
                    if (devices.stream().allMatch(device -> scope.device(device.id()).isPresent())) {
                        visible.add(publicValue(block));
                    }
                }
                case "environment_history" -> {
                    if (scope.deviceIds().stream().anyMatch(id ->
                            scope.device(id).map(device -> "environment".equalsIgnoreCase(device.kind())).orElse(false))) {
                        visible.add(publicValue(block));
                    }
                }
                case "company_energy", "energy_report" -> {
                    boolean keep = true;
                    for (JsonNode meter : block.path("report").path("meters")) {
                        String meterId = meter.path("meter").path("id").asText("");
                        if (!"energy".equalsIgnoreCase(scope.device(meterId)
                                .map(TaskReadScope.DeviceView::kind).orElse(""))) keep = false;
                    }
                    if (keep) visible.add(publicValue(block));
                }
                case "meter_report", "report_artifact" -> {
                    if (creator && allEnergy
                            && devices.stream().allMatch(device -> scope.device(device.id()).isPresent())) {
                        visible.add(publicValue(block));
                    }
                }
                case "energy_history", "energy_comparison" -> {
                    String meterId = block.path("report").path("meter").path("id").asText("");
                    if ("energy".equalsIgnoreCase(scope.device(meterId)
                            .map(TaskReadScope.DeviceView::kind).orElse(""))) {
                        visible.add(publicValue(block));
                    }
                }
                case "video_playback" -> {
                    String deviceId = block.path("report").path("deviceId").asText("");
                    if ("video".equalsIgnoreCase(scope.device(deviceId)
                            .map(TaskReadScope.DeviceView::kind).orElse(""))) {
                        visible.add(publicValue(block));
                    }
                }
                default -> {
                    List<Object> kept = new ArrayList<>();
                    for (JsonNode device : block.path("devices")) {
                        if (scope.device(device.path("deviceId").asText("")).isPresent()) {
                            kept.add(publicValue(device));
                        }
                    }
                    if (!kept.isEmpty()) {
                        Map<String, Object> trimmed = new LinkedHashMap<>();
                        block.fields().forEachRemaining(entry -> trimmed.put(entry.getKey(), publicValue(entry.getValue())));
                        trimmed.put("devices", kept);
                        visible.add(trimmed);
                    }
                }
            }
        }
        return visible;
    }

    // ------------------------------------------------------------- summary

    /**
     * Runtime report-summary.ts summarizeTaskReport: checkpoint/device/energy
     * projection. The aggregation rules live in
     * {@link ltd.qianc.iotagent.task.internal.TaskReportSummaries}; callers of
     * this projection never need to know its null-value or field conventions.
     */
    public Map<String, Object> summarize(Map<String, Object> report) {
        return ltd.qianc.iotagent.task.internal.TaskReportSummaries.summarize(report);
    }

    // ------------------------------------------------- virtual occurrences

    /**
     * Runtime plans.ts virtualTask: the projection of one not-yet-materialized plan
     * occurrence. Returns null when the occurrence is not visible/virtual.
     */
    public Map<String, Object> virtualOccurrence(PlanSource plan, LocalDate date, TaskReadScope scope,
                                                 String viewerKey, boolean inspectDisabled) {
        JsonNode payload = plan.payload();
        if ((!inspectDisabled && (!"ENABLED".equalsIgnoreCase(plan.status()) || plan.nextDate() == null))
                || date == null) return null;
        JsonNode effective = effectiveTemplate(plan, date);
        if (effective == null) return null;
        Instant effectiveAnchor = anchor(effective);
        if (effectiveAnchor == null) return null;
        if (!inspectDisabled && plan.nextAt() != null && effectiveAnchor.isBefore(plan.nextAt())) return null;
        String id = ltd.qianc.iotagent.task.TaskRecurrence.occurrenceId(plan.planId(), date);
        List<Map<String, Object>> phaseViews = new ArrayList<>();
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        boolean creator = viewerKey.equals(text(payload, "ownerKey"));
        for (JsonNode phase : effective.path("phases")) {
            JsonNode read = phase.path("read");
            boolean phaseReadVisible = creator
                    && (!read.path("agent").isObject() || allKind(read.path("devices"), scope, "energy"))
                    && (!read.path("ac").isObject() || allKind(read.path("devices"), scope, "ac"));
            List<Map<String, Object>> devices = new ArrayList<>();
            JsonNode actions = phase.path("actions");
            if (actions.isArray()) {
                for (JsonNode action : actions) {
                    String deviceId = action.path("deviceId").asText("");
                    var device = scope.device(deviceId);
                    if (device.isEmpty() || device.get().observationOnly()
                            || !device.get().kind().equalsIgnoreCase(action.path("kind").asText())) continue;
                    Map<String, Object> deviceView = new LinkedHashMap<>();
                    deviceView.put("id", deviceId);
                    deviceView.put("kind", action.path("kind").asText());
                    deviceView.put("name", action.path("deviceName").asText(action.path("name").asText(deviceId)));
                    deviceView.put("location", action.path("location").asText(""));
                    deviceView.put("target", publicValue(action.path("command")));
                    deviceView.put("outcome", "pending");
                    deviceView.put("details", Map.of());
                    devices.add(deviceView);
                    latest.computeIfAbsent(deviceId, key -> {
                        Map<String, Object> latestView = new LinkedHashMap<>();
                        latestView.put("id", deviceId);
                        latestView.put("kind", action.path("kind").asText());
                        latestView.put("name", deviceView.get("name"));
                        latestView.put("outcome", "pending");
                        return latestView;
                    });
                }
            }
            if (read.path("devices").isArray()) {
                JsonNode ac = read.path("ac");
                boolean adjusting = ac.isObject() && "adjust_to_26".equals(ac.path("mode").asText());
                for (JsonNode device : read.path("devices")) {
                    String deviceId = device.path("id").asText("");
                    var authorized = scope.device(deviceId);
                    if (authorized.isEmpty() || !authorized.get().kind().equalsIgnoreCase(device.path("kind").asText())) continue;
                    ObjectNode target = mapper.createObjectNode();
                    if (adjusting) target.put("temperature", acTarget(ac));
                    else target.put("observe", true);
                    Map<String, Object> deviceView = new LinkedHashMap<>();
                    deviceView.put("id", deviceId);
                    deviceView.put("kind", device.path("kind").asText());
                    deviceView.put("name", device.path("name").asText(deviceId));
                    deviceView.put("location", device.path("location").asText(""));
                    deviceView.put("target", publicValue(target));
                    deviceView.put("outcome", "pending");
                    deviceView.put("details", Map.of());
                    devices.add(deviceView);
                    Map<String, Object> latestView = latest.computeIfAbsent(deviceId, key -> {
                        Map<String, Object> created = new LinkedHashMap<>();
                        created.put("id", deviceId);
                        created.put("kind", device.path("kind").asText());
                        created.put("name", deviceView.get("name"));
                        created.put("outcome", "pending");
                        return created;
                    });
                    latestView.put("kind", device.path("kind").asText());
                    latestView.put("name", deviceView.get("name"));
                }
            }
            if (devices.isEmpty()) continue;
            Map<String, Object> phaseView = new LinkedHashMap<>();
            phaseView.put("id", id + ":" + phaseViews.size());
            phaseView.put("at", phase.path("at").asText());
            phaseView.put("status", "scheduled");
            phaseView.put("startedAt", null);
            phaseView.put("endedAt", null);
            phaseView.put("failureReason", null);
            if (phaseReadVisible && read.isObject()
                    && (read.path("agent").isObject() || read.path("ac").isObject())) {
                Map<String, Object> readView = new LinkedHashMap<>();
                if (read.path("agent").isObject()) readView.put("agent", publicValue(read.path("agent")));
                if (read.path("ac").isObject()) readView.put("ac", publicValue(read.path("ac")));
                phaseView.put("read", readView);
            }
            phaseView.put("devices", devices);
            phaseViews.add(phaseView);
        }
        if (latest.isEmpty()) return null;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id);
        putIfValue(view, "agentSource", text(payload, "source"));
        String label = firstText(effective, "label", "title", "objective");
        view.put("label", label.isEmpty() ? firstText(payload, "label", "title", "objective") : label);
        view.put("status", "ENABLED".equalsIgnoreCase(plan.status()) ? "scheduled" : lowerStatus(plan.status()));
        view.put("version", plan.version());
        view.put("interval", interval(effective));
        if (payload.path("repeat").isObject()) view.put("repeat", publicValue(payload.path("repeat")));
        view.put("occurrence", Map.of("planId", plan.planId(), "date", date.toString(), "virtual", true));
        putIfValue(view, "nextAt", plan.nextAt() == null ? null : plan.nextAt().toString());
        view.put("executionActive", false);
        view.put("report", null);
        view.put("createdAt", firstText(payload, "createdAt", "normalizedAt"));
        view.put("updatedAt", firstText(payload, "createdAt", "normalizedAt"));
        view.put("regionIds", regionIds(effective, scope));
        view.put("phases", phaseViews);
        view.put("devices", List.copyOf(latest.values()));
        view.put("creator", Map.of("displayName", textOr(payload, "creatorName", "")));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "recurring_plan");
        source.put("draftId", null);
        source.put("conversationId", creator ? text(payload, "conversationId") : null);
        view.put("source", source);
        view.put("canCancel", false);
        return view;
    }

    /** Effective template for a date: override payload, else the anchor-shifted template. */
    public JsonNode effectiveTemplate(PlanSource plan, LocalDate date) {
        OccurrenceState state = plan.occurrences().get(date);
        if (state != null && "SKIPPED".equalsIgnoreCase(state.status())) return null;
        if (state != null && state.overridePayload() != null && state.overridePayload().isObject()) {
            return state.overridePayload();
        }
        Instant anchor = anchor(plan.payload());
        if (anchor == null) return null;
        return shift(plan.payload(), date, ltd.qianc.iotagent.task.TaskRecurrence.localDate(anchor));
    }

    public JsonNode shift(JsonNode original, LocalDate date, LocalDate anchorDate) {
        JsonNode copy = original.deepCopy();
        if (!(copy instanceof ObjectNode root)) return null;
        long days = java.time.temporal.ChronoUnit.DAYS.between(anchorDate, date);
        JsonNode interval = root.get("interval");
        if (interval instanceof ObjectNode mutable) {
            mutable.put("startsAt", shiftAt(mutable.path("startsAt").asText(""), days));
            mutable.put("endsAt", shiftAt(mutable.path("endsAt").asText(""), days));
        }
        if (!(root.get("phases") instanceof ArrayNode phases)) return null;
        for (JsonNode phase : phases) {
            if (phase instanceof ObjectNode mutable) {
                mutable.put("at", shiftAt(mutable.path("at").asText(""), days));
            }
        }
        return root;
    }

    private String shiftAt(String value, long days) {
        try { return Instant.parse(value).plus(java.time.Duration.ofDays(days)).toString(); }
        catch (RuntimeException invalid) { return value; }
    }

    private boolean allKind(JsonNode devices, TaskReadScope scope, String kind) {
        if (!devices.isArray() || devices.isEmpty()) return false;
        for (JsonNode device : devices) {
            String expected = scope.device(device.path("id").asText(""))
                    .map(TaskReadScope.DeviceView::kind).orElse("");
            if (!kind.equalsIgnoreCase(expected)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------ plans

    /** Runtime plans.ts describe: the plan view consumed by plan lists and details. */
    public Map<String, Object> describe(PlanSource plan, TaskReadScope scope, String viewerKey,
                                        Map<String, Object> latestTask) {
        JsonNode payload = plan.payload();
        LocalDate previewDate = plan.nextDate() != null ? plan.nextDate()
                : ltd.qianc.iotagent.task.TaskRecurrence.localDate(anchor(payload));
        Map<String, Object> preview = virtualOccurrence(plan, previewDate, scope, viewerKey, true);
        if (preview == null) return null;
        boolean enabled = "ENABLED".equalsIgnoreCase(plan.status());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("planId", plan.planId());
        putIfValue(view, "agentSource", text(payload, "source"));
        view.put("status", lowerStatus(plan.status()));
        view.put("version", plan.version());
        view.put("updatedAt", plan.updatedAt() == null ? "" : plan.updatedAt().toString());
        putIfValue(view, "pauseReason", text(payload, "pauseReason") != null ? text(payload, "pauseReason")
                : "PAUSED".equalsIgnoreCase(plan.status()) ? "用户手动停用" : null);
        Instant anchor = anchor(payload);
        LocalDate anchorDate = anchor == null ? null
                : ltd.qianc.iotagent.task.TaskRecurrence.localDate(anchor);
        view.put("anchorDate", anchorDate == null ? "" : anchorDate.toString());
        view.put("label", firstText(payload, "label", "title", "objective"));
        view.put("repeat", publicValue(payload.path("repeat")));
        view.put("nextAt", enabled && plan.nextAt() != null ? plan.nextAt().toString() : null);
        view.put("nextCheckpointAt", enabled ? nextCheckpointAt(plan, previewDate) : null);
        view.put("nextDate", enabled && plan.nextDate() != null ? plan.nextDate().toString() : null);
        view.put("nextOccurrenceId", enabled && plan.nextDate() != null
                ? ltd.qianc.iotagent.task.TaskRecurrence.occurrenceId(plan.planId(), plan.nextDate()) : null);
        view.put("regionIds", preview.get("regionIds"));
        view.put("phases", preview.get("phases"));
        view.put("interval", preview.get("interval"));
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("interval", preview.get("interval"));
        template.put("phases", preview.get("phases"));
        template.put("label", view.get("label"));
        template.put("regionIds", preview.get("regionIds"));
        template.put("repeat", view.get("repeat"));
        view.put("template", template);
        view.put("creator", preview.get("creator"));
        view.put("source", preview.get("source"));
        view.put("createdAt", preview.get("createdAt"));
        boolean allVisible = allTargetsVisible(plan, scope);
        boolean owner = viewerKey.equals(text(payload, "ownerKey"));
        view.put("canManage", owner && allVisible && !"DELETED".equalsIgnoreCase(plan.status()));
        view.put("canPause", owner && allVisible && "ENABLED".equalsIgnoreCase(plan.status()));
        view.put("canResume", owner && allVisible && "PAUSED".equalsIgnoreCase(plan.status()));
        view.put("latestTask", latestTask);
        return view;
    }

    public String nextCheckpointAt(PlanSource plan, LocalDate date) {
        if (plan.nextAt() == null) return null;
        JsonNode effective = effectiveTemplate(plan, date);
        if (effective == null) return plan.nextAt().toString();
        for (JsonNode phase : effective.path("phases")) {
            try {
                Instant at = Instant.parse(phase.path("at").asText());
                if (!at.isBefore(plan.nextAt())) return at.toString();
            } catch (RuntimeException ignored) {
                return plan.nextAt().toString();
            }
        }
        return plan.nextAt().toString();
    }

    /** Runtime nextCheckpointFor: the first checkpoint of the next occurrence, else its start. */
    public String nextCheckpointAt(PlanSource plan) {
        if (plan.nextAt() == null) return null;
        LocalDate date = plan.nextDate();
        if (date == null) {
            Instant anchor = anchor(plan.payload());
            date = anchor == null ? null : ltd.qianc.iotagent.task.TaskRecurrence.localDate(anchor);
        }
        return date == null ? plan.nextAt().toString() : nextCheckpointAt(plan, date);
    }

    /** Runtime validateResume visibility core across base and future overridden templates. */
    public boolean allTargetsVisible(PlanSource plan, TaskReadScope scope) {
        List<JsonNode> templates = new ArrayList<>();
        templates.add(plan.payload());
        for (OccurrenceState state : plan.occurrences().values()) {
            if ("OVERRIDDEN".equalsIgnoreCase(state.status()) && state.overridePayload() != null) {
                templates.add(state.overridePayload());
            }
        }
        boolean any = false;
        for (JsonNode template : templates) {
            for (JsonNode phase : template.path("phases")) {
                JsonNode actions = phase.path("actions");
                if (actions.isArray()) {
                    for (JsonNode action : actions) {
                        String deviceId = action.path("deviceId").asText("");
                        var device = scope.device(deviceId);
                        if (device.isEmpty() || device.get().observationOnly()
                                || !device.get().kind().equalsIgnoreCase(action.path("kind").asText(""))) {
                            return false;
                        }
                        any = true;
                    }
                }
                JsonNode read = phase.path("read").path("devices");
                if (read.isArray()) {
                    for (JsonNode deviceNode : read) {
                        var device = scope.device(deviceNode.path("id").asText(""));
                        if (!device.map(found -> found.kind().equalsIgnoreCase(deviceNode.path("kind").asText(""))).orElse(false)) {
                            return false;
                        }
                        any = true;
                    }
                }
            }
        }
        return any;
    }

    // ------------------------------------------------------------ helpers

    public static boolean canCancel(TaskSource task, List<PhaseRow> rows, List<DeviceRow> devices, TaskReadScope scope) {
        String status = task.status() == null ? "" : task.status().toUpperCase(Locale.ROOT);
        boolean pendingWindow = interval(task.payload()) != null
                || rows.stream().anyMatch(row -> "PENDING".equalsIgnoreCase(row.status()));
        return ("SCHEDULED".equals(status) || "EXECUTING".equals(status))
                && pendingWindow && !devices.isEmpty()
                && devices.stream().allMatch(device -> scope.device(device.id()).isPresent());
    }

    public static boolean abnormal(Map<String, Object> row) {
        return exceptionalStatus(String.valueOf(row.get("status")));
    }

    public static boolean exceptionalStatus(String status) {
        return status != null && EXCEPTIONAL_STATUSES.contains(status.toLowerCase(Locale.ROOT));
    }

    private Map<String, Object> sourceView(JsonNode payload, boolean creator) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", sourceTypeOf(payload));
        source.put("draftId", creator ? text(payload, "sourceDraftId") : null);
        source.put("conversationId", creator
                ? (text(payload, "conversationId") != null ? text(payload, "conversationId")
                        : text(payload, "sourceConversationId")) : null);
        return source;
    }

    /** Runtime source_type: recurring_plan for materialized occurrences, else the stored type. */
    public static String sourceTypeOf(JsonNode payload) {
        if (text(payload, "sourcePlanId") != null) return "recurring_plan";
        String stored = text(payload, "sourceType");
        return stored != null ? stored.toLowerCase(Locale.ROOT) : "scheduled";
    }

    /** Immediate draft executions keep phases and device rows but never build a report. */
    public static boolean immediateDraft(JsonNode payload) {
        return "immediate_draft".equalsIgnoreCase(text(payload, "sourceType"));
    }

    private List<String> regionIds(JsonNode payload, TaskReadScope scope) {
        List<String> regions = new ArrayList<>();
        if (payload.path("regionIds").isArray()) {
            for (JsonNode region : payload.path("regionIds")) {
                if (region.isTextual() && scope.regionIds().contains(region.asText())) {
                    regions.add(region.asText());
                }
            }
        }
        return regions;
    }

    public static JsonNode interval(JsonNode payload) {
        JsonNode interval = payload.path("interval");
        return interval.isObject() && interval.hasNonNull("startsAt") && interval.hasNonNull("endsAt")
                ? interval : null;
    }

    public Instant anchor(JsonNode payload) {
        String value = payload.path("interval").path("startsAt").asText("");
        if (value.isBlank()) value = payload.path("phases").path(0).path("at").asText("");
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { return null; }
    }

    private String createdAt(TaskSource task) {
        String value = firstText(task.payload(), "createdAt", "normalizedAt");
        if (!value.isEmpty()) return value;
        return task.createdAt() == null ? "" : task.createdAt().toString();
    }

    private String updatedAt(TaskSource task) {
        String value = firstText(task.payload(), "updatedAt");
        if (!value.isEmpty()) return value;
        if (task.updatedAt() != null) return task.updatedAt().toString();
        return createdAt(task);
    }

    public static String lowerStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
    }

    private double acTarget(JsonNode ac) {
        JsonNode action = ac.path("action").path("setpoint");
        if (action.isNumber()) return action.asDouble();
        JsonNode condition = ac.path("condition").path("setpoint").path("value");
        if (condition.isNumber()) return condition.asDouble();
        JsonNode target = ac.path("target");
        if (target.isNumber()) return target.asDouble();
        return 26;
    }

    private Object publicValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        JsonNode safe = ltd.qianc.iotagent.task.TaskPayloadSafety.withoutCredentials(mapper, value);
        return mapper.convertValue(safe, Object.class);
    }

    private Object value(JsonNode value) {
        return value == null || value.isNull() ? null : mapper.convertValue(value, Object.class);
    }

    private static String text(JsonNode payload, String field) {
        JsonNode value = payload.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String textOr(JsonNode payload, String field, String fallback) {
        String value = text(payload, field);
        return value == null ? fallback : value;
    }

    private static String firstText(JsonNode payload, String... fields) {
        for (String field : fields) {
            String value = text(payload, field);
            if (value != null) return value;
        }
        return "";
    }

    private static void putIfValue(Map<String, Object> view, String key, String value) {
        if (value != null) view.put(key, value);
    }
}
