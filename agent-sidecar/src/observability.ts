import { NodeSDK } from "@opentelemetry/sdk-node";
import { LangfuseSpanProcessor } from "@langfuse/otel";
import { propagateAttributes, startObservation, type LangfuseTool } from "@langfuse/tracing";
import { createAssistantMessageEventStream, type AssistantMessage, type AssistantMessageEvent } from "@earendil-works/pi-ai";
import type { streamSimple } from "@earendil-works/pi-ai/api/openai-completions";
import { createHash } from "node:crypto";
import type { SidecarConfig } from "./config.js";
import type { RunOutcome, PublicEvent } from "./agent.js";

export interface GenerationScope {
  firstText(): void;
  finish(input: {
    text: string;
    content?: AssistantMessage["content"];
    model?: string;
    usage?: unknown;
    error?: unknown;
  }): void;
}

export interface AgentRunTrace {
  beginStage(name: string): { finish(error?: unknown): void };
  observeEvent(event: PublicEvent): void;
  beginGeneration(input: { name: string; model: string; systemPrompt?: string; messages: unknown[]; toolNames: string[] }): GenerationScope | null;
  finish(input: { result?: RunOutcome; error?: unknown }): void;
}

export interface Observability {
  startAgentRun(input: { runId: string; turnId: string; conversationId: string; content: string }): AgentRunTrace | null;
  flush(): Promise<void>;
  shutdown(): Promise<void>;
}

const noop: Observability = {
  startAgentRun: () => null,
  async flush() {},
  async shutdown() {},
};

/**
 * Explicit Java-provided observability settings. Fetched once at process
 * start from the private bridge endpoint; there is deliberately no
 * environment fallback and no live swap: one process owns one SDK because
 * @langfuse/tracing reads the process-global OTel provider.
 */
export interface ObservabilitySettings {
  enabled: boolean;
  revision?: number;
  baseUrl?: string;
  publicKey?: string;
  secretKey?: string;
}

export function createObservability(settings: ObservabilitySettings | null): Observability {
  if (!settings?.enabled || !settings.baseUrl || !settings.publicKey || !settings.secretKey) return noop;

  const processor = new LangfuseSpanProcessor({
    publicKey: settings.publicKey,
    secretKey: settings.secretKey,
    baseUrl: settings.baseUrl,
  });
  const sdk = new NodeSDK({ spanProcessors: [processor] });
  sdk.start();
  return {
    startAgentRun({ runId, turnId, conversationId, content }) {
      const root = propagateAttributes(
        { traceName: "agent-run", sessionId: conversationId, tags: ["agent"] },
        () => startObservation("agent-run", { input: content, metadata: { runId, turnId } }),
      );
      const openTools = new Map<string, LangfuseTool[]>();
      const startedAt = performance.now();
      let firstPublicText = false;
      return {
        beginStage(name) {
          const started = performance.now();
          const stage = root.startObservation(name);
          return {
            finish(error) {
              stage.update({
                output: { durationMs: performance.now() - started, status: error ? "failed" : "completed" },
                ...(error ? { level: "ERROR" as const, statusMessage: "stage failed" } : {}),
              });
              stage.end();
            },
          };
        },
        observeEvent(event) {
          if (!firstPublicText && event.type === "message.delta"
              && typeof event.content === "string" && event.content.trim()) {
            firstPublicText = true;
            root.update({ metadata: { firstPublicTextMs: performance.now() - startedAt } });
          }
          if (event.type === "message.completed") root.update({ output: event.content });
          if (event.type === "tool.started") {
            const name = typeof event.tool === "string" ? event.tool : "tool";
            const queue = openTools.get(name) ?? [];
            queue.push(root.startObservation(name, { input: event.arguments }, { asType: "tool" }));
            openTools.set(name, queue);
            return;
          }
          if (event.type === "tool.progress") {
            const name = typeof event.tool === "string" ? event.tool : "tool";
            openTools.get(name)?.at(-1)?.update({ output: event.details });
            return;
          }
          if (event.type === "tool.completed") {
            const name = typeof event.tool === "string" ? event.tool : "tool";
            const span = openTools.get(name)?.shift();
            if (!span) return;
            const failed = event.status === "failed";
            span.update({
              output: event.details,
              level: failed ? "ERROR" : "DEBUG",
              ...(failed ? { statusMessage: String(event.details ?? "tool failed") } : {}),
            });
            span.end();
          }
        },
        beginGeneration({ name, model, systemPrompt, messages, toolNames }) {
          const generation = root.startObservation(name, {
            model,
            input: { systemPrompt, messages },
            metadata: { toolNames },
          }, { asType: "generation" });
          let hasText = false;
          return {
            firstText() {
              if (hasText) return;
              hasText = true;
              generation.update({ completionStartTime: new Date() });
            },
            finish({ text, content: finalContent, model: responseModel, usage, error }) {
              const toolCalls = finalContent?.filter(block => block.type === "toolCall").map(block => ({
                id: block.id,
                type: "function",
                function: { name: block.name, arguments: JSON.stringify(block.arguments) },
              }));
              generation.update({
                output: toolCalls?.length ? { role: "assistant", content: text || null, tool_calls: toolCalls } : text,
                ...(responseModel ? { model: responseModel } : {}),
                metadata: { usage },
                ...(error ? { level: "ERROR" as const, statusMessage: String((error as Error)?.message ?? error) } : {}),
              });
              generation.end();
            },
          };
        },
        finish({ result, error }) {
          root.update({
            metadata: { stoppedReason: result?.terminal ?? null },
            ...(error ? { level: "ERROR" as const, statusMessage: String((error as Error)?.message ?? error) } : {}),
          });
          root.end();
        },
      };
    },
    async flush() { await processor.forceFlush(); },
    async shutdown() { await sdk.shutdown(); },
  };
}

/** Non-secret digest of adopted credentials so /healthz can prove adoption without revealing keys. */
export function credentialsDigest(settings: ObservabilitySettings | null): string | undefined {
  if (!settings?.enabled || !settings.publicKey || !settings.secretKey) return undefined;
  return createHash("sha256").update(`${settings.publicKey}:${settings.secretKey}`).digest("hex");
}

/** Startup fetch of the observability snapshot from the Java-owned admin configuration. */
export async function fetchObservabilitySettings(config: SidecarConfig): Promise<ObservabilitySettings> {
  const url = new URL("/internal/agent/observability", new URL(config.javaToolsUrl).origin);
  const response = await fetch(url, {
    headers: { "x-agent-bridge-token": config.javaToolsToken },
    signal: AbortSignal.timeout(config.javaToolTimeoutMs),
  });
  if (!response.ok) throw new Error(`observability configuration unavailable (HTTP ${response.status})`);
  const body: unknown = await response.json();
  if (!body || typeof body !== "object" || Array.isArray(body)) {
    throw new Error("observability configuration malformed");
  }
  const value = body as Record<string, unknown>;
  if (typeof value.enabled !== "boolean") throw new Error("observability configuration malformed");
  const revision = typeof value.revision === "number" && Number.isSafeInteger(value.revision) && value.revision >= 0
    ? value.revision : undefined;
  if (!value.enabled) return { enabled: false, revision };
  const baseUrl = requireText(value.baseUrl, "baseUrl");
  const publicKey = requireText(value.publicKey, "publicKey");
  const secretKey = requireText(value.secretKey, "secretKey");
  return { enabled: true, revision, baseUrl, publicKey, secretKey };
}


function requireText(value: unknown, name: string): string {
  if (typeof value !== "string" || value.trim() === "" || value.length > 8_192) {
    throw new Error(`observability configuration malformed: ${name}`);
  }
  return value;
}

export function traceStreamFn(
  streamFn: typeof streamSimple, run: AgentRunTrace | null, name = "model-turn",
): typeof streamSimple {
  return (model, context, options) => {
    // Start the generation before invoking the provider, not after opening its stream.
    const scope = run?.beginGeneration({
      name,
      model: model.id,
      systemPrompt: context.systemPrompt,
      messages: context.messages,
      toolNames: context.tools?.map(tool => tool.name) ?? [],
    });
    if (!scope) return streamFn(model, context, options);
    const forwarded = createAssistantMessageEventStream();
    void (async () => {
      let text = "";
      let final: AssistantMessage | undefined;
      try {
        const underlying = streamFn(model, context, options);
        for await (const event of underlying as AsyncIterable<AssistantMessageEvent>) {
          if (event.type === "text_delta") {
            if (event.delta.trim()) scope.firstText();
            text += event.delta;
          }
          if (event.type === "done") final = event.message;
          if (event.type === "error") final = event.error;
          forwarded.push(event);
        }
        scope.finish({ text, content: final?.content, model: final?.responseModel, usage: final?.usage, error: final?.errorMessage });
      } catch (error) {
        scope.finish({ text, content: final?.content, model: final?.responseModel, usage: final?.usage, error });
        forwarded.end(final);
        return;
      }
      forwarded.end(final);
    })();
    return forwarded;
  };
}

/** Timing-only spans: no arguments, return values, credentials, or exception text are captured. */
export async function traceOperation<T>(
  run: AgentRunTrace | null, name: string, operation: () => Promise<T>,
): Promise<T> {
  const stage = run?.beginStage(name);
  try {
    const result = await operation();
    stage?.finish();
    return result;
  } catch (error) {
    stage?.finish(error);
    throw error;
  }
}
