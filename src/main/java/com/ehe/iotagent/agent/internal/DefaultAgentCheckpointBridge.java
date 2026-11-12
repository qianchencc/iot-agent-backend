package com.ehe.iotagent.agent.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.ehe.iotagent.admin.AdminConfigurationStore;
import com.ehe.iotagent.agent.AgentCheckpointBridge;
import com.ehe.iotagent.instructions.AgentInstructions;
import com.ehe.iotagent.agent.AgentToolHaltException;
import com.ehe.iotagent.error.BusinessException;
import com.ehe.iotagent.iot.IotSourceEnum;
import com.ehe.iotagent.iot.ObservationAdapter;
import com.ehe.iotagent.iot.ObservationOperationEnum;
import com.ehe.iotagent.iot.ObservationRequest;
import com.ehe.iotagent.iot.ObservationStatusEnum;
import com.ehe.iotagent.iot.RequestIdentity;
import com.ehe.iotagent.pisession.PiSessionService;
import com.ehe.iotagent.report.ReportEvidencePort;
import com.ehe.iotagent.report.ReportFact;
import com.ehe.iotagent.report.ReportFactStore;
import com.ehe.iotagent.task.TaskExecutionGateway.TaskExecutionRequest;
import com.ehe.iotagent.task.TaskExecutionGateway.TaskExecutionResult;

/** Bounded task checkpoint runner. Business tools, fixed scope, facts and submission stay in Java. */
public final class DefaultAgentCheckpointBridge implements AgentCheckpointBridge {
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };
    private final ObjectMapper mapper;
    private final ObservationAdapter observations;
    private final ReportEvidencePort evidence;
    private final ReportFactStore reports;
    private final Supplier<AdminConfigurationStore> configurations;
    private final Clock clock;
    private final String baseUrl;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<Map<String, Object>> tools;
    private final Map<String, Checkpoint> running = new ConcurrentHashMap<>();

    public DefaultAgentCheckpointBridge(ObjectMapper mapper, ObservationAdapter observations,
            ReportEvidencePort evidence, ReportFactStore reports, Supplier<AdminConfigurationStore> configurations,
            String baseUrl, String token, Clock clock) {
        this.mapper = mapper;
        this.observations = observations;
        this.evidence = evidence;
        this.reports = reports;
        this.configurations = configurations;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.clock = clock;
        try (var input = getClass().getResourceAsStream("/agent/checkpoint-tools.json")) {
            if (input == null) throw new IllegalStateException("Missing checkpoint tool definitions");
            this.tools = mapper.readValue(input, new TypeReference<>() { });
        } catch (java.io.IOException error) { throw new IllegalStateException("Invalid checkpoint tool definitions", error); }
    }

    @Override
    public TaskExecutionResult execute(RequestIdentity identity, TaskExecutionRequest request,
            List<String> meterIds, Map<String, Object> assignment, BooleanSupplier active) {
        if (identity.source() != IotSourceEnum.COMPANY || meterIds.isEmpty()) throw invalid("用电检查点缺少已确认的公司电表范围");
        String output = text(assignment, "output", 6);
        if (!Set.of("result", "report").contains(output)) throw invalid("检查点输出类型无效");
        if (!"report".equals(output) && (baseUrl.isBlank() || token == null || token.isBlank())) {
            throw unavailable("检查点 Agent Engine 未配置");
        }
        Checkpoint state = new Checkpoint(identity, request, List.copyOf(meterIds), assignment, active);
        state.checkActive();
        Map<String, Object> spec = object(assignment.get("spec"));
        if ("report".equals(output) && spec.isEmpty()) throw invalid("定时电表报告缺少明确的统计期间与比较要求");
        if (!spec.isEmpty()) {
            Map<String, Object> intent = new LinkedHashMap<>(spec);
            intent.put("kind", "energy");
            intent.put("scope", Map.of("level", "selection", "meterIds", state.meters));
            state.prepared = evidence.prepare(identity, intent, request.dueAt());
            for (Object item : list(state.prepared.get("blocks"))) {
                Map<String, Object> block = object(item);
                if ("meter_report".equals(block.get("type"))) state.facts.add(object(block.get("report")));
            }
            if (!state.hasData()) throw unavailable("所选统计期间没有有效电表采样；没有更改日期或生成空报告");
        }
        // 暂停电表定时报告的模型解读；直接保存按确认范围和期间准备的服务端统计事实。
        if ("report".equals(output)) {
            state.submittedReport = state.prepared;
            return state.result(true);
        }
        running.put(state.contextId, state);
        boolean finished = false;
        try {
            state.checkActive();
            AdminConfigurationStore configuration = configurations.get();
            Map<String, Object> configured = configuration == null ? Map.of() : configuration.active();
            Map<String, Object> settings = configured == null ? Map.of() : configured;
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("instructions", AgentInstructions.section("checkpointInstructions", settings));
            definition.put("finalizationInstructions", AgentInstructions.section("finalizationInstructions", settings));
            definition.put("compactionInstructions", AgentInstructions.section("compactionInstructions", settings));
            definition.put("maxTurns", 6);
            definition.put("maxToolCalls", 10);
            definition.put("runTimeoutMs", 180_000);
            definition.put("tools", tools.stream().filter(tool -> {
                String name = String.valueOf(tool.get("name"));
                return (state.prepared == null && Set.of("summarize_energy", "compare_energy").contains(name))
                        || ("report".equals(output) ? "publish_report" : "finish_checkpoint").equals(name);
            }).toList());
            Map<String, Object> model = AgentBusinessRunPolicyProvider.model(settings);
            if (!model.isEmpty()) definition.put("model", model);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("objective", text(assignment, "objective", 2000));
            content.put("checkpointTime", request.dueAt().toString());
            content.put("currentTime", clock.instant().toString());
            content.put("timezone", "Asia/Shanghai");
            content.put("output", output);
            if (state.prepared != null) content.put("preparedFacts", state.indexedFacts());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("protocolVersion", 1);
            body.put("runId", state.runId);
            body.put("conversationId", state.conversationId);
            body.put("turnId", state.turnId);
            body.put("toolContextId", state.contextId);
            body.put("sessionScope", state.scopeId());
            body.put("content", mapper.writeValueAsString(content));
            body.put("definition", definition);
            var outbound = HttpRequest.newBuilder(URI.create(baseUrl + "/run"))
                    .timeout(Duration.ofSeconds(180)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json").header("Accept", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var pending = client.sendAsync(outbound, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> response;
            try { response = pending.get(185, TimeUnit.SECONDS); }
            catch (Exception failure) { pending.cancel(true); throw failure; }
            if (response.statusCode() != 200) throw unavailable("检查点 Agent Engine 请求未完成");
            var lines = response.body().lines().iterator();
            while (lines.hasNext()) {
                String line = lines.next();
                if (line.isBlank()) continue;
                JsonNode event = mapper.readTree(line);
                if ("run.failed".equals(event.path("type").asText()) || "run.aborted".equals(event.path("type").asText())) {
                    finished = false;
                    break;
                }
                if ("run.completed".equals(event.path("type").asText())) finished = true;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // Only already-collected server facts may survive a failed interpretation.
            finished = false;
        } finally {
            running.remove(state.contextId, state);
            if (!finished) cancel(state.runId);
        }
        state.checkActive();
        return state.result(finished);
    }

    @Override public Optional<PiSessionService.Context> sessionContext(Map<String, Object> binding) {
        Checkpoint state = find(binding);
        return state == null ? Optional.empty() : Optional.of(new PiSessionService.Context(state.identity.tenantCode(),
                state.identity.ownerKey(), "company", state.conversationId, state.runId));
    }

    @Override public Optional<Map<String, Object>> executeTool(Map<String, Object> binding) {
        Checkpoint state = find(binding);
        if (state == null) return Optional.empty();
        synchronized (state) { return Optional.of(state.invoke(binding)); }
    }

    private Checkpoint find(Map<String, Object> binding) {
        Checkpoint state = running.get(String.valueOf(binding.get("toolContextId")));
        if (state == null) return null;
        if (!state.runId.equals(binding.get("runId")) || !state.turnId.equals(binding.get("turnId"))
                || !state.conversationId.equals(binding.get("conversationId"))) throw invalid("检查点调用身份不匹配");
        state.checkActive();
        return state;
    }

    private void cancel(String runId) {
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/cancel/" + runId)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException ignored) { /* The invocation context is already revoked. */ }
    }

    private final class Checkpoint {
        final String contextId = UUID.randomUUID().toString(), runId = UUID.randomUUID().toString(), turnId = UUID.randomUUID().toString();
        final RequestIdentity identity;
        final TaskExecutionRequest request;
        final List<String> meters;
        final Map<String, Object> assignment;
        final BooleanSupplier active;
        final String conversationId;
        final List<Map<String, Object>> facts = new ArrayList<>();
        final Set<String> called = new HashSet<>();
        final Map<String, Map<String, Object>> accepted = new HashMap<>();
        Map<String, Object> prepared, submittedReport;
        String submittedSummary;
        boolean readFailed;

        Checkpoint(RequestIdentity identity, TaskExecutionRequest request, List<String> meters,
                Map<String, Object> assignment, BooleanSupplier active) {
            this.identity = identity; this.request = request; this.meters = meters; this.assignment = assignment; this.active = active;
            this.conversationId = "task-" + request.taskId();
        }
        void checkActive() { if (!active.getAsBoolean()) throw new BusinessException("CHECKPOINT_CANCELLED", "检查点已停止，不再取数或保存报告", 409); }
        String scopeId() { return UUID.nameUUIDFromBytes((identity.tenantCode() + "\u0000" + identity.ownerKey()
                + "\u0000" + request.taskId() + "\u0000" + request.phaseId()).getBytes(StandardCharsets.UTF_8)).toString(); }
        boolean hasData() { return facts.stream().anyMatch(DefaultAgentCheckpointBridge::hasData); }
        List<Map<String, Object>> indexedFacts() {
            List<Map<String, Object>> indexed = new ArrayList<>();
            for (int n = 0; n < facts.size(); n++) { Map<String, Object> value = new LinkedHashMap<>(facts.get(n)); value.put("index", n + 1); indexed.add(value); }
            return indexed;
        }
        Map<String, Object> invoke(Map<String, Object> binding) {
            checkActive();
            String name = text(binding, "toolName", 100), callId = text(binding, "toolCallId", 300);
            Map<String, Object> arguments = object(binding.get("arguments"));
            String key = name + ":" + json(new TreeMap<>(arguments));
            if (accepted.containsKey(callId)) {
                Map<String, Object> saved = accepted.get(callId);
                if (!key.equals(saved.get("fingerprint"))) throw invalid("检查点调用标识已用于不同参数");
                return object(saved.get("result"));
            }
            if (submittedSummary != null || submittedReport != null) throw new AgentToolHaltException("CHECKPOINT_SUBMITTED", "检查点结果已提交");
            if (called.size() >= 10 || !called.add(key)) throw new BusinessException("CHECKPOINT_NO_PROGRESS", "相同调用已完成或已达到读取预算，请使用已有事实提交", 409);
            Map<String, Object> result;
            if (Set.of("summarize_energy", "compare_energy").contains(name)) {
                if (prepared != null) throw invalid("已有服务端准备的确认期间，不得替换统计范围或日期");
                result = query(name, arguments);
            } else {
                String expected = "report".equals(assignment.get("output")) ? "publish_report" : "finish_checkpoint";
                if (!expected.equals(name)) throw invalid("工具不属于本次已确认的输出类型");
                if (!hasData()) throw unavailable("本次检查点没有取得有效统计事实");
                String summary = text(arguments, "summary", 600);
                if ("publish_report".equals(name)) submittedReport = report(arguments, summary);
                else submittedSummary = summary;
                result = Map.of("status", "submitted");
            }
            accepted.put(callId, Map.of("fingerprint", key, "result", result));
            return result;
        }
        Map<String, Object> query(String name, Map<String, Object> args) {
            Instant from = instant(args.get("from")), to = instant(args.get("to"));
            if (!from.isBefore(to) || to.isAfter(clock.instant())) throw invalid("统计期间须已发生且结束晚于开始");
            Map<String, String> params = new LinkedHashMap<>();
            params.put("kind", "compare_energy".equals(name) ? "comparison" : "statistics");
            params.put("scopeLevel", "selection"); params.put("meterIds", String.join(",", meters));
            params.put("from", from.toString()); params.put("to", to.toString());
            if ("compare_energy".equals(name)) {
                params.put("axis", "time");
                Map<String, Object> baseline = object(args.get("baseline"));
                Instant baseFrom = baseline.isEmpty() ? from.minusMillis(to.toEpochMilli() - from.toEpochMilli()) : instant(baseline.get("from"));
                Instant baseTo = baseline.isEmpty() ? from : instant(baseline.get("to"));
                if (!baseFrom.isBefore(baseTo) || baseTo.isAfter(clock.instant())) throw invalid("比较基期无效");
                params.put("baseFrom", baseFrom.toString()); params.put("baseTo", baseTo.toString());
            }
            try {
                var response = observations.read(new ObservationRequest(identity, ObservationOperationEnum.COMPANY_METER_REPORT, params)).block(Duration.ofSeconds(45));
                checkActive();
                if (response == null || response.status() == ObservationStatusEnum.UNAVAILABLE) throw unavailable("检查点统计取证暂不可用");
                Map<String, Object> fact = new LinkedHashMap<>(response.payload());
                facts.add(fact);
                Map<String, Object> indexed = new LinkedHashMap<>(fact); indexed.put("index", facts.size());
                return indexed;
            } catch (RuntimeException failure) { readFailed = true; throw failure; }
        }
        Map<String, Object> report(Map<String, Object> args, String summary) {
            Map<String, Object> result = prepared == null ? new LinkedHashMap<>() : new LinkedHashMap<>(prepared);
            result.put("title", text(args, "title", 100));
            result.put("summary", prepared == null ? summary + "；报告未保存可校验的统计要求，内容完整性尚未核验" : prepared.get("summary"));
            List<Object> blocks = new ArrayList<>();
            if (prepared != null) blocks.addAll(list(prepared.get("blocks")));
            else for (Map<String, Object> fact : facts) if (DefaultAgentCheckpointBridge.hasData(fact)) blocks.add(Map.of("type", "meter_report", "report", fact));
            for (Object value : list(args.get("sections"))) {
                Map<String, Object> section = object(value);
                if ("text".equals(section.get("type"))) blocks.add(Map.of("type", "text", "title", String.valueOf(section.getOrDefault("title", "")), "text", text(section, "text", 6000)));
                else if (!"meter".equals(section.get("type")) || !(section.get("index") instanceof Number index)
                        || index.intValue() < 1 || index.intValue() > facts.size()) throw invalid("报告引用了未登记的统计事实");
            }
            blocks.add(Map.of("type", "text", "title", "模型解读", "text", summary));
            result.put("blocks", blocks);
            result.put("status", prepared == null || readFailed ? "partial" : prepared.get("status"));
            return result;
        }
        TaskExecutionResult result(boolean finished) {
            boolean submitted = submittedSummary != null || submittedReport != null;
            boolean incomplete = readFailed || facts.stream().anyMatch(DefaultAgentCheckpointBridge::incomplete)
                    || (prepared != null && !"completed".equals(prepared.get("status")))
                    || (prepared == null && "report".equals(assignment.get("output")));
            String outcome = !hasData() ? "FAILED" : !finished || !submitted || incomplete ? "PARTIAL" : "COMPLETED";
            String summary = submittedSummary != null ? submittedSummary : submittedReport != null ? String.valueOf(submittedReport.get("summary"))
                    : hasData() ? "统计事实已保留；智能解读未完成" : "检查点未取得有效统计事实";
            if (prepared != null) {
                summary = String.valueOf(prepared.get("summary"))
                        + (submittedSummary != null ? "；模型解读：" + submittedSummary : !submitted ? "；智能解读未完成" : "");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("outcome", outcome); result.put("dispatchStarted", false); result.put("executedAt", clock.instant().toString());
            result.put("summary", summary);
            List<Object> blocks = new ArrayList<>();
            for (Map<String, Object> fact : facts) blocks.add(Map.of("type", "meter_report", "report", fact));
            if ("report".equals(assignment.get("output")) && hasData()) {
                Map<String, Object> snapshot = submittedReport != null ? new LinkedHashMap<>(submittedReport)
                        : prepared != null ? new LinkedHashMap<>(prepared) : new LinkedHashMap<>(Map.of("title", "用电简报", "blocks", blocks));
                snapshot.put("status", "COMPLETED".equals(outcome) ? "completed" : "partial");
                snapshot.put("summary", summary);
                String id = "report-" + scopeId();
                snapshot.put("id", id); snapshot.put("source", "company"); snapshot.put("tenantCode", identity.tenantCode()); snapshot.put("ownerKey", identity.ownerKey());
                snapshot.put("generatedAt", clock.instant().toString()); snapshot.put("checkpointAt", request.dueAt().toString());
                snapshot.put("objective", assignment.get("objective"));
                snapshot.put("origin", Map.of("kind", "task", "taskId", request.taskId(), "phaseId", request.phaseId(), "at", request.dueAt().toString()));
                checkActive();
                reports.save(new ReportFact(id, "company", json(snapshot)));
                blocks.add(Map.of("type", "report_artifact", "report", Map.of("id", id, "title", snapshot.get("title"), "status", snapshot.get("status"))));
            }
            result.put("observations", blocks);
            return new TaskExecutionResult(outcome, json(result), false);
        }
    }

    private static boolean incomplete(Map<String, Object> fact) {
        Map<String, Object> coverage = object(fact.get("coverage"));
        return "partial".equals(fact.get("status")) || "not_comparable".equals(fact.get("status"))
                || Boolean.TRUE.equals(coverage.get("truncated"))
                || !list(coverage.get("partial")).isEmpty() || !list(coverage.get("noData")).isEmpty()
                || (coverage.get("meters") instanceof Number meters && coverage.get("dataMeters") instanceof Number data
                    && data.intValue() < meters.intValue());
    }
    private static boolean hasData(Map<String, Object> fact) {
        Object count = object(fact.get("coverage")).get("dataMeters");
        if (count instanceof Number n) return n.intValue() > 0;
        Object first = object(object(fact.get("sides")).get("a")).get("dataMeters");
        return first instanceof Number n && n.intValue() > 0;
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw invalid("检查点事实无法保存"); }
    }
    private static String text(Map<String, Object> value, String key, int max) {
        Object raw = value.get(key);
        if (!(raw instanceof String text) || text.isBlank() || text.length() > max) throw invalid("检查点参数无效：" + key);
        return text.trim();
    }
    private static Instant instant(Object value) {
        try { if (value instanceof String text) return Instant.parse(text); }
        catch (RuntimeException ignored) { }
        throw invalid("统计期间须为带时区的ISO时间");
    }
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> { if (key instanceof String text) result.put(text, item); });
        return result;
    }
    private static List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private static BusinessException invalid(String message) { return new BusinessException("INVALID_CHECKPOINT", message, 400); }
    private static BusinessException unavailable(String message) { return new BusinessException("CHECKPOINT_UNAVAILABLE", message, 503); }
}
