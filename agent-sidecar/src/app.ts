import Fastify, { type FastifyInstance, type FastifyReply, type FastifyRequest } from "fastify";
import { timingSafeEqual } from "node:crypto";
import type { SidecarConfig } from "./config.js";
import { AgentRunner } from "./agent.js";
import { PiSessionStore } from "./session.js";
import { redact, safeError } from "./redaction.js";

interface ActiveRun {
  controller: AbortController;
  timeout: NodeJS.Timeout;
  timedOut: boolean;
}

function sameSecret(expected: string, actual: string | undefined): boolean {
  if (!actual) return false;
  const left = Buffer.from(expected);
  const right = Buffer.from(actual);
  return left.length === right.length && timingSafeEqual(left, right);
}

function bearer(request: FastifyRequest): string | undefined {
  const header = request.headers.authorization;
  if (typeof header !== "string" || !header.startsWith("Bearer ")) return undefined;
  return header.slice("Bearer ".length).trim();
}

function jsonBody(request: FastifyRequest): unknown {
  return request.body;
}

function invalid(reply: FastifyReply): void {
  reply.code(400).send({ error: "invalid_request" });
}

export function buildApp(config: SidecarConfig): FastifyInstance {
  const app = Fastify({ logger: false, bodyLimit: 1_048_576 });
  const sessions = new PiSessionStore(config);
  const runner = new AgentRunner(config, sessions);
  const active = new Map<string, ActiveRun>();

  app.addHook("preHandler", async (request, reply) => {
    const supervisorRequest = request.url === "/internal/admin/apply"
      && typeof request.headers["x-admin-supervisor-token"] === "string";
    if (supervisorRequest) return;
    if (!sameSecret(config.bridgeToken, bearer(request))) {
      await reply.code(401).send({ error: "unauthorized" });
    }
  });

  app.get("/healthz", async () => ({
    status: "ok",
    service: "agent-sidecar",
    node: process.versions.node.split(".")[0],
    activeRuns: active.size,
  }));

  app.post("/run", async (request, reply) => {
    let input;
    try {
      input = runner.parseRequest(jsonBody(request));
    } catch {
      invalid(reply);
      return;
    }
    if (active.has(input.runId)) {
      reply.code(409).send({ error: "run_already_active" });
      return;
    }
    const controller = new AbortController();
    const state: ActiveRun = {
      controller,
      timedOut: false,
      timeout: setTimeout(() => {
        state.timedOut = true;
        controller.abort();
      }, config.runTimeoutMs),
    };
    active.set(input.runId, state);
    reply.hijack();
    reply.raw.writeHead(200, {
      "content-type": "application/x-ndjson; charset=utf-8",
      "cache-control": "no-store",
      connection: "keep-alive",
      "x-content-type-options": "nosniff",
    });
    let sequence = 0;
    let heartbeat: NodeJS.Timeout | undefined;
    const onDisconnect = (): void => {
      if (!reply.raw.writableEnded) controller.abort();
    };
    reply.raw.once("close", onDisconnect);
    const emit = (event: Record<string, unknown>): void => {
      if (reply.raw.writableEnded) return;
      const type = typeof event.type === "string" ? event.type : "run.progress";
      const payload: Record<string, unknown> = {
        ...redact(event) as Record<string, unknown>,
        type,
        runId: input.runId,
        conversationId: input.conversationId,
        turnId: input.turnId,
        timestamp: new Date().toISOString(),
      };
      if (type !== "heartbeat") payload.sequence = ++sequence;
      reply.raw.write(`${JSON.stringify(payload)}\n`);
    };
    emit({ type: "run.started" });
    heartbeat = setInterval(() => emit({ type: "heartbeat" }), config.heartbeatMs);
    try {
      const outcome = await runner.run(input, emit, controller.signal);
      if (state.timedOut) emit({ type: "run.failed", code: "AGENT_ENGINE_TIMEOUT", message: "Agent Engine run timed out" });
      else if (controller.signal.aborted) emit({ type: "run.aborted", reason: "cancelled" });
      else if (outcome.terminal === "awaiting_confirmation") {
        emit({ type: "run.awaiting_confirmation", ...outcome.confirmation });
      } else emit({ type: "run.completed", outcome: "message" });
    } catch (error) {
      if (state.timedOut) emit({ type: "run.failed", code: "AGENT_ENGINE_TIMEOUT", message: "Agent Engine run timed out" });
      else if (controller.signal.aborted) emit({ type: "run.aborted", reason: "cancelled" });
      else {
        const failure = safeError(error);
        emit({ type: "run.failed", code: failure.code, message: failure.message });
      }
    } finally {
      reply.raw.removeListener("close", onDisconnect);
      clearTimeout(state.timeout);
      if (heartbeat) clearInterval(heartbeat);
      active.delete(input.runId);
      if (!reply.raw.writableEnded) reply.raw.end();
    }
  });

  app.post<{ Params: { runId: string } }>("/cancel/:runId", async (request, reply) => {
    const runId = request.params.runId;
    const run = active.get(runId);
    if (!run) {
      reply.code(404).send({ error: "run_not_found" });
      return;
    }
    run.controller.abort();
    reply.code(202).send({ accepted: true, runId });
  });


  app.post("/internal/pi-session/capacity", async (_request, reply) => {
    try { return await sessions.capacity(); } catch { reply.code(503).send({ error: "pi_session_unavailable" }); }
  });
  app.post("/internal/pi-session/integrity-check", async (_request, reply) => {
    try { return await sessions.integrityCheck(); } catch { reply.code(503).send({ error: "pi_session_integrity_failed" }); }
  });
  app.post("/internal/pi-session/writer-lease/recover", async (_request, reply) => {
    try { return await sessions.recoverWriterLease(); } catch { reply.code(503).send({ error: "pi_session_writer_recovery_failed" }); }
  });
  app.post("/internal/pi-session/backup", async (request, reply) => {
    const body = request.body;
    if (!body || typeof body !== "object" || Array.isArray(body)) { invalid(reply); return; }
    const input = body as Record<string, unknown>;
    if (typeof input.destination !== "string" || typeof input.force !== "boolean") { invalid(reply); return; }
    try { return await sessions.backup(input.destination, input.force); } catch { reply.code(503).send({ error: "pi_session_backup_failed" }); }
  });
  app.post("/internal/pi-session/restore", async (request, reply) => {
    const body = request.body;
    if (!body || typeof body !== "object" || Array.isArray(body)) { invalid(reply); return; }
    const input = body as Record<string, unknown>;
    if (typeof input.backup !== "string" || typeof input.apply !== "boolean") { invalid(reply); return; }
    try { return await sessions.restore(input.backup, input.apply); } catch { reply.code(503).send({ error: "pi_session_restore_failed" }); }
  });

  app.post("/internal/admin/apply", async (request, reply) => {
    const supervisorHeader = request.headers["x-admin-supervisor-token"] ?? request.headers["x-supervisor-token"];
    if (!sameSecret(config.supervisorToken, typeof supervisorHeader === "string" ? supervisorHeader : undefined)) {
      reply.code(401).send({ error: "supervisor_unauthorized" });
      return;
    }
    const body = request.body;
    if (!body || typeof body !== "object" || Array.isArray(body)) { invalid(reply); return; }
    const handoff = body as Record<string, unknown>;
    const acceptedHandoff = handoff.handoff === "restart"
      || (typeof handoff.handoffId === "string" && handoff.handoffId.trim() !== "");
    if (!acceptedHandoff) {
      reply.code(409).send({ error: "handoff_not_accepted" });
      return;
    }
    return { accepted: true };
  });

  app.addHook("onClose", async () => {
    for (const run of active.values()) run.controller.abort();
    await sessions.close();
  });
  return app;
}
