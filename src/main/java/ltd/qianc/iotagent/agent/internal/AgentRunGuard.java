package ltd.qianc.iotagent.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import ltd.qianc.iotagent.error.BusinessException;

/** Run-scoped domain decisions; the generic engine never classifies IoT tools or business results. */
final class AgentRunGuard {
    private static final long RETENTION_NANOS = Duration.ofMinutes(15).toNanos();
    private final Map<String, Run> runs = new HashMap<>();
    private final ObjectMapper mapper;

    AgentRunGuard(ObjectMapper mapper) { this.mapper = mapper; }

    Map<String, Object> invoke(String contextId, String callId, AgentToolCatalog.Definition tool,
            Map<String, Object> arguments, Supplier<Map<String, Object>> execute) {
        Run run = run(contextId);
        Map<String, Object> stable = new LinkedHashMap<>(arguments);
        stable.remove("idempotencyKey");
        String fingerprint = tool.name() + ":" + json(stable, false);
        synchronized (run) {
            Invocation prior = run.calls.get(callId);
            if (prior != null) {
                if (!prior.fingerprint.equals(fingerprint)) throw failure("AGENT_TOOL_CALL_REUSED", "工具调用身份已用于不同参数");
                if (prior.error != null) throw prior.error;
                return prior.result;
            }
            if (run.finalized) throw failure("AGENT_RUN_FINALIZING", "已有确定提案，未执行新的调用；请根据原结果收尾，仍需用户在卡片确认");
            if (run.awaitingCameraSelection
                    && ("prepare_video_playback".equals(tool.name()) || "review_safety_event".equals(tool.name()))) {
                throw new ltd.qianc.iotagent.agent.AgentToolHaltException("CAMERA_SELECTION_REQUIRED",
                        "已有多个监控候选，请先由用户选择，不能自行挑选或逐台生成录像入口");
            }
            if (run.failures.getOrDefault(fingerprint, 0) >= 2) {
                throw failure("AGENT_NO_PROGRESS", "同一调用连续失败且没有新证据；请修正参数或使用其他合法取证，不重复原调用，不缩小用户范围绕过故障");
            }
            boolean read = tool.confirmation() == AgentToolCatalog.Confirmation.NONE && !"create_energy_report".equals(tool.name());
            if (!read && run.successes.containsKey(fingerprint)) {
                throw failure("AGENT_NO_PROGRESS", "相同提案或报告已经准备，请保留原对象，不重复生成或执行");
            }
            try {
                Map<String, Object> result = execute.get();
                if (result == null) throw failure("AGENT_TOOL_RESULT_UNAVAILABLE", "工具没有返回有效业务结果");
                if ("find_device_candidates".equals(tool.name()) && "video".equals(arguments.get("kind"))
                        && result.get("totalMatches") instanceof Number total && total.intValue() > 1
                        || "review_safety_event".equals(tool.name()) && result.get("candidates") instanceof java.util.List<?> candidates
                        && candidates.size() > 1 && !result.containsKey("videoPlayback")) {
                    run.awaitingCameraSelection = true;
                }
                if (read) {
                    String evidenceKey = tool.name() + ":" + json(stable, true);
                    Object evidence = canonical(result, true);
                    Object previous = run.readEvidence.put(evidenceKey, evidence);
                    if (!java.util.Objects.equals(previous, evidence)) run.failures.clear();
                } else {
                    run.successes.put(fingerprint, 1);
                    run.failures.remove(fingerprint);
                }
                run.finalized = AgentToolCatalogSupport.haltsRun(tool.name(), result);
                run.calls.put(callId, new Invocation(fingerprint, result, null));
                return result;
            } catch (BusinessException error) {
                run.failures.merge(fingerprint, 1, Integer::sum);
                run.calls.put(callId, new Invocation(fingerprint, null, error));
                throw error;
            }
        }
    }


    private static boolean transientEvidenceField(String key) {
        return "observedAt".equals(key) || "candidateSetId".equals(key) || "candidateSetIds".equals(key);
    }

    private synchronized Run run(String id) {
        long now = System.nanoTime();
        Run existing = runs.get(id);
        if (existing != null) return existing;
        runs.entrySet().removeIf(entry -> now - entry.getValue().created > RETENTION_NANOS);
        if (runs.size() >= 1024) throw failure("AGENT_RUN_CAPACITY", "在途智能体请求过多，请稍后恢复读取");
        Run created = new Run(now);
        runs.put(id, created);
        return created;
    }

    private String json(Object value, boolean evidence) {
        try { return mapper.writeValueAsString(canonical(value, evidence)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw failure("INVALID_AGENT_TOOL_ARGUMENTS", "工具参数无法规范化");
        }
    }

    private static Object canonical(Object value, boolean evidence) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> {
                String field = String.valueOf(key);
                if (!evidence || !transientEvidenceField(field)) sorted.put(field, canonical(item, evidence));
            });
            return sorted;
        }
        if (value instanceof Iterable<?> list) {
            var result = new ArrayList<>();
            list.forEach(item -> result.add(canonical(item, evidence)));
            return result;
        }
        return value;
    }

    private static BusinessException failure(String code, String message) {
        return "AGENT_RUN_FINALIZING".equals(code)
                ? new ltd.qianc.iotagent.agent.AgentToolHaltException(code, message)
                : new BusinessException(code, message, 409);
    }
    private record Invocation(String fingerprint, Map<String, Object> result, BusinessException error) { }
    private static final class Run {
        final long created;
        boolean finalized;
        boolean awaitingCameraSelection;
        final Map<String, Integer> failures = new HashMap<>();
        final Map<String, Integer> successes = new HashMap<>();
        final Map<String, Object> readEvidence = new HashMap<>();
        final Map<String, Invocation> calls = new HashMap<>();
        Run(long created) { this.created = created; }
    }
}
