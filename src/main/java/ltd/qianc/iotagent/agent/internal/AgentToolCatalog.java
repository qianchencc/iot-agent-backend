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
    private static final Definition OFFICE_TASK = proposeTask(false);

    private AgentToolCatalog() { }

    static List<Definition> forSource(String source) {
        requireSource(source);
        return TOOLS.values().stream().filter(tool -> tool.sources().contains(source))
                .map(tool -> "office".equals(source) && "propose_task".equals(tool.name()) ? OFFICE_TASK : tool)
                .toList();
    }

    static Definition find(String source, String name) {
        requireSource(source);
        Definition definition = TOOLS.get(name);
        if (definition == null || !definition.sources().contains(source)) {
            throw new BusinessException("AGENT_TOOL_UNAVAILABLE", "工具未接入或当前来源不可用", 404);
        }
        return "office".equals(source) && "propose_task".equals(name) ? OFFICE_TASK : definition;
    }

    static Definition definition(String name) {
        return TOOLS.get(name);
    }

    private static Definition proposeTask(boolean company) {
        Map<String, Object> properties = props(
                prop("label", string("用动作与范围命名的任务名称", 1, 80)),
                prop("title", string("用户明确指定时的短标题", 1, 20)),
                prop("candidateSetIds", stringArray("已查明的候选集标识", 1, 10)),
                prop("taskKind", stringEnum("任务种类；由用户意图明确时填写",
                        "one_time", "interval", "conditional", "recurring")),
                prop("interval", taskInterval("任务起止区间，仅在用户给出区间时提供")),
                prop("repeat", repeat("用户明确给出的重复规则")),
                prop("checkpointCount", integer("条件/区间检查点数量", 1, 1000)),
                prop("phases", arrayOf(taskPhase(company), 1, 32)));
        if (company) properties.put("reportRequest",
                reportRequest("可选声明式报告要求；服务器取证，禁止 blocks/totals"));
        String scope = company
                ? "公司照明/空调每阶段用targets声明allAuthorized全部当前授权成员、scopeTexts地点及excludeTexts保留，或selection精确选择；服务器展开完整固定成员、核验和预检，不受模型明细窗口限制，不逐台抄大集合。targets与actions/read互斥。只读检查全范围必须显式read.all=true，缺设备列表不默认全部。"
                : "办公室每阶段使用actions逐项声明已查明设备的deviceId、kind和command，并提供对应candidateSetIds；范围为当前区域全部时也要包含该区域全部已查明成员，不因当前已满足而遗漏未来目标。只读检查使用read.deviceIds明确成员。办公室不使用公司范围targets、read.all或报告要求。";
        return definition("propose_task", "编排定时任务",
                "准备待人工确认的一次性或周期任务，不执行控制。" + scope
                        + "窗口条件检查同时给interval与cadenceMinutes。只保留用户明确要求的阶段、范围与重复规则，未来目标保留当前已满足设备；时间按本轮current_time固定为带时区绝对值。",
                object(required("label", "phases"), properties), BOTH, Confirmation.TASK);
    }

    private static Map<String, Definition> build() {
        Map<String, Definition> tools = new LinkedHashMap<>();
        add(tools, definition("list_office_regions", "查询可用办公室区域",
                "只读查询当前账号真实有权选择的办公室关注区域；可按名称包含的文字筛选。回答可选区域，不切换区域，不创建任务。当前区域仍由用户界面选择。",
                object(required(), props(prop("nameContains", string("可选：区域名称包含的非空文字", 1, 120)))),
                OFFICE, Confirmation.NONE));
        add(tools, definition("find_device_candidates", "查询设备候选",
                "按当前来源、当前关注区域和当前授权目录检索设备候选。位置条件放 scopeText/scopeTerms，设备名称或型号放 deviceText/nameHints；不要把地点拼进设备名称。返回真实候选及 candidateSetId；候选不是用户已选择的操作范围，空候选必须如实处理，不得发明设备。",
                object(required("kind"), props(
                        prop("kind", stringEnum("设备类型", "lighting", "ac", "access", "video", "environment", "energy", "safety", "unknown")),
                        prop("scopeText", string("用户明确的位置或区域描述；全公司范围用公司，不附加照明等设备词；当前区域由服务端绑定，不传内部focusRegionId", 1, 120)),
                        prop("deviceText", string("设备名称或型号片段", 1, 80)),
                        prop("scopeTerms", stringArray("用户明确要求同时满足的位置条件（AND）；不生成同义词、楼层写法或改写变体。单一地点只填scopeText", 0, 8)),
                        prop("nameHints", stringArray("设备名称候选词", 0, 8)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("get_device_details", "读取设备详情",
                "补读一个已有候选集内明确设备句柄的名称与位置，返回精确候选集供状态、能力和提案引用。只读，不能读取候选集外设备，也不能替用户选择尚有歧义的对象。",
                object(required("candidateSetId", "deviceIds"), props(
                        prop("candidateSetId", string("已有候选集标识", 1, null)),
                        prop("deviceIds", stringArray("候选集内设备标识", 1, 50)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("read_device_states", "查询设备状态",
                "批量读取当前授权候选设备的真实状态和观测时间。用户明确要求完整候选范围时，每组传all=true由服务器展开全部固定成员，不受前50条展示限制；精确选择则传deviceIds，二者不可混用。照明返回开关，空调返回参数，视频返回在线态，环境/能耗/安全返回只读观察事实。未知不是关闭；历史观测不代表当前状态；查询不产生控制或草案。",
                object(required("candidateSets"), props(
                        prop("candidateSets", arrayOf(candidateSet("候选集及其中的真实设备标识"), 1, 10)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("inspect_device_capabilities", "查询设备能力",
                "查询一个或多个候选集中设备实际支持的原子操作。完整范围用all=true由服务器展开；精确选择用deviceIds，二者不可混用。只读，不生成草案，也不控制设备。",
                object(required("candidateSets"), props(
                        prop("candidateSets", arrayOf(candidateSet("待核验能力的候选集"), 1, 10)))),
                BOTH, Confirmation.NONE));
        add(tools, definition("propose_action_draft", "生成操作草案",
                "基于已查明候选集生成一份待人工确认草案。candidateSetIds界定唯一成员宇宙。默认actions仅操作逐台明确选择的成员，不扩成整个候选集；只有用户明确要求完整范围统一操作时传expandCandidates=true，此时可每种设备给一个代表命令，由服务器展开该类型全部成员。地点排除须先取得exact真实子集，通过excludeCandidateSetIds整体相减，不逐台抄写、不以模糊命中代替。每个动作只传对应参数：照明closeOrOpen、空调ac、门禁controlType=2。服务器重核授权、范围、排除及状态；不会下发控制，模型不能确认。",
                object(required("summary", "candidateSetIds", "actions"), props(
                        prop("candidateSetIds", stringArray("已查明的完整候选集标识；草案只在这些服务器成员内展开", 1, 10)),
                        prop("expandCandidates", bool("仅用户明确要求完整候选范围统一操作时true；省略或false只操作actions逐台明确成员")),
                        prop("excludeCandidateSetIds", stringArray("已精确查明且完全属于包含范围的排除子集，合并去重后整体相减", 1, 10)),
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
                "查询本端当前授权安全设备的烟感/水浸/燃气历史台账（平台记录，不是实时报警）。Office仅在目录实际接入安全设备时开放，不借公司目录扩大范围。report仅为平台上报标记，不代表处置或恢复；台账没有当前设备态证据。单类读取失败时保留其他类别并标明覆盖缺口。",
                object(required(), props(
                        prop("types", stringArray("告警类型：smoke、water、gas", 1, 3, "smoke", "water", "gas")),
                        prop("days", integer("回看天数，缺省 30", 1, 365)),
                        prop("from", string("带时区的窗口起点；仅与 to 成对使用", 1, 64)),
                        prop("to", string("带时区的窗口终点；不晚于当前时间", 1, 64)),
                        prop("unreportedOnly", bool("只看平台标记未上报的记录")))),
                BOTH, Confirmation.NONE));
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
        add(tools, proposeTask(true));
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
        return object(required("kind"), props(
                prop("kind", stringEnum("期间类型", "absolute", "previous")),
                prop("from", string("absolute 期间的带时区 ISO 起点", 1, 64)),
                prop("to", string("absolute 期间的带时区 ISO 终点", 1, 64)),
                prop("unit", stringEnum("previous 期间的日历单位", "day", "week", "month"))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> reportScope(String description) {
        return object(required("level"), props(
                prop("level", stringEnum("已确定范围类型", "all", "floor", "location", "nameMatch", "selection")),
                prop("floor", string("明确整层的真实楼层", 1, 64)),
                prop("text", string("已从当前目录确认的地点描述", 1, 120)),
                prop("meterId", string("单台电表标识", 1, 300)),
                prop("meterIds", stringArray("已核验的电表标识集合", 1, 100))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> reportRequest(String description) {
        return object(required("kind", "period"), props(
                prop("kind", stringEnum("报告类型", "energy", "safety")),
                prop("title", string("可选标题", 1, 100)),
                prop("period", reportPeriod("报告期间")),
                prop("comparisons", stringArray("环比/同比", 0, 2, "previousPeriod", "yearOverYear")),
                prop("scope", reportScope("已确定计量范围")),
                prop("grain", stringEnum("分段粒度", "day", "week", "month"))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> candidateSet(String description) {
        return object(required("candidateSetId"), props(
                prop("candidateSetId", string("候选集标识", 1, 300)),
                prop("all", bool("仅用户明确要求完整候选范围时true；服务器展开该候选集全部固定成员，不与deviceIds并用")),
                prop("deviceIds", stringArray("精确选择的真实设备标识；与all=true二选一，省略不代表全部", 1, 50))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> controlAction() {
        return object(required("deviceId", "kind", "selected"), props(
                prop("deviceId", string("已查明的真实设备标识", 1, 300)),
                prop("kind", stringEnum("设备类型；照明只能用 closeOrOpen，空调只能用 ac，门禁只能用 controlType=2",
                        "lighting", "ac", "access")),
                prop("closeOrOpen", integerEnum("照明：0 关闭、1 打开", 0, 1)),
                prop("controlType", integerEnum("门禁：一次开门", 2)),
                prop("ac", acParameters("空调参数；只传用户要求改变的参数")),
                prop("selected", bool("是否默认勾选该操作"))), Map.of("type", "object",
        "additionalProperties", false));
    }

    private static Map<String, Object> acParameters(String description) {
        return object(required(), props(
                prop("power", stringEnum("电源开关", "on", "off")),
                prop("temperature", number("设定温度（整数℃，16–30）", 16, 30, true)),
                prop("mode", stringEnum("运行模式", "cool", "heat", "dry", "fan")),
                prop("fanSpeed", stringEnum("风速", "low", "high"))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> taskAction() {
        return object(required("deviceId"), props(
                prop("deviceId", string("已核验的真实设备标识", 1, 300)),
                prop("closeOrOpen", integerEnum("照明：0 关闭、1 打开", 0, 1)),
                prop("ac", acParameters("空调参数"))), Map.of("type", "object",
        "additionalProperties", false));
    }

    private static Map<String, Object> taskPhase(boolean company) {
        Map<String, Object> properties = props(
                prop("at", string("带时区的阶段时刻；无区间允许1–2个独立阶段", 1, 64)),
                prop("actions", arrayOf(taskAction(), 0, 50)),
                prop("read", taskRead(company)));
        if (company) properties.put("targets", arrayOf(companyControlTarget(), 1, 20));
        return object(required("at"), properties, Map.of("type", "object", "additionalProperties", false));
    }

    private static Map<String, Object> taskRead(boolean company) {
        Map<String, Object> properties = props(
                prop("deviceIds", stringArray("已明确选择的设备；不得遗漏所选范围成员", 1, 50)),
                prop("alertDays", integer("近N天滚动告警窗口；不与alertWindow混用", 1, 365)),
                prop("alertWindow", stringEnum("以阶段计划at的上海自然日期锚定，右端不含", "previous_day", "previous_week")),
                prop("ac", object(required("mode"), props(
                        prop("mode", stringEnum("inspect仅只读评估，condition/target不是控制；adjust_to_26且有action才按条件调温", "inspect", "adjust_to_26")),
                        prop("target", integer("目标设定温度，不是室温；缺省26", 16, 30)),
                        prop("cadenceMinutes", integerEnum("窗口检查频率；用户未给出先澄清，不补造", 15, 30, 60))))),
                prop("agent", object(required("objective", "output"), props(
                        prop("objective", string("已确认的到时只读指令；确认前不取数或执行", 1, 2000)),
                        prop("output", stringEnum("result只保存结果；report另保存报告附件", "result", "report")),
                        prop("spec", object(required("period", "comparisons"), props(
                                prop("period", reportPeriod("按检查点计划时间解析的统计期间")),
                                prop("comparisons", stringArray("明确要求的比较项，不要求则空数组", 0, 2,
                                        "previousPeriod", "yearOverYear")))))))));
        if (company) properties.put("all", Map.of("type", "boolean", "const", true,
                "description", "显式读取当前授权范围该类型的全部固定成员；与deviceIds互斥，不含将来新增"));
        return object(required(), properties);
    }

    private static Map<String, Object> companyControlTarget() {
        return object(required("kind", "command"), props(
                prop("kind", stringEnum("控制种类", "lighting", "ac")),
                prop("command", object(required(), props(
                        prop("closeOrOpen", integerEnum("照明开关", 0, 1)),
                        prop("power", stringEnum("空调电源；不隐含设温或开机", "on", "off")),
                        prop("temperature", number("整数设温", 16, 30, true)),
                        prop("mode", stringEnum("空调模式", "cool", "heat", "dry", "fan")),
                        prop("fanSpeed", stringEnum("空调风速", "low", "high"))))),
                prop("allAuthorized", bool("用户明确要求该类型全部公司授权成员时为true；与scopeTexts/selection互斥")),
                prop("scopeTexts", stringArray("每个具体地点，服务器取成员并集；公司/全部不是地点，应使用allAuthorized=true；与allAuthorized/selection互斥", 1, 8)),
                prop("excludeTexts", stringArray("包含范围内明确保留地点，无法核验不忽略", 0, 8)),
                prop("excludeCandidateSetIds", stringArray("已查明exact排除子集；可用scopeTerms先查交集，不搬运逐台成员；须完全属于本阶段包含范围", 1, 10)),
                prop("selection", object(required("candidateSetId", "deviceIds"), props(
                        prop("candidateSetId", string("已明确的候选集", 1, 300)),
                        prop("deviceIds", stringArray("仅精确选择这些成员，不扩大范围", 1, 50)))))));
    }

    private static Map<String, Object> taskInterval(String description) {
        return object(required("startsAt", "endsAt"), props(
                prop("startsAt", string("带时区的区间开始", 1, 64)),
                prop("endsAt", string("带时区的区间结束", 1, 64))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> repeat(String description) {
        return object(required("frequency"), props(
                prop("frequency", stringEnum("重复频率", "daily", "weekdays", "weekly", "monthly")),
                prop("weekdays", integerArray("周几，1=周一", 1, 7)),
                prop("dayOfMonth", integer("每月日期", 1, 31)),
                prop("until", string("含当天的开始日期截止，YYYY-MM-DD", 1, 32))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> taskTarget(String description) {
        return object(required("type"), props(
                prop("type", stringEnum("目标类型", "task", "task_plan")),
                prop("taskId", string("一次性任务标识或虚拟本次发生标识", 1, 300)),
                prop("planId", string("周期计划标识", 1, 300)),
                prop("occurrenceDate", string("本次发生日期，YYYY-MM-DD", 1, 32))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> meterScope(String description) {
        return object(required("level"), props(
                prop("level", stringEnum("计量范围：全部/整层/单台/已发现集合", "all", "floor", "location", "selection")),
                prop("floor", string("level=floor 时的真实楼层", 1, 64)),
                prop("meterId", string("level=location 时的单台电表标识", 1, 300)),
                prop("meterIds", stringArray("level=selection 时的电表标识集合", 1, 100))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> baseline(String description) {
        return object(required("from", "to"), props(
                prop("from", string("带时区基期起点", 1, 64)),
                prop("to", string("带时区基期终点", 1, 64))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
    }

    private static Map<String, Object> dailyWindow(String description) {
        return object(required("from", "to"), props(
                prop("from", stringEnum("每日窗口开始", "20:00")),
                prop("to", stringEnum("每日窗口结束", "08:00"))), Map.of("type", "object",
        "additionalProperties", false,
        "description", description));
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
