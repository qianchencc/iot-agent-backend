import { randomUUID } from "node:crypto";
import { createSessionBackendConformance } from "@earendil-works/pi-agent-core/session/testing";
import { MysqlSessionFileSystem, MysqlSessionRepository } from "../dist/mysql-session-repository.js";

const base = process.env.JAVA_SESSION_TEST_URL;
if (!base) throw new Error("JAVA_SESSION_TEST_URL must point to the isolated Java/MySQL engineering fixture");
const cases = createSessionBackendConformance(async () => {
  const id = randomUUID();
  const fs = new MysqlSessionFileSystem({ url: `${base}?case=${id}`,
    token: process.env.JAVA_SESSION_TEST_TOKEN || "test-java", timeoutMs: 30_000 },
    { runId: id, conversationId: id, turnId: id, toolContextId: id });
  await fs.claim();
  const repository = new MysqlSessionRepository(fs);
  return { repository, async [Symbol.asyncDispose]() { await repository.close(); } };
});
let failures = 0;
for (const item of cases) {
  try { await item.run(); console.log(`PASS ${item.group}: ${item.name}`); }
  catch (error) { failures++; console.error(`FAIL ${item.group}: ${item.name}`, error); }
}
console.log(`Engineering conformance only: ${cases.length - failures}/${cases.length}; not business acceptance.`);
if (failures) process.exitCode = 1;
