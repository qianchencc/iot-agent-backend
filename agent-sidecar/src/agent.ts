import { Agent, type AgentEvent, type AgentTool, type StreamFn } from "@earendil-works/pi-agent-core";
import { Type, type Model, type TSchema } from "@earendil-works/pi-ai";
import { streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import type { SidecarConfig } from "./config.js";
import { traceStreamFn, type Observability } from "./observability.js";
import type { PiSessionStore } from "./session.js";

export interface RunRequest {
  runId: string;
  toolContextId: string;
  conversationId: string;
  turnId: string;
  source: string;
  content: string;
  tenantCode: string;
  focusRegionId?: string;
  reference?: Record<string, unknown>;
}

export interface PublicEvent {
  type: string;
  [key: string]: unknown;
}

export type ToolCapabilityStatus = "java" | "unavailable";
export type ToolCapabilityCategory = "observation" | "report" | "action" | "task" | "confirmation" | "resolution" | "readback";

export interface ToolCapability {
  name: string;
  category: ToolCapabilityCategory;
  status: ToolCapabilityStatus;
  javaToolName?: string;
  event: "observation.reported" | "report.generated" | "action.proposed" | "action.resolved" | "task.proposed" | "task.resolved"
    | "task.confirmation" | "task.change.proposed" | "task.change.resolved" | "task.cancellation.proposed" | "task.cancellation.resolved";
}

/**
 * The sidecar exposes the full contract so model/tool negotiation remains stable.
 * Java-backed entries are the only entries allowed to claim business success.
 */
export const TOOL_CAPABILITY_MATRIX: readonly ToolCapability[] = [
  { name: "find_device_candidates", category: "observation", status: "java", event: "observation.reported" },
  { name: "read_device_states", category: "observation", status: "java", event: "observation.reported" },
  { name: "inspect_device_capabilities", category: "observation", status: "java", event: "observation.reported" },
  { name: "read_device_readback", category: "readback", status: "java", javaToolName: "read_device_states", event: "observation.reported" },
  { name: "readback_device_states", category: "readback", status: "java", javaToolName: "read_device_states", event: "observation.reported" },
  { name: "get_device_details", category: "readback", status: "java", event: "observation.reported" },
  { name: "query_alerts", category: "observation", status: "java", event: "observation.reported" },
  { name: "query_environment_history", category: "observation", status: "java", event: "observation.reported" },
  { name: "query_energy_history", category: "observation", status: "java", event: "observation.reported" },
  { name: "prepare_video_playback", category: "readback", status: "unavailable", event: "observation.reported" },
  { name: "review_safety_event", category: "observation", status: "java", event: "observation.reported" },
  { name: "summarize_energy", category: "report", status: "java", event: "report.generated" },
  { name: "compare_energy", category: "report", status: "java", event: "report.generated" },
  { name: "create_energy_report", category: "report", status: "java", event: "report.generated" },
  { name: "create_report", category: "report", status: "java", javaToolName: "create_energy_report", event: "report.generated" },
  { name: "generate_report", category: "report", status: "java", javaToolName: "create_energy_report", event: "report.generated" },
  { name: "propose_action_draft", category: "action", status: "java", event: "action.proposed" },
  { name: "propose_lighting_draft", category: "action", status: "java", javaToolName: "propose_action_draft", event: "action.proposed" },
  { name: "confirm_action", category: "confirmation", status: "java", event: "action.resolved" },
  { name: "resolve_action", category: "resolution", status: "java", event: "action.resolved" },
  { name: "read_action_draft", category: "readback", status: "java", event: "action.resolved" },
  { name: "read_action_device_states", category: "readback", status: "java", event: "action.resolved" },
  { name: "read_action_summary", category: "readback", status: "java", event: "action.resolved" },
  { name: "propose_task", category: "task", status: "java", event: "task.proposed" },
  { name: "propose_task_change", category: "task", status: "java", event: "task.change.proposed" },
  { name: "propose_task_cancellation", category: "task", status: "java", event: "task.cancellation.proposed" },
  { name: "propose_company_night_closure", category: "task", status: "unavailable", event: "task.proposed" },
  { name: "continue_company_night_closure", category: "task", status: "unavailable", event: "task.proposed" },
  { name: "list_tasks", category: "readback", status: "java", event: "task.resolved" },
  { name: "list_task_plans", category: "readback", status: "java", event: "task.resolved" },
  { name: "get_task", category: "readback", status: "java", event: "task.resolved" },
  { name: "confirm_task", category: "confirmation", status: "java", event: "task.confirmation" },
  { name: "confirm_task_change", category: "confirmation", status: "java", event: "task.change.resolved" },
  { name: "confirm_task_cancellation", category: "confirmation", status: "java", event: "task.cancellation.resolved" },
  { name: "resolve_task", category: "resolution", status: "unavailable", event: "task.resolved" },
  { name: "resolve_task_change", category: "resolution", status: "unavailable", event: "task.change.resolved" },
  { name: "resolve_task_cancellation", category: "resolution", status: "unavailable", event: "task.cancellation.resolved" },
];


function modelFor(config: SidecarConfig): Model<"openai-completions"> {
  return {
    id: config.modelId,
    name: config.modelId,
    api: "openai-completions",
    provider: "iot-agent-sidecar",
    baseUrl: config.modelBaseUrl,
    reasoning: true,
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 128_000,
    maxTokens: 16_384,
    samplingParams: {
      instructions: "Use IoT tools only when they provide evidence needed for the current request. Answer, ask for needed information, or create a confirmable draft, then stop.",
    },
    compat: {
      supportsDeveloperRole: false,
      supportsReasoningEffort: true,
      supportsUsageInStreaming: true,
      maxTokensField: "max_completion_tokens",
    },
  };
}

function messageText(message: unknown): string {
  if (!message || typeof message !== "object") return "";
  const content = (message as { content?: unknown }).content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.filter(item => item && typeof item === "object" && (item as { type?: unknown }).type === "text")
    .map(item => String((item as { text?: unknown }).text ?? "")).join("");
}

function jsonText(value: unknown): string {
  try { return JSON.stringify(value); } catch { return "[unserializable]"; }
}

function validateRequest(input: unknown): RunRequest {
  if (!input || typeof input !== "object" || Array.isArray(input)) throw new Error("request body must be an object");
  const body = input as Record<string, unknown>;
  const text = (name: string): string => {
    const value = body[name];
    if (typeof value !== "string" || value.trim() === "") throw new Error(`${name} is required`);
    if (value.length > 16_000) throw new Error(`${name} is too long`);
    return value;
  };
  const result: RunRequest = {
    runId: text("runId"), toolContextId: text("toolContextId"), conversationId: text("conversationId"),
    turnId: text("turnId"), source: text("source"), content: text("content"), tenantCode: text("tenantCode"),
  };
  if (typeof body.focusRegionId === "string") result.focusRegionId = body.focusRegionId;
  if (body.reference && typeof body.reference === "object" && !Array.isArray(body.reference)) result.reference = body.reference as Record<string, unknown>;
  return result;
}

export interface RunOutcome {
  terminal: "completed" | "awaiting_confirmation";
  confirmation?: Record<string, unknown>;
}

function unsupportedResult(capability: ToolCapability): Record<string, unknown> {
  return {
    ok: false,
    status: "unavailable",
    supported: false,
    code: "AGENT_TOOL_UNAVAILABLE",
    toolName: capability.name,
    capability: capability.category,
    message: "Agent capability is unavailable",
  };
}

function isPendingConfirmation(value: unknown): boolean {
  if (!value || typeof value !== "object") return false;
  const status = String((value as { status?: unknown }).status ?? "").toUpperCase();
  return status === "PENDING_CONFIRMATION" || status === "AWAITING_CONFIRMATION" || status === "AWAITING_CONFIRM";
}

const DEVICE_KIND = Type.Union([
  Type.Literal("lighting"), Type.Literal("ac"), Type.Literal("access"), Type.Literal("video"),
  Type.Literal("environment"), Type.Literal("energy"), Type.Literal("safety"), Type.Literal("unknown"),
]);

const CANDIDATE_SET = Type.Object({
  candidateSetId: Type.String({ minLength: 1 }),
  deviceIds: Type.Array(Type.String({ minLength: 1 }), { minItems: 1, maxItems: 50 }),
}, { additionalProperties: false });

const CONTROL_ACTION = Type.Object({
  deviceId: Type.String({ minLength: 1 }),
  kind: Type.Union([
    Type.Literal("lighting", { description: "照明；必须使用 closeOrOpen" }),
    Type.Literal("ac", { description: "空调；必须使用 ac" }),
    Type.Literal("access", { description: "门禁；必须使用 controlType=2" }),
  ]),
  closeOrOpen: Type.Optional(Type.Union([Type.Literal(0), Type.Literal(1)], {
    description: "仅照明：0关闭、1打开；不可与 controlType 或 ac 混用",
  })),
  controlType: Type.Optional(Type.Literal(2, { description: "仅门禁：一次开门；照明不可使用" })),
  ac: Type.Optional(Type.Object({
    power: Type.Optional(Type.Union([Type.Literal("on"), Type.Literal("off")])),
    temperature: Type.Optional(Type.Number({ minimum: 16, maximum: 30 })),
    mode: Type.Optional(Type.Union([Type.Literal("cool"), Type.Literal("heat"), Type.Literal("dry"), Type.Literal("fan")])),
    fanSpeed: Type.Optional(Type.Union([Type.Literal("low"), Type.Literal("high")])),
  }, { additionalProperties: false, description: "仅空调；只传用户要求改变的参数" })),
  selected: Type.Boolean(),
}, { additionalProperties: false });

function toolParameters(name: string): TSchema {
  switch (name) {
    case "find_device_candidates":
      return Type.Object({
        kind: DEVICE_KIND,
        scopeText: Type.Optional(Type.String({ minLength: 1, maxLength: 120 })),
        deviceText: Type.Optional(Type.String({ minLength: 1, maxLength: 80 })),
        scopeTerms: Type.Optional(Type.Array(Type.String({ minLength: 1 }), { minItems: 1, maxItems: 8 })),
        nameHints: Type.Optional(Type.Array(Type.String({ minLength: 1 }), { maxItems: 8 })),
      }, { additionalProperties: false });
    case "get_device_details":
      return Type.Object({
        candidateSetId: Type.String({ minLength: 1 }),
        deviceIds: Type.Array(Type.String({ minLength: 1 }), { minItems: 1, maxItems: 50 }),
      }, { additionalProperties: false });
    case "read_device_states":
    case "inspect_device_capabilities":
      return Type.Object({ candidateSets: Type.Array(CANDIDATE_SET, { minItems: 1, maxItems: 10 }) },
        { additionalProperties: false });
    case "propose_action_draft":
      return Type.Object({
        candidateSetIds: Type.Array(Type.String({ minLength: 1 }), { maxItems: 10 }),
        summary: Type.String({ minLength: 1 }),
        question: Type.Optional(Type.String({ minLength: 1 })),
        excludedDeviceIds: Type.Optional(Type.Array(Type.String({ minLength: 1 }), { maxItems: 50 })),
        actions: Type.Array(CONTROL_ACTION, { minItems: 1, maxItems: 50 }),
      }, { additionalProperties: false });
    case "confirm_action":
      return Type.Object({
        draftId: Type.String({ minLength: 1 }),
        expectedRevision: Type.Optional(Type.Number({ minimum: 1 })),
        revision: Type.Optional(Type.Number({ minimum: 1 })),
      }, { additionalProperties: false });
    case "resolve_action":
      return Type.Object({ draftId: Type.String({ minLength: 1 }) }, { additionalProperties: false });
    case "query_alerts":
      return Type.Object({
        types: Type.Optional(Type.Array(Type.Union([Type.Literal("smoke"), Type.Literal("water"), Type.Literal("gas")]), { minItems: 1, maxItems: 3 })),
        days: Type.Optional(Type.Number({ minimum: 1, maximum: 365 })),
        from: Type.Optional(Type.String()),
        to: Type.Optional(Type.String()),
        unreportedOnly: Type.Optional(Type.Boolean()),
      }, { additionalProperties: false });
    case "review_safety_event":
      return Type.Object({
        eventId: Type.String({ minLength: 1 }),
        deviceId: Type.String({ minLength: 1 }),
        ledger: Type.Union([Type.Literal("gas"), Type.Literal("smoke"), Type.Literal("flooding")]),
        cameraDeviceId: Type.Optional(Type.String({ minLength: 1 })),
      }, { additionalProperties: false });
    default:
      return Type.Object({}, { additionalProperties: true });
  }
}

export class AgentRunner {
  constructor(
    private readonly config: SidecarConfig,
    private readonly sessions: PiSessionStore,
    private readonly observability: Observability,
  ) {}

  parseRequest(input: unknown): RunRequest { return validateRequest(input); }

  async run(request: RunRequest, emit: (event: PublicEvent) => void, signal: AbortSignal): Promise<RunOutcome> {
    const session = await this.sessions.openOrCreate(request.conversationId, {
      tenantCode: request.tenantCode,
      source: request.source,
    });
    await session.appendMessage({ role: "user", content: request.content, timestamp: Date.now() });
    const model = modelFor(this.config);
    const trace = this.observability.startAgentRun({
      conversationId: request.conversationId,
      tenantCode: request.tenantCode,
      content: request.content,
    });
    const observedEmit = (event: PublicEvent) => {
      trace?.observeEvent(event);
      emit(event);
    };
    const state: { confirmation?: Record<string, unknown>; heldAssistantText: string; draftPublished: boolean } =
      { heldAssistantText: "", draftPublished: false };
    let agentTurns = 0;
    const tools = this.createTools(request, signal, observedEmit, state);
    const agent = new Agent({
      initialState: {
        model,
        systemPrompt: "你是一个受控的物联助手。只基于工具返回的事实回答。设备控制必须严格按：find_device_candidates 得到非空候选集 → read_device_states 使用候选集中的真实 candidateSetId/deviceIds → 仅在用户明确要求且设备可控时生成 propose_action_draft 待确认草案；不得发明设备ID、candidateSetId或kind，不得把空候选当作成功。控制动作每项必须显式填写 kind：lighting 只能使用 closeOrOpen，ac 只能使用 ac，access 只能使用 controlType=2，禁止混用；候选为空时先修正检索词或向用户澄清；Company来源没有可控目录时如实说明不可用。检索时scopeText/scopeTerms只放地点条件，deviceText/nameHints只放设备名称或型号，不要把地点拼进设备名称。任何控制都不能在草案阶段直接执行。使用中文，简洁且不暴露内部凭据。",
        thinkingLevel: this.config.thinkingLevel,
        tools,
      },
      streamFn: traceStreamFn(streamSimple as StreamFn, trace),
      getApiKey: () => this.config.modelApiKey,
      shouldStopAfterTurn: async ({ toolResults }) =>
        toolResults.length === 0 || Boolean(state.confirmation) || ++agentTurns >= 8,
    });
    const detachAbort = () => agent.abort();
    signal.addEventListener("abort", detachAbort, { once: true });
    const unsubscribe = agent.subscribe(async (event, eventSignal) => {
      this.publishAgentEvent(request, event, emit, state, trace ? observedEvent => trace.observeEvent(observedEvent) : undefined);
      if (eventSignal.aborted) return;
    });
    try {
      await agent.prompt(request.content);
      const messages = agent.state.messages.slice(-4);
      for (const message of messages) {
        const serialized = JSON.stringify(message);
        if (!serialized) continue;
        await session.appendMessage(JSON.parse(serialized));
      }
      // Runtime契约：终稿只取助手消息；用户消息文本回显会让前端把输入当答复。
      const answer = messages
        .filter(message => message?.role === "assistant")
        .map(messageText).reverse().find(Boolean) || state.heldAssistantText;
      if (answer) observedEmit({ type: "message.completed", content: answer, phase: "final" });
      const outcome = state.confirmation
        ? { terminal: "awaiting_confirmation" as const, confirmation: state.confirmation }
        : { terminal: "completed" as const };
      trace?.finish({ result: outcome });
      void this.observability.flush().catch(() => undefined);
      return outcome;
    } catch (error) {
      trace?.finish({ error });
      void this.observability.flush().catch(() => undefined);
      throw error;
    } finally {
      unsubscribe();
      signal.removeEventListener("abort", detachAbort);
    }
  }

  private createTools(
    request: RunRequest,
    signal: AbortSignal,
    emit: (event: PublicEvent) => void,
    state: { confirmation?: Record<string, unknown> },
  ): AgentTool[] {
    return TOOL_CAPABILITY_MATRIX.map(capability => ({
      name: capability.name,
      label: capability.name,
      description: capability.status === "java"
        ? `调用 Java 业务工具 ${capability.javaToolName ?? capability.name} 获取授权范围内事实或生成待确认草案。`
        : `能力 ${capability.name} 当前不可用；必须如实返回 AGENT_TOOL_UNAVAILABLE，不得伪造业务成功。`,
      parameters: toolParameters(capability.name),
      execute: async (toolCallId: string, argumentsValue: unknown, toolSignal?: AbortSignal) => {
        const argumentsObject = argumentsValue && typeof argumentsValue === "object" && !Array.isArray(argumentsValue)
          ? argumentsValue as Record<string, unknown>
          : {};
        const result = capability.status === "java"
          ? await this.callJavaTool(request, capability.javaToolName ?? capability.name,
              { ...argumentsObject, source: request.source }, toolCallId, toolSignal ?? signal)
          : unsupportedResult(capability);
        emit({ type: capability.event, tool: capability.name, details: result });
        if (capability.status === "java" && isPendingConfirmation(result)) {
          state.confirmation = { tool: capability.name, details: result };
        }
        return { content: [{ type: "text", text: jsonText(result) }], details: result };
      },
    }));
  }

  private async callJavaTool(request: RunRequest, toolName: string, argumentsValue: Record<string, unknown>, toolCallId: string, signal: AbortSignal): Promise<unknown> {
    const controller = new AbortController();
    const onAbort = () => controller.abort();
    signal.addEventListener("abort", onAbort, { once: true });
    const timer = setTimeout(() => controller.abort(), this.config.javaToolTimeoutMs);
    try {
      const response = await fetch(this.config.javaToolsUrl, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          accept: "application/json",
          "X-Agent-Bridge-Token": this.config.javaToolsToken,
          authorization: `Bearer ${this.config.javaToolsToken}`,
        },
        body: JSON.stringify({
          toolCallId,
          toolContextId: request.toolContextId,
          conversationId: request.conversationId,
          turnId: request.turnId,
          toolName,
          arguments: argumentsValue,
        }),
        signal: controller.signal,
      });
      const result = await response.json().catch(() => null);
      if (!response.ok) {
        if (result && typeof result === "object" && "code" in result) return result;
        return {
          ok: false,
          status: "unavailable",
          supported: false,
          code: "AGENT_TOOL_UNAVAILABLE",
          toolName,
          message: `Java business tool returned HTTP ${response.status}`,
        };
      }
      return result;
    } catch (error) {
      if (controller.signal.aborted && !signal.aborted) {
        return {
          ok: false,
          status: "unavailable",
          supported: false,
          code: "AGENT_TOOL_TIMEOUT",
          toolName,
          message: "Java business tool timed out",
        };
      }
      return {
        ok: false,
        status: "unavailable",
        supported: false,
        code: "AGENT_TOOL_UNAVAILABLE",
        toolName,
        message: "Java business tool unavailable",
      };
    } finally {
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
    }
  }

  private publishAgentEvent(
    request: RunRequest,
    event: AgentEvent,
    emit: (event: PublicEvent) => void,
    state: { heldAssistantText: string; draftPublished: boolean },
    observe?: (event: PublicEvent) => void,
  ): void {
    const publish = (value: PublicEvent) => {
      observe?.(value);
      emit(value);
    };
    switch (event.type) {
      case "message_update": {
        // Runtime contract: message.delta carries incremental text only. The SDK emits
        // message_update with assistantMessageEvent partials for every assistant event;
        // forwarding messageText(partial) replays the cumulative snapshot and the client
        // appends it, which renders quadratic garbage. Emit only the text_delta slice.
        if (event.assistantMessageEvent?.type === "text_delta") {
          const delta = event.assistantMessageEvent.delta;
          if (delta) {
            state.heldAssistantText += delta;
            state.draftPublished = true;
            publish({ type: "message.delta", content: delta, phase: "draft" });
          }
        }
        break;
      }
      case "tool_execution_start":
        publish({ type: "tool.started", tool: event.toolName, toolCallId: event.toolCallId, arguments: event.args });
        break;
      case "tool_execution_update":
        publish({ type: "tool.progress", tool: event.toolName, toolCallId: event.toolCallId, details: event.partialResult });
        break;
      case "tool_execution_end":
        publish({ type: "tool.completed", tool: event.toolName, toolCallId: event.toolCallId, status: event.isError ? "failed" : "completed" });
        break;
      case "message_end": {
        // Only assistant messages carry public text; forwarding tool-result/user ends
        // echoes the caller's input back as a draft delta.
        if (event.message.role !== "assistant") break;
        const hasToolCall = event.message.content.some(block => block.type === "toolCall");
        if (hasToolCall && state.draftPublished) {
          // 草稿随工具调用转为过程证据，前端折叠到处理记录而不是保留正文。
          publish({ type: "message.reclassified", to: "process", content: state.heldAssistantText });
        } else if (!hasToolCall && state.heldAssistantText && !state.draftPublished) {
          publish({ type: "message.delta", content: state.heldAssistantText, phase: "final" });
        }
        state.heldAssistantText = "";
        state.draftPublished = false;
        break;
      }
      case "agent_end":
        publish({ type: "run.progress", messageCount: event.messages.length, runId: request.runId });
        break;
      default:
        break;
    }
  }
}

export function parseRunRequest(input: unknown): RunRequest { return validateRequest(input); }
