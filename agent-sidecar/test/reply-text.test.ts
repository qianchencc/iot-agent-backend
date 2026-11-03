import { it } from "node:test";
import assert from "node:assert/strict";
import { createReplyTextFilter, publicReplyText } from "../src/reply-text.js";

it("keeps reasoning private at every possible chunk boundary without losing public text", () => {
  const input = "Before<ThInKiNg>private plan<think>nested secret</think>more private</thinking>After <b>literal</b>";
  for (let cut = 0; cut <= input.length; cut += 1) {
    const filter = createReplyTextFilter();
    const first = filter.push(input.slice(0, cut));
    assert.ok(!first.includes("private") && !first.includes("secret"));
    const visible = first + filter.push(input.slice(cut));
    assert.equal(visible, "BeforeAfter <b>literal</b>");
  }
  assert.equal(publicReplyText(input), "BeforeAfter <b>literal</b>");
});

it("does not leak an unfinished reasoning channel into this or the next assistant message", () => {
  const filter = createReplyTextFilter();
  assert.equal(filter.push("Public<think>private unfinished"), "Public");
  assert.equal(filter.push(" still private"), "");
  filter.finish();
  assert.equal(filter.push("Next answer"), "Next answer");
});
