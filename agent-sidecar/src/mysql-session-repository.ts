import { randomUUID } from 'node:crypto';
import { posix } from 'node:path';
import {
  JsonlSessionRepo, Session, SessionError, FileError, ok, err,
  type SessionRepo, type SessionStorage, type SessionMetadata, type SessionCreateOptions,
  type JsonlSessionMetadata, type JsonlSessionRepoFileSystem, type FileErrorCode,
  type FileInfo, type Result, type ForkOptions, type LaneRecord, type NewRecord,
} from '@earendil-works/pi-agent-core';

export interface SessionBinding {
  runId: string;
  conversationId: string;
  turnId: string;
  toolContextId: string;
}
export interface SessionTransport { url: string; token: string; timeoutMs: number }
interface Receipt { protocolVersion: number; epoch: number; version: number; value: unknown }
const FILE_ERRORS: Record<string, FileErrorCode> = {
  aborted: 'aborted', not_found: 'not_found', permission_denied: 'permission_denied',
  not_directory: 'not_directory', is_directory: 'is_directory', invalid: 'invalid',
  not_supported: 'not_supported', unknown: 'unknown',
};

/** Pi's public journal codec runs unchanged; all durable bytes live in Java/MySQL, not files. */
export class MysqlSessionFileSystem implements JsonlSessionRepoFileSystem {
  private epoch = 0;
  private version = 0;
  private closed = false;
  private tail: Promise<unknown> = Promise.resolve();
  private readonly writerId = randomUUID();
  constructor(private readonly transport: SessionTransport, private readonly binding: SessionBinding) {}

  async claim(): Promise<void> {
    const receipt = await this.post({ operation: 'claim' });
    this.epoch = receipt.epoch;
    this.version = receipt.version;
  }
  writerBinding(): { writerId: string; epoch: number } { return { writerId: this.writerId, epoch: this.epoch }; }
  async close(): Promise<void> {
    await this.tail;
    if (this.closed) return;
    try { await this.post({ operation: 'release' }); }
    finally { this.closed = true; }
  }
  assertOpen(): void {
    if (this.closed) throw new SessionError('storage', 'Session repository is closed');
  }
  absolutePath(path: string, signal?: AbortSignal): Promise<Result<string, FileError>> {
    return Promise.resolve(signal?.aborted ? err(new FileError('aborted', 'Session operation aborted')) : ok(posix.resolve('/', path)));
  }
  joinPath(parts: string[], signal?: AbortSignal): Promise<Result<string, FileError>> {
    return this.absolutePath(posix.join(...parts), signal);
  }
  readTextFile(path: string, signal?: AbortSignal): Promise<Result<string, FileError>> { return this.call('read', path, {}, signal); }
  readTextLines(path: string, options?: { maxLines?: number; abortSignal?: AbortSignal }): Promise<Result<string[], FileError>> {
    return this.call('lines', path, { maxLines: options?.maxLines ?? 0 }, options?.abortSignal);
  }
  writeFile(path: string, content: string | Uint8Array, signal?: AbortSignal): Promise<Result<void, FileError>> {
    return this.call('write', path, { content: typeof content === 'string' ? content : new TextDecoder().decode(content) }, signal);
  }
  appendFile(path: string, content: string | Uint8Array, signal?: AbortSignal): Promise<Result<void, FileError>> {
    return this.call('append', path, { content: typeof content === 'string' ? content : new TextDecoder().decode(content) }, signal);
  }
  renameFile(source: string, target: string, signal?: AbortSignal): Promise<Result<void, FileError>> {
    return this.call('rename', source, { targetKey: posix.resolve('/', target) }, signal);
  }
  fileInfo(path: string, signal?: AbortSignal): Promise<Result<FileInfo, FileError>> { return this.call('info', path, {}, signal); }
  listDir(path: string, signal?: AbortSignal): Promise<Result<FileInfo[], FileError>> { return this.call('list', path, {}, signal); }
  exists(path: string, signal?: AbortSignal): Promise<Result<boolean, FileError>> { return this.call('exists', path, {}, signal); }
  createDir(path: string, options?: { recursive?: boolean; abortSignal?: AbortSignal }): Promise<Result<void, FileError>> {
    return this.call('mkdir', path, { recursive: options?.recursive ?? true }, options?.abortSignal);
  }
  remove(path: string, options?: { recursive?: boolean; force?: boolean; abortSignal?: AbortSignal }): Promise<Result<void, FileError>> {
    return this.call('remove', path, { recursive: options?.recursive ?? false, force: options?.force ?? false }, options?.abortSignal);
  }
  private call<T>(operation: string, path: string, values: Record<string, unknown>, signal?: AbortSignal): Promise<Result<T, FileError>> {
    const task = this.tail.then(async (): Promise<Result<T, FileError>> => {
      try {
        this.assertOpen();
        if (signal?.aborted) return err(new FileError('aborted', 'Session operation aborted'));
        const receipt = await this.post({ operation, key: posix.resolve('/', path), ...values, commitId: randomUUID() }, signal);
        this.version = receipt.version;
        return ok(receipt.value as T);
      } catch (error) {
        if (error instanceof FileError) return err(error);
        return err(new FileError('unknown', '智能体会话存储暂不可用，请重新发起请求'));
      }
    });
    this.tail = task.then(() => undefined, () => undefined);
    return task;
  }
  private async post(values: Record<string, unknown>, signal?: AbortSignal): Promise<Receipt> {
    const body = JSON.stringify({ protocolVersion: 1,
      runId: this.binding.runId, conversationId: this.binding.conversationId,
      turnId: this.binding.turnId, toolContextId: this.binding.toolContextId, writerId: this.writerId,
      epoch: this.epoch, expectedVersion: this.version, ...values });
    // A lost response may follow a committed transaction. Only the identical commit is retried;
    // never rerun the model or a business tool to repair a storage acknowledgement.
    const write = ['write', 'append', 'mkdir', 'rename', 'remove'].includes(String(values.operation));
    for (let attempt = 0; ; attempt++) {
      let response: Response;
      let payload: Record<string, unknown>;
      try {
        response = await fetch(this.transport.url, {
          method: 'POST', headers: { 'content-type': 'application/json', 'X-Agent-Bridge-Token': this.transport.token },
          body, signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(this.transport.timeoutMs)]) : AbortSignal.timeout(this.transport.timeoutMs),
        });
        payload = await response.json() as Record<string, unknown>;
      } catch (error) {
        if (write && attempt === 0 && !signal?.aborted) continue;
        throw new FileError(signal?.aborted ? 'aborted' : 'unknown', '智能体会话提交未确认，请重新发起请求');
      }
      if (!response.ok) {
        const code = typeof payload.code === 'string' ? payload.code : '';
        const fileCode = code.startsWith('PI_FILE_') ? code.slice(8).toLowerCase() : 'unknown';
        throw new FileError(FILE_ERRORS[fileCode] ?? 'unknown',
          typeof payload.message === 'string' ? payload.message : '智能体会话存储未完成');
      }
      if (payload.protocolVersion !== 1 || !Number.isSafeInteger(payload.epoch) || !Number.isSafeInteger(payload.version)) {
        throw new FileError('unknown', '智能体会话存储响应无效');
      }
      return payload as unknown as Receipt;
    }
  }
}

// JSONL's physical naming convention is narrower than Pi's opaque Session id contract.
// Encode only that implementation identifier; all public ids and parent ids round-trip unchanged.
const encodeId = (id: string): string => 'p' + Buffer.from(JSON.stringify(id), 'utf8').toString('base64url');
const decodeId = (id: string): string => JSON.parse(Buffer.from(id.slice(1), 'base64url').toString('utf8')) as string;
const metadata = (m: JsonlSessionMetadata): SessionMetadata => ({ id: decodeId(m.id), createdAt: m.createdAt,
  ...(m.parentSessionId === undefined ? {} : { parentSessionId: decodeId(m.parentSessionId) }) });

/** Public SessionRepo adapter; delegates Pi semantics, validation, context and compaction to Pi. */
export class MysqlSessionRepository implements SessionRepo {
  private readonly native: JsonlSessionRepo;
  private readonly handles = new Map<string, Session>();
  constructor(private readonly fs: MysqlSessionFileSystem) {
    this.native = new JsonlSessionRepo({ fs, sessionsRoot: '/sessions' });
  }
  async create(options: SessionCreateOptions = {}): Promise<Session> {
    this.fs.assertOpen();
    const native = await this.native.create({ cwd: '/agent', id: encodeId(options.id ?? randomUUID()),
      ...(options.parentSessionId === undefined ? {} : { parentSessionId: encodeId(options.parentSessionId) }) });
    return this.wrap(native);
  }
  async open(m: SessionMetadata): Promise<Session> {
    this.fs.assertOpen();
    const inner = await this.find(m.id);
    const existing = this.handles.get(m.id);
    return existing ?? this.wrap(await this.native.open(inner));
  }
  async list(): Promise<SessionMetadata[]> { this.fs.assertOpen(); return (await this.native.list()).map(metadata); }
  async delete(m: SessionMetadata): Promise<void> {
    this.fs.assertOpen();
    const inner = (await this.native.list()).find(item => item.id === encodeId(m.id));
    if (inner) await this.native.delete(inner);
    this.handles.delete(m.id);
  }
  async fork(source: SessionMetadata, options: ForkOptions & SessionCreateOptions = {}): Promise<Session> {
    this.fs.assertOpen();
    const native = await this.native.fork(await this.find(source.id), { ...options, cwd: '/agent',
      id: encodeId(options.id ?? randomUUID()), parentSessionId: encodeId(options.parentSessionId ?? source.id) });
    return this.wrap(native);
  }
  async close(): Promise<void> { this.handles.clear(); await this.fs.close(); }
  private async find(id: string): Promise<JsonlSessionMetadata> {
    const m = (await this.native.list()).find(item => item.id === encodeId(id));
    if (!m) throw new SessionError('not_found', 'Session not found');
    return m;
  }
  private async wrap(inner: Session<JsonlSessionMetadata>): Promise<Session> {
    const m = metadata(await inner.getMetadata());
    const guard = (): void => {
      this.fs.assertOpen();
      if (this.handles.get(m.id) !== session) throw new SessionError('not_found', 'Session removed');
    };
    const storage: SessionStorage = {
      getMetadata: async () => { guard(); return metadata(await inner.getMetadata()); },
      getLanes: async () => { guard(); return inner.getLanes(); },
      createLane: async (lane, at) => { guard(); return inner.createLane(lane, at); },
      moveLane: async (lane, to) => { guard(); return inner.moveLane(lane, to); },
      appendEntry: async (entry, lane) => { guard(); return inner.appendEntry(entry, lane); },
      appendRecord: async <TRecord extends LaneRecord>(record: NewRecord<TRecord>) => {
        guard(); return await inner.appendRecord(record) as TRecord;
      },
      getEntry: async id => { guard(); return inner.getEntry(id); },
      findEntries: async query => { guard(); return inner.findEntries(query); },
      findEntriesOnBranch: async query => { guard(); return inner.findEntriesOnBranch(query); },
      findRecords: async (query: never) => { guard(); return inner.findRecords(query); },
      findOpenOperations: async (lane, options) => { guard(); return inner.findOpenOperations(lane, options); },
      getLog: async options => { guard(); return inner.getLog(options); },
      getName: async () => { guard(); return inner.getName(); },
      setName: async name => { guard(); return inner.setName(name); },
      getLabel: async id => { guard(); return inner.getLabel(id); },
      setLabel: async (id, label) => { guard(); return inner.setLabel(id, label); },
      getStats: async () => { guard(); return inner.getStats(); },
    };
    const session = new Session(storage);
    this.handles.set(m.id, session);
    return session;
  }
}
