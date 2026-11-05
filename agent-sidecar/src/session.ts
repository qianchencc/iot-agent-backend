import { randomUUID } from 'node:crypto';
import { buildSessionContext, type AgentMessage, type Session } from '@earendil-works/pi-agent-core';
import type { SidecarConfig } from './config.js';
import { MysqlSessionFileSystem, MysqlSessionRepository, type SessionBinding } from './mysql-session-repository.js';
import { toDurableMessage } from './reply-text.js';

export class PiSessionRun {
  private assistantEntryId = '';
  private readonly resultIds = new Map<string, string>();
  private readonly acknowledgedTools = new Set<string>();
  private finished = false;
  constructor(readonly session: Session, private readonly repository: MysqlSessionRepository,
    private readonly binding: SessionBinding, private readonly writer: { writerId: string; epoch: number },
    private readonly onClose: () => void) {}

  writerBinding(): { writerId: string; epoch: number } { return this.writer; }

  async recoverAndStart(content: string, resume = false): Promise<AgentMessage[]> {
    const entries = await this.session.findEntriesOnBranch({ order: 'oldestFirst' });
    const pending = new Map<string, { assistantId: string; name: string }>();
    const checkpoints = new Map<string, AgentMessage>();
    for (const entry of entries) {
      if (entry.type === 'compaction') pending.clear();
      if (entry.type === 'message') {
        if (entry.message.role === 'assistant') {
          for (const block of entry.message.content) {
            if (block.type === 'toolCall') pending.set(block.id, { assistantId: entry.id, name: block.name });
          }
        } else if (entry.message.role === 'toolResult') pending.delete(entry.message.toolCallId);
      }
      if (entry.type === 'custom' && entry.customType === 'tool_checkpoint' && entry.data && typeof entry.data === 'object') {
        const saved = entry.data as { assistantEntryId: string; message: AgentMessage };
        if (saved.message?.role === 'toolResult') {
          checkpoints.set(`${saved.assistantEntryId}:${saved.message.toolCallId}`, saved.message);
        }
      }
    }
    // Recover committed outcomes; an uncertain external effect is never executed again.
    for (const [toolCallId, target] of pending) {
      await this.session.appendMessage(checkpoints.get(`${target.assistantId}:${toolCallId}`) ?? {
        role: 'toolResult', toolCallId, toolName: target.name,
        content: [{ type: 'text', text: '上次工具调用中断，执行结果未确认。禁止自动重发；如需继续，请查询实际业务事实。' }],
        isError: true, timestamp: Date.now(),
      });
    }
    for (const open of await this.session.findOpenOperations('main')) {
      await this.session.appendRecord({ id: randomUUID(), type: 'operation_finished', lane: 'main',
        runId: open.id, outcome: 'aborted', error: { code: 'ENGINE_INTERRUPTED', message: '执行节点已中断，未自动重放工具' } });
    }
    const history = buildSessionContext(await this.session.findEntriesOnBranch({ order: 'oldestFirst' })).messages;
    await this.session.appendRecord({ id: this.binding.runId, type: 'operation_started', lane: 'main',
      sourceLeafId: await this.session.getLeafId(), intent: { kind: 'run',
        originalPrompt: resume ? [] : [{ role: 'user', content, timestamp: Date.now() }], initialMessages: [] } });
    return history;
  }

  async append(message: AgentMessage): Promise<void> {
    const id = message.role === 'toolResult' ? this.resultIds.get(message.toolCallId) : undefined;
    if (message.role === 'toolResult' && this.acknowledgedTools.has(message.toolCallId)) return;
    const entryId = id ?? randomUUID();
    // Pi's loop materializes optional fields as undefined; its durable API requires JSON values.
    const durableMessage = toDurableMessage(message);
    await this.session.appendEntry({ id: entryId, type: 'message', message: durableMessage }, 'main');
    if (message.role === 'assistant') {
      this.assistantEntryId = entryId;
      this.resultIds.clear();
      this.acknowledgedTools.clear();
    }
    if (message.role === 'toolResult') this.acknowledgedTools.add(message.toolCallId);
  }

  async toolStarted(toolCallId: string, toolName: string, effectiveArgs: Record<string, unknown>): Promise<void> {
    const resultEntryId = randomUUID();
    this.resultIds.set(toolCallId, resultEntryId);
    await this.session.appendRecord({ id: randomUUID(), type: 'tool_started', lane: 'main', runId: this.binding.runId,
      assistantEntryId: this.assistantEntryId, toolIndex: this.resultIds.size - 1,
      toolCallId, toolName, effectiveArgs, resultEntryId, replay: 'never' });
  }

  async toolCheckpoint(message: AgentMessage): Promise<void> {
    // Pi emits the final toolResult entries in assistant source order; do not reorder them
    // by parallel completion time. This durable, non-model checkpoint covers a crash first.
    await this.session.appendCustomEntry('tool_checkpoint', { assistantEntryId: this.assistantEntryId, message });
  }
  async finish(outcome: 'completed' | 'aborted' | 'failed'): Promise<void> {
    if (this.finished) return;
    await this.session.appendRecord({ id: randomUUID(), type: 'operation_finished', lane: 'main', runId: this.binding.runId, outcome });
    this.finished = true;
  }
  async close(): Promise<void> {
    try { await this.repository.close(); } finally { this.onClose(); }
  }
}

/** No local durable state: every run reopens the Java-authorized MySQL journal. */
export class PiSessionStore {
  private readonly active = new Set<PiSessionRun>();
  constructor(private readonly config: SidecarConfig) {}
  async openRun(binding: SessionBinding & { sessionScope: string }): Promise<PiSessionRun> {
    const fs = new MysqlSessionFileSystem({ url: this.config.javaSessionsUrl,
      token: this.config.javaToolsToken, timeoutMs: this.config.javaToolTimeoutMs }, binding);
    await fs.claim();
    const repository = new MysqlSessionRepository(fs);
    try {
      const existing = (await repository.list()).find(item => item.id === binding.sessionScope);
      const session = existing ? await repository.open(existing) : await repository.create({ id: binding.sessionScope });
      const run = new PiSessionRun(session, repository, binding, fs.writerBinding(), () => this.active.delete(run));
      this.active.add(run);
      return run;
    } catch (error) { await repository.close().catch(() => undefined); throw error; }
  }
  async close(): Promise<void> { await Promise.all([...this.active].map(run => run.close())); }
}
