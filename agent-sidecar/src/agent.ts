import {
  Agent,
  SessionError,
  FileError,
  estimateTokens,
  type AgentEvent,
  type AgentMessage,
  type AgentTool,
  type StreamFn,
} from "@earendil-works/pi-agent-core";
import type { Model, TSchema } from "@earendil-works/pi-ai";
import { streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import type { SidecarConfig } from "./config.js";
import { traceStreamFn, type Observability } from "./observability.js";
import type { PiSessionRun, PiSessionStore } from "./session.js";
import { createReplyTextFilter, publicReplyText, type ReplyTextFilter } from "./reply-text.js";
import { loadAgentHistory } from "./history.js";

/** Plain JSON Schema supplied by Java. The sidecar never interprets business meaning. */
export type JsonSchema = Record<string, unknown>;

export interface ToolDefinition {
  name: string;
  label: string;
  description: string;
  parameters: JsonSchema;
}

export interface ModelSettings {
  modelId: string;
  modelBaseUrl: string;
  modelApiKey: string;
  contextWindow: number;
  maxOutputTokens: number;
  reasoning: boolean;
  thinkingLevel: "off" | "low" | "medium" | "high";
  supportsDeveloperRole: boolean;
  supportsReasoningEffort: boolean;
  supportsUsageInStreaming: boolean;
  maxTokensField: "max_completion_tokens" | "max_tokens";
}

export interface RunDefinition {
  instructions: string;
  /** Ephemeral invocation data, placed beside the current user input and never journaled. */
  context?: string;
  tools: ToolDefinition[];
  maxTurns: number;
  maxToolCalls?: number;
  runTimeoutMs?: number;
  finalizationInstructions?: string;
  compactionInstructions?: string;
  model?: ModelSettings;
}

export interface RunRequest {
  protocolVersion: 1;
  runId: string;
  conversationId: string;
  turnId: string;
  toolContextId: string;
  content: string;
  sessionScope: string;
  definition: RunDefinition;
}

export interface PublicEvent {
  type: string;
  [key: string]: unknown;
}

export interface RunOutcome {
  terminal: "completed";
}

const RUN_FIELDS: Record<string, true> = {
  protocolVersion: true, runId: true, conversationId: true, turnId: true,
  toolContextId: true, content: true, sessionScope: true, definition: true,
};
const DEFINITION_FIELDS: Record<string, true> = {
  instructions: true, tools: true, maxTurns: true, maxToolCalls: true, runTimeoutMs: true, model: true,
  finalizationInstructions: true, compactionInstructions: true, context: true,
};
const TOOL_FIELDS: Record<string, true> = { name: true, label: true, description: true, parameters: true };
const MODEL_FIELDS: Record<string, true> = {
  modelId: true, modelBaseUrl: true, modelApiKey: true, contextWindow: true, maxOutputTokens: true,
  reasoning: true, thinkingLevel: true, supportsDeveloperRole: true, supportsReasoningEffort: true,
  supportsUsageInStreaming: true, maxTokensField: true,
};
const PROTOCOL_VERSION = 1;

function modelFor(config: SidecarConfig, settings?: ModelSettings): Model<"openai-completions"> {
  return {
    id: settings?.modelId ?? config.modelId,
    name: settings?.modelId ?? config.modelId,
    api: "openai-completions",
    provider: "agent-sidecar",
    baseUrl: settings?.modelBaseUrl ?? config.modelBaseUrl,
    reasoning: settings?.reasoning ?? true,
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: settings?.contextWindow ?? 128_000,
    maxTokens: settings?.maxOutputTokens ?? 16_384,
    compat: settings
      ? {
        supportsDeveloperRole: settings.supportsDeveloperRole,
        supportsReasoningEffort: settings.supportsReasoningEffort,
        supportsUsageInStreaming: settings.supportsUsageInStreaming,
        maxTokensField: settings.maxTokensField,
      }
      : {
        supportsDeveloperRole: false,
        supportsReasoningEffort: true,
        supportsUsageInStreaming: true,
        maxTokensField: "max_completion_tokens",
      },
  };
}

function messageText(message: unknown): string {
  if (!message || typeof message !== "object" || !("content" in message)) return "";
  const content = message.content;
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  const parts: string[] = [];
  for (const item of content) {
    if (!item || typeof item !== "object" || !("type" in item) || item.type !== "text") continue;
    if ("text" in item && typeof item.text === "string") parts.push(item.text);
  }
  return parts.join("");
}

function jsonText(value: unknown): string {
  try { return JSON.stringify(value); } catch { return "[unserializable]"; }
}

function rejectUnknownFields(value: Record<string, unknown>, allowed: Record<string, true>, where: string): void {
  for (const key of Object.keys(value)) {
    if (allowed[key] !== true) throw new Error(`${where} contains unsupported field ${key}`);
  }
}

function asObject(value: unknown, where: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${where} must be an object`);
  return value as Record<string, unknown>;
}

function boundedText(value: unknown, where: string, maximum: number, allowEmpty = false): string {
  if (typeof value !== "string") throw new Error(`${where} must be a string`);
  if (!allowEmpty && value.trim() === "") throw new Error(`${where} is required`);
  if (value.length > maximum) throw new Error(`${where} is too long`);
  return value;
}

function assertJsonData(value: unknown, where: string, depth = 0): void {
  if (depth > 32) throw new Error(`${where} is nested too deeply`);
  if (value === null || typeof value === "string" || typeof value === "boolean") return;
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error(`${where} contains a non-finite number`);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) assertJsonData(item, where, depth + 1);
    return;
  }
  if (typeof value !== "object") throw new Error(`${where} contains non-JSON data`);
  for (const item of Object.values(value)) assertJsonData(item, where, depth + 1);
}

function integerInRange(value: unknown, where: string, minimum: number, maximum: number): number {
  if (typeof value !== "number" || !Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${where} must be an integer between ${minimum} and ${maximum}`);
  }
  return value;
}

function booleanValue(value: unknown, where: string): boolean {
  if (typeof value !== "boolean") throw new Error(`${where} must be a boolean`);
  return value;
}

function thinkingLevelValue(value: unknown): ModelSettings["thinkingLevel"] {
  if (value === "off" || value === "low" || value === "medium" || value === "high") return value;
  throw new Error("definition.model.thinkingLevel must be one of off|low|medium|high");
}

function maxTokensFieldValue(value: unknown): ModelSettings["maxTokensField"] {
  if (value === "max_completion_tokens" || value === "max_tokens") return value;
  throw new Error("definition.model.maxTokensField must be one of max_completion_tokens|max_tokens");
}

function parseModel(input: unknown): ModelSettings {
  const value = asObject(input, "definition.model");
  rejectUnknownFields(value, MODEL_FIELDS, "definition.model");
  const modelId = boundedText(value.modelId, "definition.model.modelId", 512);
  const modelBaseUrl = boundedText(value.modelBaseUrl, "definition.model.modelBaseUrl", 8_192);
  let url: URL;
  try {
    url = new URL(modelBaseUrl);
  } catch {
    throw new Error("definition.model.modelBaseUrl must be an absolute HTTP URL");
  }
  if ((url.protocol !== "http:" && url.protocol !== "https:")
      || url.username !== "" || url.password !== "" || url.search !== "" || url.hash !== "") {
    throw new Error("definition.model.modelBaseUrl must be an absolute HTTP URL without credentials, query, or fragment");
  }
  const modelApiKey = boundedText(value.modelApiKey, "definition.model.modelApiKey", 8_192);
  const contextWindow = integerInRange(value.contextWindow, "definition.model.contextWindow", 1, 10_000_000);
  const maxOutputTokens = integerInRange(value.maxOutputTokens, "definition.model.maxOutputTokens", 1, 10_000_000);
  if (maxOutputTokens >= contextWindow) {
    throw new Error("definition.model.maxOutputTokens must be less than definition.model.contextWindow");
  }
  return {
    modelId,
    modelBaseUrl,
    modelApiKey,
    contextWindow,
    maxOutputTokens,
    reasoning: booleanValue(value.reasoning, "definition.model.reasoning"),
    thinkingLevel: thinkingLevelValue(value.thinkingLevel),
    supportsDeveloperRole: booleanValue(value.supportsDeveloperRole, "definition.model.supportsDeveloperRole"),
    supportsReasoningEffort: booleanValue(value.supportsReasoningEffort, "definition.model.supportsReasoningEffort"),
    supportsUsageInStreaming: booleanValue(value.supportsUsageInStreaming, "definition.model.supportsUsageInStreaming"),
    maxTokensField: maxTokensFieldValue(value.maxTokensField),
  };
}

function parseTool(input: unknown, index: number): ToolDefinition {
  const where = `definition.tools[${index}]`;
  const value = asObject(input, where);
  rejectUnknownFields(value, TOOL_FIELDS, where);
  const name = boundedText(value.name, `${where}.name`, 128);
  const label = boundedText(value.label, `${where}.label`, 512);
  const description = boundedText(value.description, `${where}.description`, 16_384, true);
  const parameters = asObject(value.parameters, `${where}.parameters`);
  assertJsonData(parameters, `${where}.parameters`);
  return { name, label, description, parameters };
}

function parseDefinition(input: unknown): RunDefinition {
  const value = asObject(input, "definition");
  rejectUnknownFields(value, DEFINITION_FIELDS, "definition");
  const instructions = boundedText(value.instructions, "definition.instructions", 65_536, true);
  if (!Array.isArray(value.tools)) throw new Error("definition.tools must be an array");
  const tools = value.tools.map(parseTool);
  const names = new Set<string>();
  for (const tool of tools) {
    if (names.has(tool.name)) throw new Error(`definition.tools contains duplicate name ${tool.name}`);
    names.add(tool.name);
  }
  const maxTurns = value.maxTurns;
  if (typeof maxTurns !== "number" || !Number.isInteger(maxTurns) || maxTurns < 1 || maxTurns > 32) {
    throw new Error("definition.maxTurns must be an integer between 1 and 32");
  }
  const maxToolCalls = value.maxToolCalls === undefined
    ? undefined
    : integerInRange(value.maxToolCalls, "definition.maxToolCalls", 1, 1_000);
  const runTimeoutMs = value.runTimeoutMs === undefined
    ? undefined
    : integerInRange(value.runTimeoutMs, "definition.runTimeoutMs", 1_000, 600_000);
  const model = value.model === undefined ? undefined : parseModel(value.model);
  const finalizationInstructions = value.finalizationInstructions === undefined ? undefined
    : boundedText(value.finalizationInstructions, "definition.finalizationInstructions", 16_000);
  const compactionInstructions = value.compactionInstructions === undefined ? undefined
    : boundedText(value.compactionInstructions, "definition.compactionInstructions", 16_000);
  const context = value.context === undefined ? undefined : boundedText(value.context, "definition.context", 80_000);
  return { instructions, context, tools, maxTurns, maxToolCalls, runTimeoutMs, model, finalizationInstructions, compactionInstructions };
}

function validateRequest(input: unknown): RunRequest {
  const body = asObject(input, "request body");
  rejectUnknownFields(body, RUN_FIELDS, "request body");
  if (body.protocolVersion !== PROTOCOL_VERSION) throw new Error("protocolVersion must be 1");
  return {
    protocolVersion: PROTOCOL_VERSION,
    runId: boundedText(body.runId, "runId", 512),
    conversationId: boundedText(body.conversationId, "conversationId", 512),
    turnId: boundedText(body.turnId, "turnId", 512),
    toolContextId: boundedText(body.toolContextId, "toolContextId", 512),
    content: boundedText(body.content, "content", 16_000),
    sessionScope: boundedText(body.sessionScope, "sessionScope", 512),
    definition: parseDefinition(body.definition),
  };
}

export class AgentRunner {
  constructor(
    private readonly config: SidecarConfig,
    private readonly sessions: PiSessionStore,
    private readonly observability: Observability,
  ) {}

  parseRequest(input: unknown): RunRequest { return validateRequest(input); }

  async run(request: RunRequest, emit: (event: PublicEvent) => void, signal: AbortSignal): Promise<RunOutcome> {
    let checkpoint: PiSessionRun;
    try { checkpoint = await this.sessions.openRun(request); }
    catch (cause) { throw new Error("智能体会话暂不可用，请稍后重新发起请求", { cause }); }
    let history: AgentMessage[];
    try { history = await checkpoint.recoverAndStart(request.content); }
    catch (error) { await checkpoint.close().catch(() => undefined); throw new Error("智能体会话未能恢复，请重新发起请求"); }
    const initialMessageCount = history.length;
    // Java-owned snapshot for this run. Never log, trace, or emit this object or its key.
    const modelSettings = request.definition.model;
    const model = modelFor(this.config, modelSettings);
    const modelApiKey = modelSettings?.modelApiKey ?? this.config.modelApiKey;
    const trace = this.observability.startAgentRun({
      conversationId: request.conversationId,
      content: request.content,
    });
    const observedEmit = (event: PublicEvent) => {
      trace?.observeEvent(event);
      emit(event);
    };
    const state: {
      halt: boolean;
      heldAssistantText: string;
      draftPublished: boolean;
      finalText: string;
      toolCalls: number;
      finalizing: boolean;
      replyText: ReplyTextFilter;
    } = { halt: false, heldAssistantText: "", draftPublished: false, finalText: "", toolCalls: 0, finalizing: false,
      replyText: createReplyTextFilter() };
    let turns = 0;
    const tools = this.createTools(request, signal, observedEmit, state, checkpoint);
    const overheadTokens = estimateTokens({ role: "user", timestamp: 0,
      content: JSON.stringify({ instructions: request.definition.instructions, tools: request.definition.tools,
        finalizationInstructions: request.definition.finalizationInstructions, context: request.definition.context }) });
    const invocationContext: AgentMessage | undefined = request.definition.context
      ? { role: "user", content: request.definition.context, timestamp: Date.now() } : undefined;
    const agent = new Agent({
      initialState: {
        model,
        systemPrompt: request.definition.instructions,
        thinkingLevel: modelSettings?.thinkingLevel ?? this.config.thinkingLevel,
        messages: history,
        tools,
      },
      streamFn: traceStreamFn(streamSimple as StreamFn, trace),
      getApiKey: () => modelApiKey,
      transformContext: async () => {
        const messages = await loadAgentHistory(checkpoint.session, model, modelApiKey, overheadTokens, signal,
          request.definition.compactionInstructions);
        if (!invocationContext) return messages;
        const currentInput = messages.findLastIndex(message => message.role === "user");
        return messages.toSpliced(currentInput < 0 ? messages.length : currentInput, 0, invocationContext);
      },
      beforeToolCall: async () => state.halt || state.finalizing
        ? { block: true, reason: request.definition.finalizationInstructions ?? "The tool phase is closed." }
        : undefined,
      shouldStopAfterTurn: async ({ toolResults }) => {
        turns += 1;
        if (!toolResults.length || state.finalizing) return true;
        const stopped = state.halt || turns >= request.definition.maxTurns
          || (request.definition.maxToolCalls !== undefined && state.toolCalls >= request.definition.maxToolCalls);
        if (stopped && request.definition.finalizationInstructions) {
          state.finalizing = true;
          return false;
        }
        return stopped;
      },
      prepareNextTurnWithContext: ({ context }) => state.finalizing ? {
        context: { ...context, tools: [],
          systemPrompt: `${context.systemPrompt}\n\n${request.definition.finalizationInstructions}` },
      } : undefined,
    });
    const detachAbort = () => agent.abort();
    signal.addEventListener("abort", detachAbort, { once: true });
    const unsubscribe = agent.subscribe(async (event, eventSignal) => {
      if (event.type === "message_end") await checkpoint.append(event.message);
      this.publishAgentEvent(request, event, emit, state, trace ? observedEvent => trace.observeEvent(observedEvent) : undefined);
      if (eventSignal.aborted) return;
    });
    try {
      signal.throwIfAborted();
      await agent.prompt(request.content);
      signal.throwIfAborted();
      const modelFailed = agent.state.messages.some((message, index) => index >= initialMessageCount && message.role === "assistant" && message.stopReason === "error");
      if (modelFailed) {
        // Pi resolves prompt() for provider errors; the persisted assistant
        // message retains diagnostics, but the public run must fail safely.
        throw new Error("Agent model request failed");
      }
      if (!state.finalText.trim() && (!state.halt || request.definition.finalizationInstructions)) {
        throw new Error("Agent model returned no displayable response");
      }
      await checkpoint.finish("completed");
      if (state.finalText) {
        observedEmit({ type: "message.completed", content: state.finalText, phase: "final" });
      }
      const outcome: RunOutcome = { terminal: "completed" };
      trace?.finish({ result: outcome });
      void this.observability.flush().catch(() => undefined);
      return outcome;
    } catch (error) {
      await checkpoint.finish(signal.aborted ? "aborted" : "failed").catch(() => undefined);
      trace?.finish({ error });
      void this.observability.flush().catch(() => undefined);
      if (error instanceof SessionError || error instanceof FileError) {
        throw new Error("智能体会话保存未完成，请重新发起请求；已产生的业务结果不会自动重发");
      }
      throw error;
    } finally {
      unsubscribe();
      signal.removeEventListener("abort", detachAbort);
      await checkpoint.close().catch(() => undefined);
    }
  }

  private createTools(
    request: RunRequest,
    signal: AbortSignal,
    emit: (event: PublicEvent) => void,
    state: { halt: boolean; toolCalls: number },
    checkpoint: PiSessionRun,
  ): AgentTool[] {
    return request.definition.tools.map(tool => ({
      name: tool.name,
      label: tool.label,
      description: tool.description,
      // Plain JSON Schema is structurally a TSchema; Pi consumes it as data.
      parameters: tool.parameters as TSchema,
      execute: async (toolCallId: string, params: unknown, toolSignal?: AbortSignal) => {
        if (!params || typeof params !== "object" || Array.isArray(params)) {
          throw new Error("tool arguments must be a JSON object");
        }
        const limit = request.definition.maxToolCalls;
        if (limit !== undefined && state.toolCalls >= limit) {
          throw new Error(`AGENT_TOOL_BUDGET_EXHAUSTED: tool call budget exhausted (maxToolCalls=${limit})`);
        }
        state.toolCalls += 1;
        await checkpoint.toolStarted(toolCallId, tool.name, params as Record<string, unknown>);
        const callback = await this.callJavaTool(
          request,
          tool.name,
          params as Record<string, unknown>,
          toolCallId,
          checkpoint.writerBinding(),
          toolSignal ?? signal,
          () => { state.halt = true; },
        );
        await checkpoint.toolCheckpoint({ role: "toolResult", toolCallId, toolName: tool.name,
          content: [{ type: "text", text: jsonText(callback.result) }], details: callback.result,
          isError: false, timestamp: Date.now() });
        emit({ type: "tool.result", tool: tool.name, toolCallId, result: callback.result });
        if (callback.halt) state.halt = true;
        return {
          content: [{ type: "text" as const, text: jsonText(callback.result) }],
          details: callback.result,
        };
      },
    }));
  }

  private async callJavaTool(
    request: RunRequest,
    toolName: string,
    argumentsValue: Record<string, unknown>,
    toolCallId: string,
    writer: { writerId: string; epoch: number },
    signal: AbortSignal,
    onHalt: () => void,
  ): Promise<{ result: unknown; halt: boolean }> {
    const controller = new AbortController();
    const onAbort = () => controller.abort();
    if (signal.aborted) controller.abort();
    else signal.addEventListener("abort", onAbort, { once: true });
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
          protocolVersion: PROTOCOL_VERSION,
          runId: request.runId,
          toolContextId: request.toolContextId,
          conversationId: request.conversationId,
          turnId: request.turnId,
          ...writer,
          toolName,
          toolCallId,
          arguments: argumentsValue,
        }),
        signal: controller.signal,
      });
      const payload: unknown = await response.json().catch(() => null);
      if (payload && typeof payload === "object" && !Array.isArray(payload)
          && "protocolVersion" in payload && payload.protocolVersion === PROTOCOL_VERSION
          && "halt" in payload && payload.halt === true) onHalt();
      if (!response.ok) {
        const message = payload && typeof payload === "object" && !Array.isArray(payload)
          && "message" in payload && typeof payload.message === "string"
          ? payload.message.slice(0, 1_000)
          : `Java tool call failed with HTTP ${response.status}`;
        throw new Error(message);
      }
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
        throw new Error("Java tool call returned an invalid response");
      }
      if (!("protocolVersion" in payload) || payload.protocolVersion !== PROTOCOL_VERSION) {
        throw new Error("Java tool call returned an unsupported protocol version");
      }
      if (!("halt" in payload) || typeof payload.halt !== "boolean") {
        throw new Error("Java tool call returned an invalid halt flag");
      }
      if (!("result" in payload)) throw new Error("Java tool call returned an invalid result");
      return { result: payload.result, halt: payload.halt };
    } catch (error) {
      if (controller.signal.aborted && !signal.aborted) {
        throw new Error("Java tool call timed out");
      }
      if (signal.aborted) throw new Error("Java tool call cancelled");
      throw error instanceof Error ? error : new Error("Java tool call unavailable");
    } finally {
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
    }
  }

  private publishAgentEvent(
    request: RunRequest,
    event: AgentEvent,
    emit: (event: PublicEvent) => void,
    state: { heldAssistantText: string; draftPublished: boolean; finalText: string;
      replyText: ReplyTextFilter },
    observe?: (event: PublicEvent) => void,
  ): void {
    const publish = (value: PublicEvent) => {
      observe?.(value);
      emit(value);
    };
    switch (event.type) {
      case "message_update": {
        // Publish only the incremental delta. Forwarding the cumulative partial
        // snapshot makes the client append repeated text.
        if (event.assistantMessageEvent?.type === "text_delta") {
          const delta = state.replyText.push(event.assistantMessageEvent.delta);
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
        // Only assistant messages carry public text; forwarding tool-result/user
        // ends echoes the caller's input back as a draft delta.
        if (event.message.role !== "assistant") break;
        const hasToolCall = event.message.content.some(block => block.type === "toolCall");
        if (hasToolCall) {
          if (state.draftPublished) {
            // Assistant prose that precedes tool calls is process evidence.
            publish({ type: "message.reclassified", to: "process", content: state.heldAssistantText });
          }
        } else {
          const finalText = publicReplyText(messageText(event.message));
          if (finalText) state.finalText = finalText;
          if (state.heldAssistantText && !state.draftPublished) {
            publish({ type: "message.delta", content: state.heldAssistantText, phase: "final" });
          }
        }
        state.heldAssistantText = "";
        state.draftPublished = false;
        state.replyText.finish();
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
