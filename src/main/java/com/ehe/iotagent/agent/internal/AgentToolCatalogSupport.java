package com.ehe.iotagent.agent.internal;

import java.util.Map;

/** Narrow internal helper for the transport envelope; not business dispatch. */
public final class AgentToolCatalogSupport {
    private AgentToolCatalogSupport() { }

    public static boolean haltsRun(String toolName, Map<String, Object> result) {
        if (toolName == null || result == null) return false;
        if (("finish_checkpoint".equals(toolName) || "publish_report".equals(toolName))
                && "submitted".equals(result.get("status"))) return true;
        if ("propose_task".equals(toolName)) {
            // Runtime proposeTaskTool: a first candidate-set proposal leaves the run open
            // for a second independent task; the run finalizes on a schedule clarification,
            // a second proposal, or the single-shot company targets path (haltRun marker).
            return "needs_clarification".equals(result.get("status"))
                    || Boolean.TRUE.equals(result.get("haltRun"));
        }
        AgentToolCatalog.Definition definition = AgentToolCatalog.definition(toolName);
        if (definition == null || definition.confirmation() == AgentToolCatalog.ConfirmationEnum.NONE) return false;
        Object status = result.get("status");
        return status == null || "PENDING_CONFIRMATION".equalsIgnoreCase(String.valueOf(status))
                || "NO_CHANGE".equalsIgnoreCase(String.valueOf(status));
    }

    public static String completionText(String toolName, Map<String, Object> result, Map<?, ?> arguments) {
        // Runtime phaseTaskResult gets a fixed receipt; candidate taskProposals keep
        // the model summary, including when the second proposal closes the tool phase.
        boolean companyTargets = arguments.get("phases") instanceof java.util.List<?> phases
                && phases.stream().anyMatch(value -> value instanceof Map<?, ?> phase
                        && phase.get("targets") instanceof java.util.List<?> targets && !targets.isEmpty());
        if ("propose_task".equals(toolName) && companyTargets
                && "PENDING_CONFIRMATION".equals(result.get("status"))
                && result.get("payload") instanceof Map<?, ?> payload && "company".equals(payload.get("source"))) {
            return "请核对卡片，确认后才会创建任务。";
        }
        if (("propose_action_draft".equals(toolName) || "propose_lighting_draft".equals(toolName))
                && "NO_CHANGE".equals(result.get("status"))) {
            return "目标已满足，本次没有待下发操作，也未执行控制。";
        }
        if (("propose_action_draft".equals(toolName) || "propose_lighting_draft".equals(toolName))
                && "PENDING_CONFIRMATION".equals(result.get("status"))
                && result.get("actions") instanceof java.util.List<?> actions) {
            boolean pending = actions.stream().anyMatch(value ->
                    value instanceof Map<?, ?> action && !Boolean.TRUE.equals(action.get("satisfied")));
            return pending ? "请核对卡片，确认前不会下发操作。"
                    : "目标已满足，本次没有待下发操作，也未执行控制。";
        }
        return null;
    }

    /** Full facts remain in result; model history gets goals and gaps, not a second device roster. */
    public static Object cardModelResult(String tool, Map<String, Object> result, Object existing) {
        if (!"PENDING_CONFIRMATION".equals(result.get("status"))) return existing;
        boolean task = "propose_task".equals(tool) || "propose_task_change".equals(tool) || "propose_task_cancellation".equals(tool);
        boolean action = "propose_action_draft".equals(tool) || "propose_lighting_draft".equals(tool);
        if (!task && !action) return existing;
        Map<String, Object> model = existing instanceof Map<?, ?> values
                ? new java.util.LinkedHashMap<>()
                : selected(result, "proposalId", "draftId", "version", "status", "kind", "operation",
                        "expiresAt", "nextCheckpointAt", "nextOccurrenceId", "counts", "warnings", "note");
        if (existing instanceof Map<?, ?> values) values.forEach((key, value) -> {
            if (key instanceof String name) model.put(name, value);
        });
        if (task) {
            for (String key : new String[] {"payload", "before", "after", "target"}) {
                if (result.get(key) instanceof Map<?, ?> payload) model.put(key, taskSummary(payload));
            }
        }
        model.put("cardContent", java.util.List.of("devices", "parameters", "schedule", "counts", "confirmation"));
        model.put("confirmationReply", "propose_task".equals(tool) ? "请核对卡片，确认后才会创建任务。"
                : action ? "请核对卡片，确认前不会下发操作。" : "请核对变更卡，确认后才会生效。");
        model.put("replyInstruction", "本次提案待用户确认，不能声称本次提案已创建、已变更或已执行。先完成用户其余独立目标；"
                + "全部目标均已有正常卡片且没有额外问题时，最终正文直接使用confirmationReply这一句话，不加前言、摘要或总结，"
                + "不复述设备、数量、参数或时间。存在未完成目标、歧义、排除、状态未知或用户要求解释时，才补充这些必要内容；"
                + "用户明确要求完整清单时按原对象读取事实。");
        return model;
    }

    private static Map<String, Object> taskSummary(Map<?, ?> payload) {
        Map<String, Object> summary = selected(payload, "taskId", "planId", "id", "type", "kind", "label",
                "source", "status", "repeat", "interval", "operation", "reason");
        if (payload.get("phases") instanceof java.util.List<?> phases) {
            summary.put("phases", phases.stream().map(value -> {
                if (!(value instanceof Map<?, ?> phase)) return Map.of();
                Map<String, Object> item = selected(phase, "at", "title");
                if (phase.get("actions") instanceof java.util.List<?> actions) {
                    Map<Map<String, Object>, Integer> groups = new java.util.LinkedHashMap<>();
                    for (Object entry : actions) if (entry instanceof Map<?, ?> action) {
                        groups.merge(selected(action, "kind", "command"), 1, Integer::sum);
                    }
                    item.put("actionTargets", groups.entrySet().stream().map(entry -> {
                        Map<String, Object> target = new java.util.LinkedHashMap<>(entry.getKey());
                        target.put("count", entry.getValue());
                        return target;
                    }).toList());
                }
                if (phase.get("preflight") instanceof Map<?, ?> preflight) {
                    item.put("preflight", selected(preflight, "excludedScopes", "excludedCount", "unreadableCount", "observedAt"));
                }
                if (phase.get("read") instanceof Map<?, ?> read) {
                    Map<String, Object> reading = selected(read, "ac", "agent", "alertWindow", "alertDays", "all");
                    if (read.get("devices") instanceof java.util.List<?> devices) reading.put("deviceCount", devices.size());
                    item.put("read", reading);
                }
                return item;
            }).toList());
        }
        return summary;
    }

    private static Map<String, Object> selected(Map<?, ?> source, String... keys) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String key : keys) if (source.containsKey(key)) result.put(key, source.get(key));
        return result;
    }
}
