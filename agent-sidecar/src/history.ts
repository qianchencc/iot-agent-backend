import { createHash, randomUUID } from "node:crypto";
import {
  buildSessionContext, compact, DEFAULT_COMPACTION_SETTINGS, estimateContextTokens, estimateTokens,
  prepareCompaction, shouldCompact, type AgentMessage, type Session,
} from "@earendil-works/pi-agent-core";
import { createModels, createProvider, hasApi, type Model } from "@earendil-works/pi-ai";
import { stream, streamSimple } from "@earendil-works/pi-ai/api/openai-completions";

/** Native Pi summaries are journal entries, never a replacement for original business facts. */
export async function loadAgentHistory(session: Session, model: Model<"openai-completions">,
  apiKey: string, overheadTokens: number, signal: AbortSignal, instructions?: string): Promise<AgentMessage[]> {
  const entries = await session.findEntriesOnBranch({ order: "oldestFirst" });
  const messages = buildSessionContext(entries).messages;
  // A retained assistant's usage predates the summary. Later provider usage is authoritative again.
  const lastCompaction = entries.findLastIndex(entry => entry.type === "compaction");
  const policyHash = createHash("sha256").update(instructions ?? "").digest("hex");
  const previous = lastCompaction < 0 ? undefined : entries[lastCompaction];
  const details = previous?.type === "compaction" ? previous.details : undefined;
  const rebase = previous !== undefined && (!details || typeof details !== "object"
    || !("policyHash" in details) || details.policyHash !== policyHash);
  const hasFreshUsage = entries.some((entry, index) => index > lastCompaction && entry.type === "message"
    && entry.message.role === "assistant" && entry.message.usage.totalTokens > 0);
  const tokens = lastCompaction >= 0 && !hasFreshUsage
    ? messages.reduce((total, message) => total + estimateTokens(message), 0)
    : estimateContextTokens(messages).tokens;
  if (!rebase && !shouldCompact(tokens + overheadTokens, model.contextWindow, DEFAULT_COMPACTION_SETTINGS)) return messages;
  // A changed policy must see original journal messages, not only a potentially lossy old summary.
  const prepared = prepareCompaction(rebase ? entries.filter(entry => entry.type !== "compaction") : entries,
    DEFAULT_COMPACTION_SETTINGS);
  if (!prepared.ok) throw prepared.error;
  if (!prepared.value || (!prepared.value.messagesToSummarize.length && !prepared.value.turnPrefixMessages.length)) return messages;

  const models = createModels();
  models.setProvider(createProvider({ id: model.provider, models: [model],
    auth: { apiKey: { name: "Run model key", resolve: async () => ({ auth: { apiKey } }) } },
    api: {
      stream: (candidate, context, options) => {
        if (!hasApi(candidate, "openai-completions")) throw new Error("Unsupported summary API");
        return stream(candidate, context, options);
      },
      streamSimple: (candidate, context, options) => {
        if (!hasApi(candidate, "openai-completions")) throw new Error("Unsupported summary API");
        return streamSimple(candidate, context, options);
      },
    },
  }));
  const completeSummary = models.completeSimple.bind(models);
  models.completeSimple = async (...args) => {
    const response = await completeSummary(...args);
    if (response.stopReason === "length") throw new Error("会话摘要达到生成上限");
    return response;
  };
  const result = await compact(prepared.value, models, model, instructions, signal, "low",
    { enabled: false, maxRetries: 0, baseDelayMs: 0 });
  if (!result.ok) throw result.error;
  if (!result.value.summary.trim()) throw new Error("会话摘要为空");
  signal.throwIfAborted();
  const entry = await session.appendEntry({
    type: "compaction", id: randomUUID(), summary: result.value.summary,
    retainedTail: result.value.retainedTail, tokensBefore: result.value.tokensBefore,
    details: { ...(result.value.details !== null && typeof result.value.details === "object"
      ? result.value.details : {}), policyHash },
    ...(result.value.usage ? { usage: result.value.usage } : {}),
  }, "main");
  return buildSessionContext([...entries, entry]).messages;
}
