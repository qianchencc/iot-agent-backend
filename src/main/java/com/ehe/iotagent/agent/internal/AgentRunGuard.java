package com.ehe.iotagent.agent.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.Function;
import com.ehe.iotagent.iot.DeviceCandidateRecord;
import com.ehe.iotagent.error.BusinessException;

/** Run-scoped domain decisions; the generic engine never classifies IoT tools or business results. */
final class AgentRunGuard {
    private static final long RETENTION_NANOS = Duration.ofMinutes(15).toNanos();
    private static final java.util.regex.Pattern CANDIDATE_HANDLE = java.util.regex.Pattern.compile("^s\\d+$");
    private static final java.util.regex.Pattern DEVICE_HANDLE = java.util.regex.Pattern.compile("^d(\\d+)_\\d+$");
    private final Map<String, Run> runs = new HashMap<>();
    private final ObjectMapper mapper;

    AgentRunGuard(ObjectMapper mapper) { this.mapper = mapper; }

    Map<String, Object> invoke(String contextId, String callId, AgentToolCatalog.Definition tool,
            Map<String, Object> arguments, Function<String, DeviceCandidateRecord> resolveCandidate,
            Supplier<Map<String, Object>> execute) {
        Run run = run(contextId);
        Map<String, Object> stable = new LinkedHashMap<>(arguments);
        stable.remove("idempotencyKey");
        // Candidate-set and device handles are renewed per discovery; the fingerprint
        // follows the server-owned member evidence behind them so a refreshed alias
        // for the same members never counts as a new attempt. Unknown references
        // stay literal — a genuinely different scope must never collapse with this one.
        String callFingerprint = tool.name() + ":" + json(stable, false, null);
        synchronized (run) {
            Invocation prior = run.calls.get(callId);
            if (prior != null) {
                if (!prior.fingerprint.equals(callFingerprint)) throw failure("AGENT_TOOL_CALL_REUSED", "工具调用身份已用于不同参数");
                if (prior.error != null) throw prior.error;
                return prior.result;
            }
            if (run.finalized) throw failure("AGENT_RUN_FINALIZING", "已有确定提案，未执行新的调用；请根据原结果收尾，仍需用户在卡片确认");
            if (run.awaitingCameraSelection
                    && ("prepare_video_playback".equals(tool.name()) || "review_safety_event".equals(tool.name()))) {
                throw new com.ehe.iotagent.agent.AgentToolHaltException("CAMERA_SELECTION_REQUIRED",
                        "已有多个监控候选，请先由用户选择，不能自行挑选或逐台生成录像入口");
            }
            boolean read = tool.confirmation() == AgentToolCatalog.ConfirmationEnum.NONE && !"create_energy_report".equals(tool.name());
            rejectRepeatedFailure(run.failures.get(callFingerprint));
            try {
                if (!read) hydrateReferences(run, stable, resolveCandidate);
            } catch (BusinessException error) {
                recordFailure(run, callFingerprint, callFingerprint, callId, error, read);
                throw error;
            }
            String fingerprint = run.candidateSets.isEmpty() && run.deviceRefs.isEmpty() ? callFingerprint
                    : tool.name() + ":" + json(stable, false, run);
            Failure previousFailure = run.failures.get(fingerprint);
            rejectRepeatedFailure(previousFailure);
            if (!read && run.successes.containsKey(fingerprint)) {
                throw failure("AGENT_NO_PROGRESS", "相同提案或报告已经准备，请保留原对象，不重复生成或执行");
            }
            // Runtime beforeToolCall: an identical non-read call is refused on the
            // second attempt unless the prior failure was a transient read
            // (network/timeout/5xx) that a corrected re-preparation may legally retry.
            if (!read && previousFailure != null
                    && !(previousFailure.transientFailure()
                            && tool.confirmation() != AgentToolCatalog.ConfirmationEnum.NONE)) {
                throw failure("AGENT_NO_PROGRESS", "同一调用没有产生新结果，本次不再重复；候选集句柄刷新或相同成员的重复检索不算新证据，"
                        + "请改为用户点名设备的精确引用、真正不同的范围或参数，或请用户澄清确切范围；没有可行下一步时如实说明阻碍",
                        noProgressRecovery());
            }
            try {
                if ("propose_task_cancellation".equals(tool.name())
                        && !run.listedTaskIds.contains(arguments.get("taskId"))) {
                    throw new BusinessException("TASK_NOT_QUERIED", "请先查询并核对要取消的任务", 409);
                }
                Map<String, Object> result = execute.get();
                if (result == null) throw failure("AGENT_TOOL_RESULT_UNAVAILABLE", "工具没有返回有效业务结果");
                if ("get_task".equals(tool.name()) && "task".equals(arguments.get("type"))) {
                    run.listedTaskIds.add(String.valueOf(arguments.get("id")));
                } else if ("list_tasks".equals(tool.name()) && result.get("tasks") instanceof Iterable<?> listed) {
                    for (Object value : listed) {
                        if (value instanceof Map<?, ?> task) {
                            Object id = task.containsKey("id") ? task.get("id") : task.get("taskId");
                            if (id instanceof String taskId) run.listedTaskIds.add(taskId);
                        }
                    }
                }
                // Runtime sets the camera-selection halt only from the safety-review
                // clarification path (pi-runner.ts safetyReviewTool); a wide camera
                // find alone never blocks a user-named playback target.
                if ("review_safety_event".equals(tool.name()) && result.get("candidates") instanceof java.util.List<?> candidates
                        && candidates.size() > 1 && !result.containsKey("videoPlayback")) {
                    run.awaitingCameraSelection = true;
                }
                if (read && !"duplicate_result".equals(result.get("status"))) {
                    String evidenceKey = tool.name() + ":" + json(stable, true, run);
                    Object evidence = canonical(result, true, run);
                    Object previous = run.readEvidence.put(evidenceKey, evidence);
                    if (!java.util.Objects.equals(previous, evidence)) {
                        // Candidate renewals cannot reopen a failed proposal; genuine
                        // capability/state evidence still reopens the original recovery path.
                        if ("find_device_candidates".equals(tool.name()) || "get_device_details".equals(tool.name())) {
                            run.failures.values().removeIf(Failure::read);
                        } else {
                            run.failures.clear();
                        }
                    }
                } else if (!read) {
                    run.successes.put(fingerprint, 1);
                    run.failures.remove(fingerprint);
                }
                // get_device_details returns the same discovery-shaped projection as a
                // fresh find, so both register renewed aliases; duplicate folds carry
                // no candidateSetId and are skipped.
                if (result.get("candidateSetId") instanceof String) rememberCandidateEvidence(run, result);
                run.finalized = AgentToolCatalogSupport.haltsRun(tool.name(), result);
                run.calls.put(callId, new Invocation(callFingerprint, result, null));
                return result;
            } catch (BusinessException error) {
                recordFailure(run, fingerprint, callFingerprint, callId, error, read);
                throw error;
            }
        }
    }

    private static void rejectRepeatedFailure(Failure previous) {
        if (previous != null && previous.count() >= 2) throw failure("AGENT_NO_PROGRESS",
                "同一调用连续失败且没有新证据；请修正参数或使用其他合法取证，不重复原调用，不缩小用户范围绕过故障",
                noProgressRecovery());
    }

    private static void recordFailure(Run run, String fingerprint, String callFingerprint, String callId,
            BusinessException error, boolean read) {
        Failure previous = run.failures.get(fingerprint);
        String errorIdentity = error.code() + ":" + error.getMessage();
        run.failures.put(fingerprint, new Failure(errorIdentity,
                previous != null && previous.error().equals(errorIdentity) ? previous.count() + 1 : 1,
                error.httpStatus() >= 500, read));
        run.calls.put(callId, new Invocation(callFingerprint, null, error));
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

    private String json(Object value, boolean evidence, Run run) {
        try { return mapper.writeValueAsString(canonical(value, evidence, run)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw failure("INVALID_AGENT_TOOL_ARGUMENTS", "工具参数无法规范化");
        }
    }

    private static Object canonical(Object value, boolean evidence, Run run) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, item) -> {
                String field = String.valueOf(key);
                if (!evidence || !transientEvidenceField(field)) sorted.put(field,
                        referenceField(field) ? reference(item, evidence, run) : canonical(item, evidence, run));
            });
            return sorted;
        }
        if (value instanceof Iterable<?> list) {
            var result = new ArrayList<>();
            list.forEach(item -> result.add(canonical(item, evidence, run)));
            return result;
        }
        return value;
    }

    private static boolean referenceField(String field) {
        return "candidateSetId".equals(field) || "candidateSetIds".equals(field)
                || "excludeCandidateSetIds".equals(field) || "deviceId".equals(field) || "deviceIds".equals(field);
    }

    private static Object reference(Object value, boolean evidence, Run run) {
        if (run != null && value instanceof String text) {
            String members = run.candidateSets.get(text);
            if (members != null) return "candidate-members:" + members;
            String device = run.deviceRefs.get(text);
            if (device != null) return "device:" + device;
        }
        if (value instanceof Iterable<?> values) {
            var result = new ArrayList<>();
            values.forEach(item -> result.add(reference(item, evidence, run)));
            return result;
        }
        return canonical(value, evidence, run);
    }

    /**
     * Server-owned alias evidence from a successful discovery: renewed set and
     * device handles over the same members must not look like new facts to the
     * no-progress rules. Real ids stay inside the run and never reach the model.
     */
    private void hydrateReferences(Run run, Object arguments, Function<String, DeviceCandidateRecord> resolve) {
        var references = new java.util.LinkedHashSet<String>();
        collectReferences(arguments, references, false);
        for (String ref : references) {
            if (run.candidateSets.containsKey(ref) || run.deviceRefs.containsKey(ref)) continue;
            var device = DEVICE_HANDLE.matcher(ref);
            String set = device.matches() ? "s" + device.group(1) : ref;
            DeviceCandidateRecord record = resolve.apply(set);
            if (record == null || !record.complete() || record.members().size() != record.totalMatches()) continue;
            String signature = memberSignature(Map.of("query", Map.of("kind", record.query().kind()),
                    "resolution", Map.of("mode", record.resolutionMode()), "membersComplete", true,
                    "totalMatches", record.totalMatches()), DeviceCandidateRecord.scopeFingerprint(record.members()));
            if (run.candidateSets.size() < 64) {
                run.candidateSets.put(set, signature);
                run.candidateSets.put(record.candidateSetId(), signature);
            }
            if (record.handle() != null && CANDIDATE_HANDLE.matcher(record.handle()).matches()) {
                for (int index = 0; index < record.members().size() && run.deviceRefs.size() < 512; index++) {
                    run.deviceRefs.put(record.deviceHandle(index), record.members().get(index).id());
                }
            }
        }
    }

    private static void collectReferences(Object value, java.util.Set<String> references, boolean reference) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> collectReferences(item, references, referenceField(String.valueOf(key))));
        } else if (value instanceof Iterable<?> values) {
            values.forEach(item -> collectReferences(item, references, reference));
        } else if (reference && value instanceof String text
                && (CANDIDATE_HANDLE.matcher(text).matches() || DEVICE_HANDLE.matcher(text).matches())) {
            references.add(text);
        }
    }

    private void rememberCandidateEvidence(Run run, Map<String, Object> result) {
        List<?> full = result.get("devices") instanceof List<?> devices ? devices : List.of();
        List<?> shown = result.get("modelResult") instanceof Map<?, ?> model
                && model.get("devices") instanceof List<?> rows ? rows : List.of();
        int shared = Math.min(full.size(), shown.size());
        for (int index = 0; index < shared && run.deviceRefs.size() < 512; index++) {
            if (!(full.get(index) instanceof Map<?, ?> entry) || !(entry.get("id") instanceof String id)) break;
            if (!(shown.get(index) instanceof Map<?, ?> row) || !(row.get("handle") instanceof String handle)) break;
            run.deviceRefs.put(handle, id);
        }
        if (!(result.get("candidateMembersFingerprint") instanceof String membership)) return;
        String signature = memberSignature(result, membership);
        if (run.candidateSets.size() >= 64) return;
        if (result.get("candidateSetId") instanceof String setId) run.candidateSets.put(setId, signature);
        if (result.get("handle") instanceof String handle) run.candidateSets.put(handle, signature);
    }

    /** Member scope and precision identity: membership, size, completeness and resolution mode. */
    private String memberSignature(Map<String, Object> result, String membership) {
        var signature = new TreeMap<String, Object>();
        if (result.get("query") instanceof Map<?, ?> query && query.get("kind") instanceof String kind) {
            signature.put("kind", kind);
        }
        if (result.get("resolution") instanceof Map<?, ?> resolution
                && resolution.get("mode") instanceof String mode) {
            signature.put("resolution", mode);
        }
        signature.put("membersComplete", Boolean.TRUE.equals(result.get("membersComplete")));
        signature.put("totalMatches", result.get("totalMatches"));
        signature.put("members", membership);
        return json(signature, false, null);
    }

    /** Generic no-progress recovery: precise lookup or clarification, never a same-scope retry. */
    private static Map<String, Object> noProgressRecovery() {
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("reason", "no_new_evidence");
        recovery.put("retrySameScope", false);
        recovery.put("nextTool", "get_device_details");
        recovery.put("guidance", "本次重试与已失败的上一尝试成员范围、精度和控制意图相同；请改用用户点名设备的精确引用"
                + "（get_device_details 补读形成精确候选集），或使用真正不同的范围与参数；范围不明时请用户澄清确切位置与成员，"
                + "不要重复相同位置检索或原样重提");
        return recovery;
    }

    private static BusinessException failure(String code, String message, Map<String, Object> recovery) {
        return new BusinessException(code, message, recovery, 409);
    }


    private static BusinessException failure(String code, String message) {
        return "AGENT_RUN_FINALIZING".equals(code)
                ? new com.ehe.iotagent.agent.AgentToolHaltException(code, message)
                : new BusinessException(code, message, 409);
    }
    private record Invocation(String fingerprint, Map<String, Object> result, BusinessException error) { }
    private record Failure(String error, int count, boolean transientFailure, boolean read) { }
    private static final class Run {
        final long created;
        boolean finalized;
        boolean awaitingCameraSelection;
        final Map<String, Failure> failures = new HashMap<>();
        final java.util.Set<String> listedTaskIds = new java.util.HashSet<>();
        final Map<String, Integer> successes = new HashMap<>();
        final Map<String, Object> readEvidence = new HashMap<>();
        final Map<String, String> candidateSets = new HashMap<>();
        final Map<String, String> deviceRefs = new HashMap<>();
        final Map<String, Invocation> calls = new HashMap<>();
        Run(long created) { this.created = created; }
    }
}
