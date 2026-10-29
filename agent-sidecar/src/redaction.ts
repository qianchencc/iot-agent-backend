const SECRET_KEY = /(token|secret|password|api.?key|authorization|cookie|credential)/i;

export function redact(value: unknown, depth = 0): unknown {
  if (depth > 5) return "[redacted]";
  if (typeof value === "string") {
    if (value.length > 512) return `${value.slice(0, 128)}…[redacted]`;
    return value.replace(/(Bearer\s+)[^\s]+/gi, "$1[redacted]");
  }
  if (Array.isArray(value)) return value.slice(0, 50).map(item => redact(item, depth + 1));
  if (value && typeof value === "object") {
    const result: Record<string, unknown> = {};
    for (const [key, item] of Object.entries(value)) {
      result[key] = SECRET_KEY.test(key) ? "[redacted]" : redact(item, depth + 1);
    }
    return result;
  }
  return value;
}

export function safeError(error: unknown): { code: string; message: string } {
  const message = error instanceof Error ? error.message : String(error);
  return { code: "SIDECAR_OPERATION_FAILED", message: redact(message) as string };
}

export function redactPath(_path: string): string {
  return "[sidecar-path]";
}
