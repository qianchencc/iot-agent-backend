import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { PiSessionRun } from "../src/session.js";

describe("Pi session run cleanup", () => {
  it("does not hold the run terminal on a stuck release", async () => {
    const stuck = Promise.withResolvers<void>();
    let closed = false;
    const run = new PiSessionRun(
      {} as never,
      { close: () => stuck.promise } as never,
      {} as never,
      {} as never,
      () => { closed = true; },
    );

    const started = Date.now();
    await run.close();

    assert.ok(Date.now() - started < 2_500);
    assert.equal(closed, true);
    stuck.resolve();
  });
});
