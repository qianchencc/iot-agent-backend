package com.ehe.iotagent.agent.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the facts the model needs after an observation has been rendered by a UI component.
 * Full rows, candidates, samples, and playback details remain in the transport result.
 */
final class AgentObservationModelProjection {
    private AgentObservationModelProjection() { }

    static Map<String, Object> project(String tool, Map<String, Object> result) {
        return switch (tool) {
            case "query_alerts" -> summary(result, "alert_list", List.of(
                    "status", "total", "scanned", "excludedCount", "coverageComplete", "summaryStatus",
                    "windowDays", "failures"), List.of("items"));
            case "query_environment_history" -> summary(result, "environment_history", List.of(
                    "status", "type", "observedAt", "from", "to", "window", "windowSummary",
                    "coverage", "coverageComplete", "summary", "metrics", "failures", "message", "retryable"),
                    List.of("samples", "series", "rows", "items"));
            case "query_energy_history" -> summary(result, "energy_history", List.of(
                    "status", "type", "observedAt", "from", "to", "window", "windowSummary",
                    "coverage", "coverageComplete", "summary", "total", "metrics", "failures", "message", "retryable"),
                    List.of("samples", "series", "rows", "items", "meters"));
            case "summarize_energy", "compare_energy" -> "meter-report".equals(result.get("type"))
                    ? AgentEnergyRunFacts.modelFacts(result)
                    : summary(result, "energy_report", List.of("status", "type", "observedAt", "summary",
                            "total", "coverage", "coverageComplete", "failures", "message", "retryable"),
                            List.of("rows", "series", "samples", "meters"));
            case "create_energy_report" -> summary(result, "report_artifact",
                    List.of("status", "reportId", "reused", "summary", "message", "retryable"), List.of("report"));
            case "read_action_draft", "read_action_summary" -> summary(result, "action_card",
                    List.of("draftId", "status", "revision", "observedAt", "message", "retryable"),
                    List.of("actions", "states", "devices"));
            case "read_action_device_states" -> stateSummary(result);
            case "list_tasks" -> taskListSummary(result, "task_list", "tasks", "taskId");
            case "list_task_plans" -> taskListSummary(result, "task_plan_list", "plans", "planId");
            case "get_task" -> taskDetailSummary(result);
            case "read_device_states" -> stateSummary(result);
            case "prepare_video_playback" -> videoSummary(result, "video_playback");
            case "review_safety_event" -> safetySummary(result);
            default -> null;
        };
    }

    private static Map<String, Object> summary(Map<String, Object> result, String component,
            List<String> keys, List<String> detailKeys) {
        Map<String, Object> model = selected(result, keys);
        addCounts(result, model, detailKeys);
        model.put("presentation", Map.of(
                "component", component,
                "detailsRenderedByComponent", true,
                "detailKeys", detailKeys));
        return model;
    }

    private static Map<String, Object> taskListSummary(Map<String, Object> result, String component,
            String detailKey, String idKey) {
        Map<String, Object> model = selected(result, List.of("regionId", "date", "status", "message", "retryable"));
        List<String> ids = new java.util.ArrayList<>();
        if (result.get(detailKey) instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> item && item.get(idKey) != null) ids.add(String.valueOf(item.get(idKey)));
            }
        }
        model.put("itemCount", ids.size());
        model.put(idKey + "s", ids);
        model.put("presentation", Map.of("component", component, "detailsRenderedByComponent", true,
                "detailKeys", List.of(detailKey)));
        return model;
    }

    private static Map<String, Object> taskDetailSummary(Map<String, Object> result) {
        Map<String, Object> model = selected(result, List.of("status", "message", "retryable"));
        for (String key : List.of("task", "plan")) {
            if (result.get(key) instanceof Map<?, ?> detail) {
                for (String field : List.of("taskId", "planId", "status", "version", "operation")) {
                    if (detail.containsKey(field)) model.put(field, detail.get(field));
                }
                break;
            }
        }
        model.put("presentation", Map.of("component", "task_detail", "detailsRenderedByComponent", true,
                "detailKeys", List.of("task", "plan")));
        return model;
    }

    private static Map<String, Object> stateSummary(Map<String, Object> result) {
        Map<String, Object> model = selected(result,
                List.of("observedAt", "status", "coverageComplete", "failures", "message", "missingDeviceIds", "cache"));
        addCounts(result, model, List.of("states"));
        model.put("presentation", Map.of("component", "device_states", "detailsRenderedByComponent", true,
                "detailKeys", List.of("states")));
        return model;
    }

    private static Map<String, Object> capabilitySummary(Map<String, Object> result) {
        Map<String, Object> model = selected(result,
                List.of("observedAt", "status", "coverageComplete", "failures", "message"));
        addCounts(result, model, List.of("devices", "capabilities"));
        model.put("presentation", Map.of("component", "device_capabilities", "detailsRenderedByComponent", true,
                "detailKeys", List.of("devices", "capabilities")));
        return model;
    }

    private static Map<String, Object> videoSummary(Map<String, Object> result, String component) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("videoReady", result.get("videoPlayback") instanceof Map<?, ?>);
        model.put("presentation", Map.of("component", component, "detailsRenderedByComponent", true,
                "detailKeys", List.of("videoPlayback")));
        return model;
    }

    private static Map<String, Object> safetySummary(Map<String, Object> result) {
        Map<String, Object> model = selected(result,
                List.of("status", "eventId", "coverageComplete", "failures", "message"));
        addCounts(result, model, List.of("candidates"));
        model.put("selectionRequired", result.get("candidates") instanceof List<?> candidates && candidates.size() > 1
                && !(result.get("videoPlayback") instanceof Map<?, ?>));
        model.put("videoReady", result.get("videoPlayback") instanceof Map<?, ?>);
        model.put("presentation", Map.of("component", "safety_event", "detailsRenderedByComponent", true,
                "detailKeys", List.of("candidates", "videoPlayback")));
        return model;
    }

    private static void addCounts(Map<String, Object> result, Map<String, Object> model, List<String> keys) {
        for (String key : keys) {
            Object value = result.get(key);
            if (value instanceof List<?> values) model.put(key + "Count", values.size());
            else if (value instanceof Map<?, ?> values) model.put(key + "Count", values.size());
        }
    }

    private static Map<String, Object> selected(Map<String, Object> source, List<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) if (source.containsKey(key)) result.put(key, source.get(key));
        return result;
    }
}
