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
import {
  createAssistantMessageEventStream,
  type AssistantMessage,
  type AssistantMessageEvent,
  type Model,
  type TSchema,
} from "@earendil-works/pi-ai";
import { streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import type { SidecarConfig } from "./config.js";
import { traceOperation, traceStreamFn, type Observability } from "./observability.js";
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
  /** Continue the restored conversation without adding a new user message. */
  resume?: boolean;
  tools: ToolDefinition[];
  maxTurns: number;
  maxToolCalls?: number;
  runTimeoutMs?: number;
  finalizationInstructions?: string;
  compactionInstructions?: string;
  model: ModelSettings;
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
  finalizationInstructions: true, compactionInstructions: true, context: true, resume: true,
};
const TOOL_FIELDS: Record<string, true> = { name: true, label: true, description: true, parameters: true };
const MODEL_FIELDS: Record<string, true> = {
  modelId: true, modelBaseUrl: true, modelApiKey: true, contextWindow: true, maxOutputTokens: true,
  reasoning: true, thinkingLevel: true, supportsDeveloperRole: true, supportsReasoningEffort: true,
  supportsUsageInStreaming: true, maxTokensField: true,
};
const PROTOCOL_VERSION = 1;

function modelFor(settings: ModelSettings): Model<"openai-completions"> {
  return {
    id: settings.modelId,
    name: settings.modelId,
    api: "openai-completions",
    provider: "agent-sidecar",
    baseUrl: settings.modelBaseUrl,
    reasoning: settings.reasoning,
    input: ["text"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: settings.contextWindow,
    maxTokens: settings.maxOutputTokens,
    compat: {
      supportsDeveloperRole: settings.supportsDeveloperRole,
      supportsReasoningEffort: settings.supportsReasoningEffort,
      supportsUsageInStreaming: settings.supportsUsageInStreaming,
      maxTokensField: settings.maxTokensField,
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

/**
 * Java-owned typed recovery rendered for the model-visible error text. The
 * sidecar never interprets business meaning; object recovery is emitted as one
 * compact JSON value or not at all, so a size bound can never cut it into an
 * invalid fragment. String recovery is already model-facing Java text.
 */
function recoveryText(value: unknown): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (typeof value === "string") return value.trim() ? value.slice(0, 2_000) : undefined;
  if (typeof value !== "object") return undefined;
  try {
    const json = JSON.stringify(value);
    return json && json.length <= 2_000 ? json : undefined;
  } catch {
    return undefined;
  }
}

/** Connect-phase transport failure of a model request; safe to retry the same call. */
function isConnectTimeout(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? "");
  const cause = error && typeof error === "object" && "cause" in error ? error.cause : undefined;
  const code = error && typeof error === "object" && "code" in error ? error.code
    : cause && typeof cause === "object" && "code" in cause ? cause.code : undefined;
  return code === "UND_ERR_CONNECT_TIMEOUT"
    || /UND_ERR_CONNECT_TIMEOUT|connect(?:ion)? timeout/i.test(message);
}

/**
 * Retries only the in-flight model request once on a connect-phase timeout
 * (nothing was streamed yet and the run was not aborted), then fails safely
 * with a terminal error message so the SDK result() never hangs.
 */
function withConnectRetry(streamFn: StreamFn): StreamFn {
  return (model, context, streamOptions) => {
    const forwarded = createAssistantMessageEventStream();
    void (async () => {
      let retried = false;
      while (true) {
        let started = false;
        let final: AssistantMessage | undefined;
        let deferredError: Extract<AssistantMessageEvent, { type: "error" }> | undefined;
        try {
          const underlying = streamFn(model, context, streamOptions);
          for await (const event of underlying as AsyncIterable<AssistantMessageEvent>) {
            if (event.type === "start") started = true;
            else if (event.type === "done") final = event.message;
            else if (event.type === "error") { final = event.error; deferredError = event; break; }
            forwarded.push(event);
          }
          if (deferredError) {
            if (!retried && !started && !streamOptions?.signal?.aborted && isConnectTimeout(deferredError.error)) {
              retried = true;
              continue;
            }
            forwarded.push(deferredError);
            forwarded.end(final);
            return;
          }
          if (!final) throw new Error("model stream returned no terminal state");
          forwarded.end(final);
          return;
        } catch (error) {
          if (!retried && !started && !streamOptions?.signal?.aborted && isConnectTimeout(error)) {
            retried = true;
            continue;
          }
          const failed: AssistantMessage = {
            role: "assistant",
            content: [],
            api: model.api,
            provider: model.provider,
            model: model.id,
            stopReason: streamOptions?.signal?.aborted ? "aborted" : "error",
            errorMessage: error instanceof Error ? error.message : String(error),
            usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
              cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
            timestamp: Date.now(),
          };
          forwarded.push({ type: "error", reason: failed.stopReason as "error" | "aborted", error: failed });
          forwarded.end(failed);
          return;
        }
      }
    })();
    return forwarded;
  };
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
  if (value.model === undefined) throw new Error("definition.model is required; configure the model connection in the admin console");
  const model = parseModel(value.model);
  const finalizationInstructions = value.finalizationInstructions === undefined ? undefined
    : boundedText(value.finalizationInstructions, "definition.finalizationInstructions", 16_000);
  const compactionInstructions = value.compactionInstructions === undefined ? undefined
    : boundedText(value.compactionInstructions, "definition.compactionInstructions", 16_000);
  const context = value.context === undefined ? undefined : boundedText(value.context, "definition.context", 80_000);
  if (value.resume !== undefined && typeof value.resume !== "boolean") throw new Error("definition.resume must be boolean");
  if (value.resume === true && !context) throw new Error("definition.resume requires invocation context");
  return { instructions, context, resume: value.resume as boolean | undefined, tools, maxTurns, maxToolCalls, runTimeoutMs, model, finalizationInstructions, compactionInstructions };
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
    const trace = this.observability.startAgentRun({
      runId: request.runId, turnId: request.turnId,
      conversationId: request.conversationId, content: request.content,
    });
    let checkpoint: PiSessionRun;
    try { checkpoint = await traceOperation(trace, "open-session", () => this.sessions.openRun(request)); }
    catch (cause) {
      trace?.finish({ error: cause });
      void this.observability.flush().catch(() => undefined);
      throw new Error("智能体会话暂不可用，请稍后重新发起请求", { cause });
    }
    let history: AgentMessage[];
    try {
      history = await traceOperation(trace, "recover-session",
        () => checkpoint.recoverAndStart(request.content, request.definition.resume === true));
    } catch (error) {
      trace?.finish({ error });
      void this.observability.flush().catch(() => undefined);
      await checkpoint.close().catch(() => undefined);
      throw new Error("智能体会话未能恢复，请重新发起请求");
    }
    const initialMessageCount = history.length;
    // Java-owned snapshot for this run. Never log, trace, or emit this object or its key.
    const modelSettings = request.definition.model;
    const model = modelFor(modelSettings);
    const modelApiKey = modelSettings.modelApiKey;
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
      completion?: string;
      closingContext?: string;
      toolErrorCodes: Map<string, string>;
    } = { halt: false, heldAssistantText: "", draftPublished: false, finalText: "", toolCalls: 0, finalizing: false,
      replyText: createReplyTextFilter(), toolErrorCodes: new Map() };
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
        thinkingLevel: modelSettings.thinkingLevel,
        messages: request.definition.resume && invocationContext ? [...history, invocationContext] : history,
        tools,
      },
      getApiKey: () => modelApiKey,
      streamFn: withConnectRetry(traceStreamFn(streamSimple, trace) as StreamFn),
      // Runtime parity: sequential execution lets mid-batch stop decisions
      // (budget/no-progress) block the rest of the same batch immediately.
      toolExecution: "sequential",
      transformContext: async () => {
        const messages = await traceOperation(trace, "load-history",
          () => loadAgentHistory(checkpoint.session, model, modelApiKey, overheadTokens, signal,
            request.definition.compactionInstructions, trace));
        if (!invocationContext) return messages;
        if (request.definition.resume) return [...messages, invocationContext];
        const currentInput = messages.findLastIndex(message => message.role === "user");
        return messages.toSpliced(currentInput < 0 ? messages.length : currentInput, 0, invocationContext);
      },
      beforeToolCall: async ({ toolCall }) => {
        if (state.halt || state.finalizing) {
          // Runtime parity: a call refused because the tool phase already closed
          // is a skip ("已改用现有结果收尾"), not a failed business step.
          if (state.finalizing) state.toolErrorCodes.set(toolCall.id, "AGENT_TOOL_PHASE_CLOSED");
          return { block: true, reason: request.definition.finalizationInstructions ?? "The tool phase is closed." };
        }
        return undefined;
      },
      shouldStopAfterTurn: async ({ toolResults }) => {
        turns += 1;
        if (state.completion !== undefined) return true;
        if (!toolResults.length || state.finalizing) return true;
        const toolTurns = request.definition.finalizationInstructions
          ? Math.max(1, request.definition.maxTurns - 1) : request.definition.maxTurns;
        const stopped = state.halt || turns >= toolTurns
          || (request.definition.maxToolCalls !== undefined && state.toolCalls >= request.definition.maxToolCalls);
        if (stopped && request.definition.finalizationInstructions) {
          state.finalizing = true;
          return false;
        }
        return stopped;
      },
      prepareNextTurnWithContext: ({ context }) => state.finalizing ? {
        context: { ...context, tools: [],
          systemPrompt: `${context.systemPrompt}\n\n${request.definition.finalizationInstructions}`
            + (state.closingContext ? `\n\n${state.closingContext}` : "") },
      } : undefined,
    });
    const detachAbort = () => agent.abort();
    signal.addEventListener("abort", detachAbort, { once: true });
    const unsubscribe = agent.subscribe(async (event, eventSignal) => {
      if (event.type === "message_end")
        await traceOperation(trace, "append-message", () => checkpoint.append(event.message));
      this.publishAgentEvent(request, event, emit, state, trace ? observedEvent => trace.observeEvent(observedEvent) : undefined);
      if (eventSignal.aborted) return;
    });
    try {
      signal.throwIfAborted();
      if (request.definition.resume) await agent.continue();
      else await agent.prompt(request.content);
      signal.throwIfAborted();
      if (state.completion !== undefined) state.finalText = state.completion;
      const modelFailed = agent.state.messages.some((message, index) => index >= initialMessageCount && message.role === "assistant" && message.stopReason === "error");
      if (modelFailed) {
        // Pi resolves prompt() for provider errors; the persisted assistant
        // message retains diagnostics, but the public run must fail safely.
        throw new Error("Agent model request failed");
      }
      // Runtime parity (pi-runner.ts length stop): a truncated answer is replaced
      // by a bounded receipt instead of publishing a partial answer as final.
      const lengthLimited = agent.state.messages.some((message, index) => index >= initialMessageCount
        && message.role === "assistant" && message.stopReason === "length");
      if (lengthLimited) {
        state.finalText = "本次请求未能完整处理：模型输出达到长度上限；请缩小请求范围后重试。";
      }
      if (!state.finalText.trim() && (!state.halt || request.definition.finalizationInstructions)) {
        throw new Error("Agent model returned no displayable response");
      }
      await traceOperation(trace, "finish-session", () => checkpoint.finish("completed"));
      if (state.finalText) {
        observedEmit({ type: "message.completed", content: state.finalText, phase: "final" });
      }
      const outcome: RunOutcome = { terminal: "completed" };
      trace?.finish({ result: outcome });
      void this.observability.flush().catch(() => undefined);
      return outcome;
    } catch (error) {
      await traceOperation(trace, "finish-session",
        () => checkpoint.finish(signal.aborted ? "aborted" : "failed")).catch(() => undefined);
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
    state: { halt: boolean; toolCalls: number; completion?: string; toolErrorCodes: Map<string, string> },
    checkpoint: PiSessionRun,
  ): AgentTool[] {
    return request.definition.tools.map(tool => ({
      name: tool.name,
      label: tool.label,
      description: tool.description,
      // Plain JSON Schema is structurally a TSchema; Pi consumes it as data.
      parameters: tool.parameters as TSchema,
      execute: async (toolCallId: string, params: unknown, toolSignal?: AbortSignal) => {
        try {
          return await this.executeTool(request, tool, toolCallId, params, signal, toolSignal, emit, state, checkpoint);
        } catch (error) {
          // Keep the opaque engine-protocol code so the projection can classify
          // the outcome; the wrapped message text alone loses it.
          if (error && typeof error === "object" && "code" in error && typeof error.code === "string") {
            state.toolErrorCodes.set(toolCallId, error.code);
          }
          throw error;
        }
      },
    }));
  }

  private async executeTool(
    request: RunRequest,
    tool: ToolDefinition,
    toolCallId: string,
    params: unknown,
    signal: AbortSignal,
    toolSignal: AbortSignal | undefined,
    emit: (event: PublicEvent) => void,
    state: { halt: boolean; toolCalls: number; completion?: string; closingContext?: string },
    checkpoint: PiSessionRun,
  ): Promise<{ content: Array<{ type: "text"; text: string }>; details: unknown }> {
    if (!params || typeof params !== "object" || Array.isArray(params)) {
      throw new Error("tool arguments must be a JSON object");
    }
    const limit = request.definition.maxToolCalls;
    if (limit !== undefined && state.toolCalls >= limit) {
      state.halt = true;
      throw Object.assign(
        new Error(`AGENT_TOOL_BUDGET_EXHAUSTED: tool call budget exhausted (maxToolCalls=${limit})`),
        { code: "AGENT_TOOL_BUDGET_EXHAUSTED" });
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
      content: [{ type: "text", text: jsonText(callback.modelResult ?? callback.result) }], details: callback.result,
      isError: false, timestamp: Date.now() });
    emit({ type: "tool.result", tool: tool.name, toolCallId, result: callback.result });
    if (callback.halt) state.halt = true;
    if (callback.completion !== undefined) state.completion = callback.completion;
    if (callback.closingContext !== undefined) state.closingContext = callback.closingContext;
    return {
      content: [{ type: "text" as const, text: jsonText(callback.modelResult ?? callback.result) }],
      details: callback.result,
    };
  }

  private async callJavaTool(
    request: RunRequest,
    toolName: string,
    argumentsValue: Record<string, unknown>,
    toolCallId: string,
    writer: { writerId: string; epoch: number },
    signal: AbortSignal,
    onHalt: () => void,
  ): Promise<{ result: unknown; modelResult?: unknown; halt: boolean; completion?: string; closingContext?: string }> {
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
        const body = payload && typeof payload === "object" && !Array.isArray(payload) ? payload : undefined;
        const message = body && "message" in body && typeof body.message === "string"
          ? body.message.slice(0, 1_000)
          : `Java tool call failed with HTTP ${response.status}`;
        const code = body && "code" in body && typeof body.code === "string" ? body.code : undefined;
        // The code is an opaque engine-protocol marker; Java decides what it means.
        // Typed recovery rides along so the model sees the same structured facts the
        // public bridge response carries; without it the rendering path would discard them.
        const recoveryValue = body && "recovery" in body ? body.recovery : undefined;
        const recovery = recoveryText(recoveryValue);
        const text = recovery ? `${message}\n${recovery}` : message;
        throw Object.assign(new Error(text), {
          ...(code ? { code } : {}),
          ...(recoveryValue !== undefined ? { recovery: recoveryValue } : {}),
        });
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
      const completion = "completion" in payload ? payload.completion : undefined;
      if (completion !== undefined && (typeof completion !== "string" || !completion.trim() || completion.length > 8_000)) {
        throw new Error("Java tool call returned an invalid completion");
      }
      if (completion !== undefined && payload.halt !== true) {
        throw new Error("Java tool completion must close the tool phase");
      }
      // Optional Java-owned closing facts (Runtime prepareNextTurnWithContext):
      // the latest value is appended verbatim to the no-tool finalization turn.
      const closingContext = "closingContext" in payload ? payload.closingContext : undefined;
      if (closingContext !== undefined && (typeof closingContext !== "string"
          || !closingContext.trim() || closingContext.length > 16_000)) {
        throw new Error("Java tool call returned an invalid closingContext");
      }
      return { result: payload.result, halt: payload.halt,
        ...("modelResult" in payload ? { modelResult: payload.modelResult } : {}),
        ...(typeof completion === "string" ? { completion } : {}),
        ...(typeof closingContext === "string" ? { closingContext } : {}) };
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
      replyText: ReplyTextFilter; toolErrorCodes: Map<string, string> },
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
      case "tool_execution_end": {
        // Bounded failure evidence, mirroring the Runtime timeline item; the
        // opaque code lets the Java projection classify stop decisions.
        const completed: PublicEvent = { type: "tool.completed", tool: event.toolName,
          toolCallId: event.toolCallId, status: event.isError ? "failed" : "completed" };
        if (event.isError) {
          const errorText = messageText(event.result).slice(0, 200);
          if (errorText) completed.error = errorText;
          const code = state.toolErrorCodes.get(event.toolCallId);
          if (code) {
            completed.code = code;
            state.toolErrorCodes.delete(event.toolCallId);
          }
        }
        publish(completed);
        break;
      }
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
