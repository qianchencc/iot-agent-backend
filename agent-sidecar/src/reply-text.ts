const tags = ["<think>", "</think>", "<thinking>", "</thinking>"];

export interface ReplyTextFilter {
  push(text: string): string;
  finish(): void;
}

/** Generic provider channel normalization, matching the reliable Runtime parser.
 * Keep partial tag prefixes private across stream chunks; never publish then retract.
 */
export function createReplyTextFilter(): ReplyTextFilter {
  let pending = "";
  let depth = 0;
  return {
    push(text: string): string {
      pending += text;
      let visible = "";
      while (pending) {
        const start = pending.indexOf("<");
        if (start < 0) {
          if (!depth) visible += pending;
          pending = "";
          break;
        }
        if (!depth) visible += pending.slice(0, start);
        pending = pending.slice(start);
        const lower = pending.toLowerCase();
        const tag = tags.find(tag => lower.startsWith(tag));
        if (tag) {
          depth = tag.startsWith("</") ? Math.max(0, depth - 1) : depth + 1;
          pending = pending.slice(tag.length);
        } else if (tags.some(tag => tag.startsWith(lower))) {
          break;
        } else {
          if (!depth) visible += "<";
          pending = pending.slice(1);
        }
      }
      return visible;
    },
    finish(): void {
      pending = "";
      depth = 0;
    },
  };
}

export function publicReplyText(text: string): string {
  return createReplyTextFilter().push(text);
}
