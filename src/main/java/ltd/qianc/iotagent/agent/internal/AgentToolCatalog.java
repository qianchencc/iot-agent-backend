package ltd.qianc.iotagent.agent.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ltd.qianc.iotagent.error.BusinessException;

/**
 * Single Java-owned source of truth for the business tools exposed to the generic
 * Agent Engine. Names, labels, JSON Schemas, descriptions and confirmation
 * behaviour are all data here; the Node side only executes what is supplied.
 */
final class AgentToolCatalog {

    enum Confirmation {
        NONE,
        ACTION,
        TASK,
        TASK_CHANGE,
        TASK_CANCELLATION
    }

    record Definition(
            String name,
            String label,
            String description,
            Map<String, Object> parameters,
            Set<String> sources,
            Confirmation confirmation) {
        Definition {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
            if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
            if (parameters == null) throw new IllegalArgumentException("parameters are required");
            sources = Set.copyOf(sources);
        }
    }

    private static final Set<String> OFFICE = Set.of("office");
    private static final Set<String> COMPANY = Set.of("company");
    private static final Set<String> BOTH = Set.of("office", "company");
    private static final Map<String, Definition> TOOLS = build();

    private AgentToolCatalog() { }

    static List<Definition> forSource(String source) {
        requireSource(source);
        return TOOLS.values().stream().filter(tool -> tool.sources().contains(source)).toList();
    }

    static Definition find(String source, String name) {
        requireSource(source);
        Definition definition = TOOLS.get(name);
        if (definition == null || !definition.sources().contains(source)) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "工具未接入或当前来源不可用", 404);
        }
        return definition;
    }

    static Definition definition(String name) {
        return TOOLS.get(name);
    }

    private static Map<String, Definition> build() {
        Map<String, Definition> tools = new LinkedHashMap<>();
        add(tools, definition("list_office_regions", "查询可用办公室区域",
                "只读列出当前账号真实有权选择的办公室关注区域，回答有哪些办公室可切换；不切换区域，不创建任务。当前区域仍由用户界面选择。",
                object(required(), props()), OFFICE, Confirmation.NONE));
        add(tools, definition("find_device_candidates", "查询设备候选",
                "按当前来源、当前关注区域和当前授权目录检索设备候选。位置条件放 scopeText/scopeTerms，设备名称或型号放 deviceText/nameHints；不要把地点拼进设备名称。返回真实候选及 candidateSetId；候选不是用户已选择的操作范围，空候选必须如实处理，不得发明设备。",
                object(required("kind"), props(
                        prop("kind", stringEnum("设备类型", "lighting", "ac", "access", "video", "environment", "energy", "safety", "unknown")),
                        prop("scopeText", string("位置或区域描述；当前区域由服务端绑定，不要传内部 focusRegionId", 1, 120)),
                        prop("deviceText", string("设备名称或型号片段", 1, 80)),
                        prop("scopeTerms", stringArray("拆分的真实位置条件", 0, 8)),
                        prop("nameHints", stringArray("设备名称候选词", 0, 8)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("get_device_details", "读取设备详情",
                "补读一个已有候选集内明确设备句柄的名称与位置，返回精确候选集供状态、能力和提案引用。只读，不能读取候选集外设备，也不能替用户选择尚有歧义的对象。",
                object(required("candidateSetId", "deviceIds"), props(
                        prop("candidateSetId", string("已有候选集标识", 1, null)),
                        prop("deviceIds", stringArray("候选集内设备标识", 1, 50)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("read_device_states", "查询设备状态",
                "批量读取当前授权候选设备的真实状态和观测时间。照明返回开关，空调返回参数，视频返回在线态，环境/能耗/安全返回只读观察事实。未知不是关闭；历史观测不代表当前状态；查询不产生控制或草案。",
                object(required("candidateSets"), props(
                        prop("candidateSets", arrayOf(candidateSet("候选集及其中的真实设备标识"), 1, 10)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("inspect_device_capabilities", "查询设备能力",
                "一次查询一个或多个候选集中指定设备实际支持的原子操作。只读，不生成草案，也不控制设备。",
                object(required("candidateSets"), props(
                        prop("candidateSets", arrayOf(candidateSet("待核验能力的候选集"), 1, 10)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("propose_action_draft", "生成操作草案",
                "基于已查明的完整候选集生成一份待人工确认的操作草案。candidateSetIds 必填且是唯一成员宇宙；actions 可按每种设备类型给一个代表动作，由服务器展开该候选集内同类型全部成员并应用同一命令，不要逐台抄写。每个动作只传对应类型参数：照明 closeOrOpen、空调 ac、门禁 controlType=2；selected=false 仅用于明确排除，未列出的展开成员默认保留。服务器重新核验当前授权、能力、成员和状态；不会下发控制，模型不能确认或执行。",
                object(required("summary", "candidateSetIds", "actions"), props(
                        prop("candidateSetIds", stringArray("已查明的完整候选集标识；草案只在这些服务器成员内展开", 1, 10)),
                        prop("summary", string("草案摘要，说明范围与目标", 1, 200)),
                        prop("question", string("需要用户确认或澄清的问题", 1, 500)),
                        prop("excludedDeviceIds", stringArray("明确排除的设备标识", 0, 50)),
                        prop("actions", arrayOf(controlAction(), 1, 50)))),
                BOTH, Confirmation.ACTION));
        add(tools, definition("read_action_draft", "读取操作草案",
                "按本端真实草案标识读取当前待确认草案、动作和目标参数。只读，不能确认或执行。",
                object(required("draftId"), props(prop("draftId", string("本端草案标识", 1, 300)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("read_action_device_states", "读取草案设备回读",
                "读取已保存草案的设备执行回读事实。只读；没有回读就是没有证据，不得据此声称设备已改变。",
                object(required("draftId"), props(prop("draftId", string("本端草案标识", 1, 300)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("read_action_summary", "读取草案结果摘要",
                "读取草案回执与逐设备回读摘要，区分已下发、已回读、失败和无需操作。只读。",
                object(required("draftId"), props(prop("draftId", string("本端草案标识", 1, 300)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("prepare_video_playback", "准备录像入口",
                "为用户指定的本端摄像头和带时区的录像窗口准备观看卡，不取流、不承诺录像存在；单次窗口最多三天。时间不明先澄清。",
                object(required("deviceId", "beginTime", "endTime"), props(
                        prop("deviceId", string("本端可见摄像头标识", 1, 300)),
                        prop("beginTime", string("带时区录像开始时间", 1, 64)),
                        prop("endTime", string("带时区录像结束时间，晚于开始", 1, 64)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("query_alerts", "查询安全告警",
                "查询公司安全台账中的烟感/水浸/燃气历史记录（平台记录，不是实时报警）。report 仅为平台上报标记，不代表处置或恢复；台账没有当前设备态证据。单类读取失败时保留其他类别并标明覆盖缺口。",
                object(required(), props(
                        prop("types", stringArray("告警类型：smoke、water、gas", 1, 3, "smoke", "water", "gas")),
                        prop("days", integer("回看天数，缺省 30", 1, 365)),
                        prop("from", string("带时区的窗口起点；仅与 to 成对使用", 1, 64)),
                        prop("to", string("带时区的窗口终点；不晚于当前时间", 1, 64)),
                        prop("unreportedOnly", bool("只看平台标记未上报的记录")))),
                COMPANY, Confirmation.NONE));
        add(tools, definition("query_environment_history", "查询环境趋势",
                "只读查询 Company 站点环境历史。起止时间必须带时区；温湿度和 PM 指标来自真实采样。deviceCode 缺失不归属具体房间，颗粒物单位未知，不判断达标；覆盖不完整时如实说明。",
                object(required("from", "to"), props(
                        prop("from", string("带时区 ISO 起点", 1, 64)),
                        prop("to", string("带时区 ISO 终点", 1, 64)),
                        prop("page", integer("分页序号", 0, 10000)))),
                COMPANY, Confirmation.NONE));
        add(tools, definition("query_energy_history", "查询计量历史",
                "只读查询 Company 计量点的原始累计示数和采样。使用已查到的 energy 计量点稳定标识；示数单位为度，累计示数不是期间用电。期间统计和对比使用专用工具。",
                object(required("equipmentId", "from", "to"), props(
                        prop("equipmentId", string("Company 计量点标识或已查到的设备标识", 1, 300)),
                        prop("from", string("带时区 ISO 起点", 1, 64)),
                        prop("to", string("带时区 ISO 终点", 1, 64)),
                        prop("page", integer("分页序号", 0, 10000)))),
                COMPANY, Confirmation.NONE));
        add(tools, definition("summarize_energy", "统计区间用电",
                "统计一个计量范围在 [from,to) 内的用电总量、分段与位置构成。仅表达统计或趋势分段，不表达两期增减关系。dailyWindow 可限定每日 20:00 至次日 08:00；全范围夜间分析用 scope=all。总量单位为度，缺测不得补零。",
                object(required("scope", "from", "to"), props(
                        prop("scope", meterScope("计量范围")),
                        prop("from", string("带时区 ISO 起点", 1, 64)),
                        prop("to", string("带时区 ISO 终点，区间右开", 1, 64)),
                        prop("grain", stringEnum("分段粒度", "hour", "day", "week", "month")),
                        prop("dailyWindow", dailyWindow("仅统计每日 20:00 至次日 08:00")))),
                COMPANY, Confirmation.NONE));
        add(tools, definition("compare_energy", "对比用电",
                "只在用户明确要求环比、同比、前后、变化、差异或哪个更多时使用。axis=time 固定 scope 比较本期 from/to 与 baseline（缺省紧邻上一等长区间）；axis=region 固定同一 from/to 比较 scope 与 other。一次只改变时间或范围之一。",
                object(required("axis", "scope", "from", "to"), props(
                        prop("axis", stringEnum("对比维度", "time", "region")),
                        prop("scope", meterScope("本期或第一侧计量范围")),
                        prop("other", meterScope("axis=region 时的另一侧范围")),
                        prop("from", string("本期带时区 ISO 起点", 1, 64)),
                        prop("to", string("本期带时区 ISO 终点", 1, 64)),
                        prop("baseline", baseline("明确基期；未指定日历时才省略")))),
                COMPANY, Confirmation.NONE));
        add(tools, definition("create_energy_report", "生成报告附件",
                "用户明确要求报告、日报、周报、月报、安全周报或可预览附件时使用。只声明报告意图：期间、范围和所需比较；服务器自行取得并核验授权计量/告警事实、覆盖、统计与结论。禁止传 blocks、totals、status、结论或模型自拟设备清单。普通统计或对比继续使用原工具。",
                object(required("kind", "period"), props(
                        prop("kind", stringEnum("报告类型", "energy", "safety")),
                        prop("title", string("可选报告标题；不包含未经统计的数值或结论", 1, 100)),
                        prop("period", reportPeriod("报告期间；absolute 用带时区起止，previous 用上一日/周/月")),
                        prop("comparisons", stringArray("仅列用户要求的环比/同比", 0, 2, "previousPeriod", "yearOverYear")),
                        prop("scope", reportScope("可选的已确定计量范围；省略表示当前授权全部")),
                        prop("grain", stringEnum("可选分段粒度", "day", "week", "month")))),
                COMPANY, Confirmation.NONE));
        add(tools, definition("review_safety_event", "查找事件候选监控",
                "按公司安全记录查找同地点或同楼层监控。仅一个候选或用户明确选定 cameraDeviceId 时返回录像卡；多个候选未选定时先询问，不罗列全部监控、不自行选择。不证明覆盖，不取流，不处置告警。",
                object(required("eventId", "deviceId", "ledger"), props(
                        prop("eventId", string("原始安全记录标识", 1, 300)),
                        prop("deviceId", string("原安全设备标识", 1, 300)),
                        prop("ledger", stringEnum("台账类型", "gas", "smoke", "flooding")),
                        prop("cameraDeviceId", string("用户明确选择的候选摄像头标识", 1, 300)))),
                COMPANY, Confirmation.NONE));
        add(tools, definition("propose_task", "编排定时任务",
                "把用户明确要求的一次性或周期安排保存为待人工确认的任务提案；不会直接创建或执行任务。只安排用户给出的日期、重复规则、区间和检查点，不补造终点动作；未来目标保留当前已满足设备。相对日期按本轮 current_time 的理解固化为带时区绝对时间。",
                object(required("label", "candidateSetIds", "phases"), props(
                        prop("label", string("用动作与范围命名的任务名称", 1, 80)),
                        prop("title", string("用户明确指定时的短标题", 1, 20)),
                        prop("candidateSetIds", stringArray("已查明的候选集标识", 1, 10)),
                        prop("taskKind", stringEnum("任务种类；由用户意图明确时填写",
                                "one_time", "interval", "conditional", "recurring")),
                        prop("interval", taskInterval("任务起止区间，仅在用户给出区间时提供")),
                        prop("repeat", repeat("用户明确给出的重复规则")),
                        prop("checkpointCount", integer("条件/区间检查点数量", 1, 1000)),
                        prop("reportRequest", reportRequest("可选声明式报告要求；服务器取证，禁止 blocks/totals")),
                        prop("phases", arrayOf(taskPhase(), 1, 32)))),
                BOTH, Confirmation.TASK));
        add(tools, definition("propose_task_change", "提出任务修改",
                "用本端精确 task/plan 标识提出修改、周期启停、删除或跳过，绝不直接写入。必须先 get_task 核对。仅本次发生用虚拟 occurrence task 标识，整个周期用 task_plan 标识。只生成待确认差异卡。",
                object(required("target", "operation"), props(
                        prop("target", taskTarget("要修改的任务、周期计划或本次发生")),
                        prop("operation", stringEnum("修改操作", "edit", "pause", "resume", "delete", "skip")),
                        prop("template", Map.of("type", "object", "additionalProperties", true,
                                "description", "edit 时提供的差异内容；省略表示仅时间等局部修改")))),
                BOTH, Confirmation.TASK_CHANGE));
        add(tools, definition("propose_task_cancellation", "提出取消任务",
                "引用已通过 get_task/list_tasks 查到的精确 task 标识，生成待用户确认的取消卡；不会直接取消或回滚设备动作。",
                object(required("target"), props(prop("target", taskTarget("要取消的一次性任务")))),
                BOTH, Confirmation.TASK_CANCELLATION));
        add(tools, definition("list_tasks", "查询区域任务",
                "只读查询当前用户授权来源在指定时间范围内的任务，不执行或取消任务。未指定区域时使用当前关注区域。",
                object(required(), props(
                        prop("from", string("带时区 ISO 起点", 1, 64)),
                        prop("to", string("带时区 ISO 终点", 1, 64)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("list_task_plans", "查询周期计划",
                "只读列出当前授权范围内的周期计划、启停、标识和下一次发生时间；不混用其他来源计划。",
                object(required(), props()),
                BOTH, Confirmation.NONE));
        add(tools, definition("get_task", "读取任务与报告",
                "按本端真实 taskId 或 planId 读取授权任务、周期计划及同一次报告，包含虚拟发生。只读，不能执行。引用里的标识可直接查询；同名不明确先查或澄清。",
                object(required(), props(
                        prop("taskId", string("本端任务标识", 1, 300)),
                        prop("planId", string("本端周期计划标识", 1, 300)))),
                BOTH, Confirmation.NONE));
        return tools;
    }

    private static void add(Map<String, Definition> tools, Definition definition) {
        if (tools.put(definition.name(), definition) != null) {
            throw new IllegalStateException("duplicate agent tool " + definition.name());
        }
    }

    private static Definition definition(String name, String label, String description,
            Map<String, Object> parameters, Set<String> sources, Confirmation confirmation) {
        return new Definition(name, label, description, parameters, sources, confirmation);
    }

    private static void requireSource(String source) {
        if (!"office".equals(source) && !"company".equals(source)) {
            throw new BusinessException("INVALID_AGENT_SOURCE", "Agent 来源无效", 400);
        }
    }

    private static Map<String, Object> reportPeriod(String description) {
        return object(required("kind"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("kind", stringEnum("期间类型", "absolute", "previous")),
                        prop("from", string("absolute 期间的带时区 ISO 起点", 1, 64)),
                        prop("to", string("absolute 期间的带时区 ISO 终点", 1, 64)),
                        prop("unit", stringEnum("previous 期间的日历单位", "day", "week", "month")))));
    }

    private static Map<String, Object> reportScope(String description) {
        return object(required("level"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("level", stringEnum("已确定范围类型", "all", "floor", "location", "nameMatch", "selection")),
                        prop("floor", string("明确整层的真实楼层", 1, 64)),
                        prop("text", string("已从当前目录确认的地点描述", 1, 120)),
                        prop("meterId", string("单台电表标识", 1, 300)),
                        prop("meterIds", stringArray("已核验的电表标识集合", 1, 100)))));
    }

    private static Map<String, Object> reportRequest(String description) {
        return object(required("kind", "period"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("kind", stringEnum("报告类型", "energy", "safety")),
                        prop("title", string("可选标题", 1, 100)),
                        prop("period", reportPeriod("报告期间")),
                        prop("comparisons", stringArray("环比/同比", 0, 2, "previousPeriod", "yearOverYear")),
                        prop("scope", reportScope("已确定计量范围")),
                        prop("grain", stringEnum("分段粒度", "day", "week", "month")))));
    }

    private static Map<String, Object> candidateSet(String description) {
        return object(required("candidateSetId", "deviceIds"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("candidateSetId", string("候选集标识", 1, 300)),
                        prop("deviceIds", stringArray("候选集中的真实设备标识", 1, 50)))));
    }

    private static Map<String, Object> controlAction() {
        return object(required("deviceId", "kind", "selected"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", props(
                        prop("deviceId", string("已查明的真实设备标识", 1, 300)),
                        prop("kind", stringEnum("设备类型；照明只能用 closeOrOpen，空调只能用 ac，门禁只能用 controlType=2",
                                "lighting", "ac", "access")),
                        prop("closeOrOpen", integerEnum("照明：0 关闭、1 打开", 0, 1)),
                        prop("controlType", integerEnum("门禁：一次开门", 2)),
                        prop("ac", acParameters("空调参数；只传用户要求改变的参数")),
                        prop("selected", bool("是否默认勾选该操作")))));
    }

    private static Map<String, Object> acParameters(String description) {
        return object(required(), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("power", stringEnum("电源开关", "on", "off")),
                        prop("temperature", number("设定温度（整数℃，16–30）", 16, 30, true)),
                        prop("mode", stringEnum("运行模式", "cool", "heat", "dry", "fan")),
                        prop("fanSpeed", stringEnum("风速", "low", "high")))));
    }

    private static Map<String, Object> taskAction() {
        return object(required("deviceId"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", props(
                        prop("deviceId", string("已核验的真实设备标识", 1, 300)),
                        prop("closeOrOpen", integerEnum("照明：0 关闭、1 打开", 0, 1)),
                        prop("ac", acParameters("空调参数")))));
    }

    private static Map<String, Object> taskPhase() {
        return object(required("at", "actions"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", props(
                        prop("at", string("带时区的阶段时刻", 1, 64)),
                        prop("actions", arrayOf(taskAction(), 0, 50)),
                        prop("read", object(required(), props(), Map.of("type", "object",
                                "additionalProperties", true, "description", "只读检查要求，与控制动作不能混用"))))));
    }

    private static Map<String, Object> taskInterval(String description) {
        return object(required("startsAt", "endsAt"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("startsAt", string("带时区的区间开始", 1, 64)),
                        prop("endsAt", string("带时区的区间结束", 1, 64)))));
    }

    private static Map<String, Object> repeat(String description) {
        return object(required("frequency"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("frequency", stringEnum("重复频率", "daily", "weekdays", "weekly", "monthly")),
                        prop("weekdays", integerArray("周几，1=周一", 1, 7)),
                        prop("dayOfMonth", integer("每月日期", 1, 31)),
                        prop("until", string("含当天的开始日期截止，YYYY-MM-DD", 1, 32)))));
    }

    private static Map<String, Object> taskTarget(String description) {
        return object(required("type"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("type", stringEnum("目标类型", "task", "task_plan")),
                        prop("taskId", string("一次性任务标识或虚拟本次发生标识", 1, 300)),
                        prop("planId", string("周期计划标识", 1, 300)),
                        prop("occurrenceDate", string("本次发生日期，YYYY-MM-DD", 1, 32)))));
    }

    private static Map<String, Object> meterScope(String description) {
        return object(required("level"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("level", stringEnum("计量范围：全部/整层/单台/已发现集合", "all", "floor", "location", "selection")),
                        prop("floor", string("level=floor 时的真实楼层", 1, 64)),
                        prop("meterId", string("level=location 时的单台电表标识", 1, 300)),
                        prop("meterIds", stringArray("level=selection 时的电表标识集合", 1, 100)))));
    }

    private static Map<String, Object> baseline(String description) {
        return object(required("from", "to"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("from", string("带时区基期起点", 1, 64)),
                        prop("to", string("带时区基期终点", 1, 64)))));
    }

    private static Map<String, Object> dailyWindow(String description) {
        return object(required("from", "to"), Map.of(
                "type", "object",
                "additionalProperties", false,
                "description", description,
                "properties", props(
                        prop("from", stringEnum("每日窗口开始", "20:00")),
                        prop("to", stringEnum("每日窗口结束", "08:00")))));
    }

    private static Map<String, Object> object(List<String> required, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> object(List<String> required, Map<String, Object> properties,
            Map<String, Object> additions) {
        Map<String, Object> schema = new LinkedHashMap<>(additions);
        schema.putIfAbsent("type", "object");
        schema.putIfAbsent("additionalProperties", false);
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> props(Object... values) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Object value : values) {
            Map.Entry<String, Map<String, Object>> entry = (Map.Entry<String, Map<String, Object>>) value;
            properties.put(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    private static Map.Entry<String, Map<String, Object>> prop(String name, Map<String, Object> schema) {
        return Map.entry(name, schema);
    }

    private static Map<String, Object> string(String description, int minLength, Integer maxLength) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        if (minLength > 0) schema.put("minLength", minLength);
        if (maxLength != null) schema.put("maxLength", maxLength);
        return schema;
    }

    private static Map<String, Object> stringEnum(String description, String... values) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        schema.put("enum", List.of(values));
        return schema;
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> integer(String description, int minimum, int maximum) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        schema.put("minimum", minimum);
        schema.put("maximum", maximum);
        return schema;
    }

    private static Map<String, Object> integerEnum(String description, int... values) {
        List<Object> valuesList = new ArrayList<>();
        for (int value : values) valuesList.add(value);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        schema.put("enum", valuesList);
        return schema;
    }

    private static Map<String, Object> number(String description, double minimum, double maximum, boolean integer) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", integer ? "integer" : "number");
        schema.put("description", description);
        schema.put("minimum", minimum);
        schema.put("maximum", maximum);
        return schema;
    }

    private static Map<String, Object> stringArray(String description, int minItems, int maxItems, String... values) {
        Map<String, Object> items = values.length == 0
                ? Map.of("type", "string", "minLength", 1)
                : Map.of("type", "string", "enum", List.of(values));
        return arrayOf(items, minItems, maxItems, description);
    }

    private static Map<String, Object> integerArray(String description, int minimum, int maximum) {
        return arrayOf(Map.of("type", "integer", "minimum", minimum, "maximum", maximum), 1, 7, description);
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items, int minItems, int maxItems) {
        return arrayOf(items, minItems, maxItems, null);
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items, int minItems, int maxItems, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", items);
        if (minItems > 0) schema.put("minItems", minItems);
        if (maxItems > 0) schema.put("maxItems", maxItems);
        if (description != null) schema.put("description", description);
        return schema;
    }

    private static List<String> required(String... names) {
        return List.of(names);
    }

    static List<String> requiredArgumentNames(Definition definition) {
        Object required = definition.parameters().get("required");
        if (!(required instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }

    static Set<String> topLevelArgumentNames(Definition definition) {
        Object properties = definition.parameters().get("properties");
        if (!(properties instanceof Map<?, ?> map)) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (Object key : map.keySet()) names.add(String.valueOf(key));
        return names;
    }
}
