import { NodeSDK } from "@opentelemetry/sdk-node";
import { LangfuseSpanProcessor } from "@langfuse/otel";
import { propagateAttributes, startObservation, type LangfuseTool } from "@langfuse/tracing";
import { createAssistantMessageEventStream, type AssistantMessage, type AssistantMessageEvent } from "@earendil-works/pi-ai";
import type { StreamFn } from "@earendil-works/pi-agent-core";
import type { RunOutcome, PublicEvent } from "./agent.js";

export interface GenerationScope {
  finish(input: {
    text: string;
    content?: AssistantMessage["content"];
    model?: string;
    usage?: unknown;
    error?: unknown;
  }): void;
}

export interface AgentRunTrace {
  observeEvent(event: PublicEvent): void;
  beginGeneration(input: { model: string; systemPrompt?: string; messages: unknown[]; toolNames: string[] }): GenerationScope | null;
  finish(input: { result?: RunOutcome; error?: unknown }): void;
}

export interface Observability {
  startAgentRun(input: { conversationId: string; tenantCode: string; content: string }): AgentRunTrace | null;
  flush(): Promise<void>;
  shutdown(): Promise<void>;
}

const noop: Observability = {
  startAgentRun: () => null,
  async flush() {},
  async shutdown() {},
};

export function createObservability(env: Record<string, string | undefined> = process.env): Observability {
  const publicKey = env.LANGFUSE_PUBLIC_KEY?.trim();
  const secretKey = env.LANGFUSE_SECRET_KEY?.trim();
  const baseUrl = env.LANGFUSE_BASE_URL?.trim();
  if (!publicKey || !secretKey || !baseUrl) return noop;

  const processor = new LangfuseSpanProcessor({ publicKey, secretKey, baseUrl });
  const sdk = new NodeSDK({ spanProcessors: [processor] });
  sdk.start();

  return {
    startAgentRun({ conversationId, tenantCode, content }) {
      const root = propagateAttributes(
        { traceName: "agent-run", sessionId: conversationId, tags: ["agent", tenantCode] },
        () => startObservation("agent-run", { input: content }),
      );
      const openTools = new Map<string, LangfuseTool[]>();
      return {
        observeEvent(event) {
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
        beginGeneration({ model, systemPrompt, messages, toolNames }) {
          const generation = root.startObservation("model-turn", {
            model,
            input: { systemPrompt, messages },
            metadata: { toolNames },
          }, { asType: "generation" });
          return {
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

export function traceStreamFn(streamFn: StreamFn, run: AgentRunTrace | null): StreamFn {
  return (model, context, options) => {
    const underlying = streamFn(model, context, options);
    const scope = run?.beginGeneration({
      model: model.id,
      systemPrompt: context.systemPrompt,
      messages: context.messages,
      toolNames: context.tools?.map(tool => tool.name) ?? [],
    });
    if (!scope) return underlying;
    const forwarded = createAssistantMessageEventStream();
    void (async () => {
      let text = "";
      let final: AssistantMessage | undefined;
      try {
        for await (const event of underlying as AsyncIterable<AssistantMessageEvent>) {
          if (event.type === "text_delta") text += event.delta;
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
