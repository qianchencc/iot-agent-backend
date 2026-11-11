package ltd.qianc.iotagent.agent.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ltd.qianc.iotagent.error.BusinessException;

/** Java-owned tool data migrated from Runtime pi-runner.ts, without a second schema convention. */
final class AgentToolCatalog {
    enum ConfirmationEnum { NONE, ACTION, TASK, TASK_CHANGE, TASK_CANCELLATION }

    record Definition(String name, String label, String description, Map<String, Object> parameters,
                      Set<String> sources, ConfirmationEnum confirmation) { }
    private record ToolData(String name, String label, String description, Map<String, Object> parameters) { }
    private static final Map<String, Map<String, Definition>> TOOLS = load();

    private AgentToolCatalog() { }

    static boolean taskTool(String name) {
        return switch (name) {
            case "propose_task", "propose_task_change", "propose_task_cancellation",
                    "list_tasks", "list_task_plans", "get_task" -> true;
            default -> false;
        };
    }

    static List<Definition> forSource(String source) {
        return List.copyOf(sourceTools(source).values());
    }

    static Definition find(String source, String name) {
        Definition definition = sourceTools(source).get(name);
        if (definition == null) throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "工具未接入或当前来源不可用", 404);
        return definition;
    }

    static Definition definition(String name) {
        Definition company = TOOLS.get("company").get(name);
        return company == null ? TOOLS.get("office").get(name) : company;
    }

    private static Map<String, Definition> sourceTools(String source) {
        Map<String, Definition> tools = TOOLS.get(source);
        if (tools == null) throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        return tools;
    }

    private static Map<String, Map<String, Definition>> load() {
        try (var stream = AgentToolCatalog.class.getResourceAsStream("/agent/tool-definitions.json")) {
            if (stream == null) throw new IllegalStateException("Missing original tool definitions");
            Map<String, Map<String, ToolData>> data = new ObjectMapper().readValue(stream, new TypeReference<>() { });
            Map<String, Map<String, Definition>> sources = new LinkedHashMap<>();
            for (String source : List.of("office", "company")) {
                Map<String, Definition> tools = new LinkedHashMap<>();
                for (ToolData tool : data.get(source).values()) {
                    tools.put(tool.name(), new Definition(tool.name(), tool.label(), tool.description(), tool.parameters(),
                            Set.of(source), confirmation(tool.name())));
                }
                addExistingJavaTools(tools, source);
                sources.put(source, Collections.unmodifiableMap(tools));
            }
            return Collections.unmodifiableMap(sources);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load original tool definitions", failure);
        }
    }

    private static ConfirmationEnum confirmation(String name) {
        return switch (name) {
            case "propose_action_draft", "propose_lighting_draft" -> ConfirmationEnum.ACTION;
            case "propose_task" -> ConfirmationEnum.TASK;
            case "propose_task_change" -> ConfirmationEnum.TASK_CHANGE;
            case "propose_task_cancellation" -> ConfirmationEnum.TASK_CANCELLATION;
            default -> ConfirmationEnum.NONE;
        };
    }

    /** Existing Java-only maintenance extension and draft readback tools; not replacements for Runtime tools. */
    private static void addExistingJavaTools(Map<String, Definition> tools, String source) {
        if ("office".equals(source)) {
            tools.put("list_office_regions", new Definition("list_office_regions", "查询可用办公室区域",
                    "只读查询当前账号真实有权选择的办公室关注区域；可按名称包含的文字筛选。回答可选区域，不切换区域，不创建任务。当前区域仍由用户界面选择。",
                    Map.of("type", "object", "properties", Map.of("nameContains", Map.of("type", "string", "minLength", 1,
                            "maxLength", 120, "description", "可选：区域名称包含的非空文字")), "required", List.of(),
                            "additionalProperties", false), Set.of(source), ConfirmationEnum.NONE));
        }
        addDraftRead(tools, source, "read_action_draft", "读取操作草案",
                "按本端真实草案标识读取当前待确认草案、动作和目标参数。只读，不能确认或执行。");
        addDraftRead(tools, source, "read_action_device_states", "读取草案设备回读",
                "读取已保存草案的设备执行回读事实。只读；没有回读就是没有证据，不得据此声称设备已改变。");
        addDraftRead(tools, source, "read_action_summary", "读取草案结果摘要",
                "读取草案回执与逐设备回读摘要，区分已下发、已回读、失败和无需操作。只读。");
    }

    private static void addDraftRead(Map<String, Definition> tools, String source, String name, String label, String description) {
        tools.put(name, new Definition(name, label, description, Map.of("type", "object", "required", List.of("draftId"),
                "properties", Map.of("draftId", Map.of("type", "string", "minLength", 1, "maxLength", 300,
                        "description", "本端草案标识")), "additionalProperties", false), Set.of(source), ConfirmationEnum.NONE));
    }

    static List<String> requiredArgumentNames(Definition definition) {
        Object required = definition.parameters().get("required");
        if (!(required instanceof List<?> values)) return List.of();
        List<String> result = new ArrayList<>(values.size());
        for (Object value : values) result.add(String.valueOf(value));
        return result;
    }

    static Set<String> topLevelArgumentNames(Definition definition) {
        Object properties = definition.parameters().get("properties");
        if (!(properties instanceof Map<?, ?> map)) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (Object key : map.keySet()) names.add(String.valueOf(key));
        return names;
    }
}
