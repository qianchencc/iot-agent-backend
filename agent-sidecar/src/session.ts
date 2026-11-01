import { DatabaseSync } from "node:sqlite";
import { chmodSync, cpSync, existsSync, mkdirSync, renameSync, rmSync, statSync, statfsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { buildSessionContext, type AgentMessage } from "@earendil-works/pi-agent-core";
import { NodeExecutionEnv, type Session } from "@earendil-works/pi-agent-core/node";
import {
  createNodeSqliteFactory,
  SqliteSessionRepository,
  type SqliteSessionMetadata,
} from "@earendil-works/pi-session-backend-sqlite-node";
import type { SidecarConfig } from "./config.js";
import { redactPath } from "./redaction.js";

export class PiSessionStore {
  private repository: SqliteSessionRepository;
  private readonly openSessions = new Map<string, Session<SqliteSessionMetadata>>();
  private initialized?: Promise<void>;

  constructor(private readonly config: SidecarConfig) {
    this.repository = this.createRepository();
  }

  private createRepository(): SqliteSessionRepository {
    mkdirSync(dirname(this.config.sessionPath), { recursive: true, mode: 0o700 });
    if (!existsSync(this.config.sessionPath)) {
      const database = new DatabaseSync(this.config.sessionPath);
      database.close();
    }
    return new SqliteSessionRepository({
      env: new NodeExecutionEnv({ cwd: process.cwd() }),
      sqlite: createNodeSqliteFactory(),
      databasePath: this.config.sessionPath,
      writerLease: { ttlMs: 120_000, heartbeatIntervalMs: 15_000 },
    });
  }

  private async init(): Promise<void> {
    if (!this.initialized) this.initialized = this.repository.list().then(() => undefined);
    await this.initialized;
  }

  async openOrCreate(sessionScope: string): Promise<Session<SqliteSessionMetadata>> {
    await this.init();
    const current = this.openSessions.get(sessionScope);
    if (current) return current;
    const existing = (await this.repository.list()).find(item => item.id === sessionScope);
    const session = existing
      ? await this.repository.open(existing)
      : await this.repository.create({ id: sessionScope, cwd: process.cwd(), metadata: {} });
    this.openSessions.set(sessionScope, session);
    return session;
  }

  /** Rebuilds the exact Pi context for a scope, including compaction and branch summaries. */
  async history(sessionScope: string): Promise<AgentMessage[]> {
    const session = await this.openOrCreate(sessionScope);
    const entries = await session.findEntriesOnBranch({ order: "oldestFirst" });
    return buildSessionContext(entries).messages;
  }

  async capacity(): Promise<Record<string, unknown>> {
    await this.init();
    const stats = statfsSync(dirname(this.config.sessionPath));
    const freeBytes = Number(stats.bavail) * Number(stats.bsize);
    return {
      status: freeBytes >= this.config.minimumFreeBytes ? "ok" : "insufficient_capacity",
      freeBytes,
      minimumFreeBytes: this.config.minimumFreeBytes,
    };
  }

  async integrityCheck(): Promise<Record<string, unknown>> {
    await this.init();
    const db = new DatabaseSync(this.config.sessionPath, { readOnly: true });
    try {
      const row = db.prepare("PRAGMA quick_check").get() as Record<string, unknown> | undefined;
      const integrity = String(row?.quick_check ?? row?.["quick_check"] ?? "");
      if (integrity !== "ok") throw new Error("SQLite quick_check failed");
      return { status: "ok", integrity: "ok" };
    } finally {
      db.close();
    }
  }

  async recoverWriterLease(): Promise<Record<string, unknown>> {
    await this.init();
    const db = new DatabaseSync(this.config.sessionPath);
    try {
      const now = Date.now();
      const result = db.prepare("DELETE FROM writer_leases WHERE expires_at_ms <= ?").run(now);
      return { status: "ok", recoveredLeases: Number(result.changes) };
    } finally {
      db.close();
    }
  }

  private checkDestination(destination: string): string {
    if (typeof destination !== "string" || destination.trim() === "") throw new Error("destination is required");
    const candidate = resolve(destination);
    const backupRoot = resolve(this.config.backupDir);
    if (candidate === resolve(this.config.sessionPath)
        || (candidate !== backupRoot && !candidate.startsWith(`${backupRoot}/`))) {
      throw new Error("backup path must be inside the sidecar backup directory");
    }
    return candidate;
  }

  async backup(destination: string, force: boolean): Promise<Record<string, unknown>> {
    await this.init();
    const target = this.checkDestination(destination);
    mkdirSync(dirname(target), { recursive: true, mode: 0o700 });
    if (existsSync(target)) {
      if (!force) throw new Error("backup destination already exists");
      rmSync(target, { force: true });
    }
    const db = new DatabaseSync(this.config.sessionPath);
    try {
      const escaped = target.replaceAll("'", "''");
      db.exec(`VACUUM INTO '${escaped}'`);
    } finally {
      db.close();
    }
    const check = new DatabaseSync(target, { readOnly: true });
    try {
      const row = check.prepare("PRAGMA quick_check").get() as Record<string, unknown> | undefined;
      if (String(row?.quick_check ?? "") !== "ok") throw new Error("backup integrity check failed");
    } finally {
      check.close();
    }
    return { status: "ok", integrity: "ok", bytes: statSync(target).size, destination: redactPath(target) };
  }

  async restore(backup: string, apply: boolean): Promise<Record<string, unknown>> {
    if (!apply) throw new Error("restore requires apply=true");
    const source = this.checkDestination(backup);
    if (!existsSync(source)) throw new Error("backup does not exist");
    const check = new DatabaseSync(source, { readOnly: true });
    try {
      const row = check.prepare("PRAGMA quick_check").get() as Record<string, unknown> | undefined;
      if (String(row?.quick_check ?? "") !== "ok") throw new Error("backup integrity check failed");
    } finally {
      check.close();
    }
    await this.repository.close();
    this.openSessions.clear();
    mkdirSync(dirname(this.config.sessionPath), { recursive: true, mode: 0o700 });
    const temporary = `${this.config.sessionPath}.restore-${process.pid}-${Date.now()}`;
    cpSync(source, temporary);
    chmodSync(temporary, 0o600);
    renameSync(temporary, this.config.sessionPath);
    this.repository = this.createRepository();
    this.initialized = undefined;
    await this.init();
    return { status: "ok", integrity: "ok", applied: true, backup: redactPath(source) };
  }

  async close(): Promise<void> {
    this.openSessions.clear();
    await this.repository.close();
  }
}
