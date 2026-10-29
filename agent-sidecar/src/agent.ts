import { Agent, type AgentEvent, type AgentTool, type StreamFn } from "@earendil-works/pi-agent-core";
import { Type, type Model } from "@earendil-works/pi-ai";
import { streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import type { SidecarConfig } from "./config.js";
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
    reasoning: false,
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 128_000,
    maxTokens: 4_096,
    samplingParams: { instructions: "Use only the supplied business tools when evidence is required." },
    compat: {
      supportsDeveloperRole: true,
      supportsReasoningEffort: false,
      supportsUsageInStreaming: true,
      maxTokensField: "max_tokens",
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

export class AgentRunner {
  constructor(private readonly config: SidecarConfig, private readonly sessions: PiSessionStore) {}

  parseRequest(input: unknown): RunRequest { return validateRequest(input); }

  async run(request: RunRequest, emit: (event: PublicEvent) => void, signal: AbortSignal): Promise<RunOutcome> {
    const session = await this.sessions.openOrCreate(request.conversationId, {
      tenantCode: request.tenantCode,
      source: request.source,
    });
    await session.appendMessage({ role: "user", content: request.content, timestamp: Date.now() });
    const model = modelFor(this.config);
    const state: { confirmation?: Record<string, unknown> } = {};
    const tools = this.createTools(request, signal, emit, state);
    const agent = new Agent({
      initialState: {
        model,
        systemPrompt: "你是一个受控的物联助手。只基于工具返回的事实回答；涉及控制或任务时只能生成待确认草案。使用中文，简洁且不暴露内部凭据。",
        thinkingLevel: "off",
        tools,
      },
      streamFn: streamSimple as StreamFn,
      getApiKey: () => this.config.modelApiKey,
      shouldStopAfterTurn: async () => true,
    });
    const detachAbort = () => agent.abort();
    signal.addEventListener("abort", detachAbort, { once: true });
    const unsubscribe = agent.subscribe(async (event, eventSignal) => {
      this.publishAgentEvent(request, event, emit);
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
      const answer = messages.map(messageText).reverse().find(Boolean);
      if (answer) emit({ type: "message.completed", content: answer, phase: "final" });
      return state.confirmation ? { terminal: "awaiting_confirmation", confirmation: state.confirmation } : { terminal: "completed" };
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
      parameters: Type.Object({}, { additionalProperties: true }),
      execute: async (toolCallId: string, argumentsValue: unknown, toolSignal?: AbortSignal) => {
        const argumentsObject = argumentsValue && typeof argumentsValue === "object" && !Array.isArray(argumentsValue)
          ? argumentsValue as Record<string, unknown>
          : {};
        const result = capability.status === "java"
          ? await this.callJavaTool(request, capability.javaToolName ?? capability.name, argumentsObject, toolCallId, toolSignal ?? signal)
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
    const timer = setTimeout(() => controller.abort(), 30_000);
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
        throw new Error(`Java business tool returned HTTP ${response.status}`);
      }
      return result;
    } finally {
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
    }
  }

  private publishAgentEvent(request: RunRequest, event: AgentEvent, emit: (event: PublicEvent) => void): void {
    switch (event.type) {
      case "message_update": {
        const content = messageText(event.message);
        if (content) emit({ type: "message.delta", content, phase: "draft" });
        break;
      }
      case "tool_execution_start":
        emit({ type: "tool.started", tool: event.toolName, toolCallId: event.toolCallId, arguments: event.args });
        break;
      case "tool_execution_update":
        emit({ type: "tool.progress", tool: event.toolName, toolCallId: event.toolCallId, details: event.partialResult });
        break;
      case "tool_execution_end":
        emit({ type: "tool.completed", tool: event.toolName, toolCallId: event.toolCallId, status: event.isError ? "failed" : "completed" });
        break;
      case "message_end": {
        const content = messageText(event.message);
        if (content) emit({ type: "message.delta", content, phase: "draft" });
        break;
      }
      case "agent_end":
        emit({ type: "run.progress", messageCount: event.messages.length, runId: request.runId });
        break;
      default:
        break;
    }
  }
}

export function parseRunRequest(input: unknown): RunRequest { return validateRequest(input); }
